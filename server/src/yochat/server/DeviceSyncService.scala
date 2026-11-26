package yochat.server

import cats.effect.{IO, Ref}
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, Key32}
import yochat.core.s2.{JoinManifest, LogicalKey, ManifestKey, OrderStamp}
import yochat.store.{ContactRecord, ConversationRecord, MessageRecord, MessageState, Store}
import yochat.transport.{RelayClient, RelayPayload}

/** S2-A primary sender. It sends contacts/conversations plus messages whose `sync_seq` is above the A2 checkpoint. */
final class DeviceSyncService(
  store: Store,
  relay: RelayClient,
  peerDevicePub: Ed25519Public,
  authHash: ByteVector,
  syncRoot: Key32,
  lastAckedSyncSeq: Long,
  joinManifest: JoinManifest,
  state: Ref[IO, (Long, ByteVector)]
):

  private val MaxBatchMessages: Int = 256
  private val MaxBatchBytes: Int    = yochat.transport.RelayPayload.MaxPayload - 4_096

  private def readState: IO[(Long, ByteVector)] =
    state.get // scalafix:ok DisableSyntax.no-unsafe-get

  def buildSnapshot: IO[Either[String, ByteVector]] =
    for
      syncSeqs <- store.messageSyncSeqs(lastAckedSyncSeq)
      snapshot <- store.snapshot
      orders   <- store.listAllMessageOrders
      result   <- (syncSeqs, snapshot, orders) match
                    case (Left(error), _, _)                          => IO.pure(Left(error.code))
                    case (_, Left(error), _)                          => IO.pure(Left(error.code))
                    case (_, _, Left(error))                          => IO.pure(Left(error.code))
                    case (Right(seqs), Right(value), Right(orderMap)) =>
                      val candidates = value.messages.values.flatten
                        .flatMap(message => seqs.get(message.id).map(seq => message -> seq))
                        .toVector
                        .sortBy { case (message, seq) => (seq, message.id) }
                      val messages   = candidates
                        .filter { case (message, _) =>
                          !joinManifest.covers(ManifestKey(message.conversationId, message.authorPub, message.devicePub), message.seq)
                        }
                        .take(MaxBatchMessages)
                      val base       = ujson.Obj(
                        "contacts"      -> ujson.Arr(value.contacts.map(contactJson)*),
                        "conversations" -> ujson.Arr(value.conversations.map(conversationJson)*),
                        "messages"      -> ujson.Arr()
                      )
                      val baseSize   = ujson.write(base).getBytes("UTF-8").length
                      val packed     = messages.foldLeft((Vector.empty[(MessageRecord, Long)], baseSize)) { case ((acc, size), (message, seq)) =>
                        val order   = orderMap.get(LogicalKey(message.conversationId, message.devicePub, message.seq))
                        val encoded = ujson.write(messageJson(message, seq, order)).getBytes("UTF-8").length
                        if size + encoded + 1 <= MaxBatchBytes then (acc :+ (message, seq), size + encoded + 1)
                        else (acc, size)
                      }
                      val selected   = packed._1
                      val first      = selected.headOption.map(_._2).getOrElse(lastAckedSyncSeq)
                      val last       = selected.lastOption.map(_._2).orElse(candidates.lastOption.map(_._2)).getOrElse(lastAckedSyncSeq)
                      val records    = ujson.Obj(
                        "contacts"      -> base("contacts"),
                        "conversations" -> base("conversations"),
                        "messages"      -> ujson.Arr(selected.map { case (message, seq) =>
                          messageJson(message, seq, orderMap.get(LogicalKey(message.conversationId, message.devicePub, message.seq)))
                        }*)
                      )
                      if baseSize > MaxBatchBytes || (messages.nonEmpty && selected.isEmpty) then IO.pure(Left("batch-too-large"))
                      else
                        for
                          (batchSeq, previous) <- readState
                          encoded              <- IO.fromEither(
                                                    DeviceSyncProtocol
                                                      .seal(
                                                        syncRoot,
                                                        store.identity.devicePrivate,
                                                        authHash,
                                                        batchSeq,
                                                        previous,
                                                        first,
                                                        last,
                                                        records
                                                      )
                                                      .left
                                                      .map(message => new IllegalArgumentException(message))
                                                  ).attempt
                        yield encoded.left.map(_ => "seal")
    yield result

  def sendSnapshot: IO[Either[String, Unit]] =
    buildSnapshot.flatMap {
      case Left(error)                                          => IO.pure(Left(error))
      case Right(bytes) if bytes.size > RelayPayload.MaxPayload => IO.pure(Left("batch-too-large"))
      case Right(bytes)                                         =>
        for
          (batchSeq, _) <- readState
          _             <- relay.send(peerDevicePub, RelayPayload.DeviceSync(bytes)).attempt
          _             <- state.set((batchSeq + 1L, DeviceSyncProtocol.batchHash(bytes)))
        yield Right(())
    }

  private def contactJson(record: ContactRecord): ujson.Value =
    ujson.Obj(
      "userPub"            -> record.userPub.bytes.toHex,
      "channelLabel"       -> record.data.channelLabel,
      "channelAccount"     -> record.data.channelAccount,
      "peerChannelAccount" -> record.data.peerChannelAccount.map(ujson.Str.apply).getOrElse(ujson.Null),
      "alias"              -> record.data.alias.map(ujson.Str.apply).getOrElse(ujson.Null),
      "safetyVerified"     -> record.data.safetyVerified,
      "confirmation"       -> confirmationCode(record)
    )

  private def conversationJson(record: ConversationRecord): ujson.Value =
    ujson.Obj(
      "id"          -> record.id.toHex,
      "peerUserPub" -> record.peerUserPub.bytes.toHex,
      "createdAt"   -> record.createdAt
    )

  private def messageJson(record: MessageRecord, syncSeq: Long, order: Option[OrderStamp]): ujson.Value =
    ujson.Obj(
      "sourceId"       -> record.id,
      "conversationId" -> record.conversationId.toHex,
      "authorPub"      -> record.authorPub.bytes.toHex,
      "devicePub"      -> record.devicePub.bytes.toHex,
      "seq"            -> record.seq,
      "body"           -> new String(record.body.toArray, "UTF-8"),
      "state"          -> stateCode(record.state),
      "createdAt"      -> record.createdAt,
      "syncSeq"        -> syncSeq,
      "lamport"        -> order.map(_.lamport).getOrElse(record.seq),
      "deps"           -> ujson.Arr(order.toVector.flatMap(_.deps).map(dep => ujson.Arr(ujson.Str(dep.device.bytes.toHex), ujson.Num(dep.seq.toDouble)))*)
    )

  private def confirmationCode(record: ContactRecord): Int =
    record.data.confirmationState match
      case yochat.store.ContactConfirmation.Pending         => 1
      case yochat.store.ContactConfirmation.Confirmed       => 2
      case yochat.store.ContactConfirmation.AccountMismatch => 3

  private def stateCode(state: MessageState): Int =
    MessageState.code(state)

object DeviceSyncService:

  def create(
    store: Store,
    relay: RelayClient,
    peerDevicePub: Ed25519Public,
    authHash: ByteVector,
    syncRoot: Key32,
    lastAckedSyncSeq: Long,
    lastBatchSeq: Long,
    lastBatchHash: ByteVector,
    joinManifest: JoinManifest
  ): IO[DeviceSyncService] =
    Ref.of[IO, (Long, ByteVector)]((lastBatchSeq + 1L, lastBatchHash)).map { state =>
      new DeviceSyncService(store, relay, peerDevicePub, authHash, syncRoot, lastAckedSyncSeq, joinManifest, state)
    }
