package yochat.server

import cats.effect.{IO, Ref}
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, Key32}
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
  state: Ref[IO, (Long, ByteVector)]
):

  private def readState: IO[(Long, ByteVector)] =
    state.get // scalafix:ok DisableSyntax.no-unsafe-get

  def buildSnapshot: IO[Either[String, ByteVector]] =
    for
      syncSeqs <- store.messageSyncSeqs(lastAckedSyncSeq)
      snapshot <- store.snapshot
      result   <- (syncSeqs, snapshot) match
                    case (Left(error), _)            => IO.pure(Left(error.code))
                    case (_, Left(error))            => IO.pure(Left(error.code))
                    case (Right(seqs), Right(value)) =>
                      val messages = value.messages.values.flatten
                        .flatMap(message => seqs.get(message.id).map(seq => message -> seq))
                        .toVector
                        .sortBy { case (message, seq) => (seq, message.id) }
                      val first    = messages.headOption.map(_._2).getOrElse(lastAckedSyncSeq)
                      val last     = messages.lastOption.map(_._2).getOrElse(lastAckedSyncSeq)
                      val records  = ujson.Obj(
                        "contacts"      -> ujson.Arr(value.contacts.map(contactJson)*),
                        "conversations" -> ujson.Arr(value.conversations.map(conversationJson)*),
                        "messages"      -> ujson.Arr(messages.map { case (message, seq) => messageJson(message, seq) }*)
                      )
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
      case Left(error)  => IO.pure(Left(error))
      case Right(bytes) =>
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

  private def messageJson(record: MessageRecord, syncSeq: Long): ujson.Value =
    ujson.Obj(
      "sourceId"       -> record.id,
      "conversationId" -> record.conversationId.toHex,
      "authorPub"      -> record.authorPub.bytes.toHex,
      "devicePub"      -> record.devicePub.bytes.toHex,
      "seq"            -> record.seq,
      "body"           -> new String(record.body.toArray, "UTF-8"),
      "state"          -> stateCode(record.state),
      "createdAt"      -> record.createdAt,
      "syncSeq"        -> syncSeq
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
    lastBatchHash: ByteVector
  ): IO[DeviceSyncService] =
    Ref.of[IO, (Long, ByteVector)]((lastBatchSeq + 1L, lastBatchHash)).map { state =>
      new DeviceSyncService(store, relay, peerDevicePub, authHash, syncRoot, lastAckedSyncSeq, state)
    }
