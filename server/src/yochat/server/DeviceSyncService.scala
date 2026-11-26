package yochat.server

import cats.effect.{IO, Ref}
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, Key32}
import yochat.store.{ContactRecord, ConversationRecord, MessageRecord, MessageState, Store, StoreSnapshot}
import yochat.transport.{RelayClient, RelayPayload}

/** S2-A primary sender. It snapshots the post-join store view; A2 upserts by source id. This is intentionally not the final incremental
  * checkpoint protocol.
  */
final class DeviceSyncService(
  store: Store,
  relay: RelayClient,
  peerDevicePub: Ed25519Public,
  authHash: ByteVector,
  syncRoot: Key32,
  joinCursorSourceId: Long,
  state: Ref[IO, (Long, ByteVector)]
):

  private def readState: IO[(Long, ByteVector)] =
    state.get // scalafix:ok DisableSyntax.no-unsafe-get

  def buildSnapshot: IO[Either[String, ByteVector]] =
    for
      snapshot <- store.snapshot
      result   <- snapshot match
                    case Left(error)  => IO.pure(Left(error.code))
                    case Right(value) =>
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
                                                      records(value, joinCursorSourceId)
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

  private def records(snapshot: StoreSnapshot, cursor: Long): ujson.Value =
    val contacts           = snapshot.contacts.map(contactJson)
    val conversations      = snapshot.conversations.map(conversationJson)
    val messages           = snapshot.messages.values.flatten
      .filter(_.id > cursor)
      .toVector
      .sortBy(message => (message.id, message.seq))
      .map(messageJson)
    ujson.Obj(
      "contacts"      -> ujson.Arr(contacts*),
      "conversations" -> ujson.Arr(conversations*),
      "messages"      -> ujson.Arr(messages*)
    )

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

  private def messageJson(record: MessageRecord): ujson.Value =
    ujson.Obj(
      "sourceId"       -> record.id,
      "conversationId" -> record.conversationId.toHex,
      "authorPub"      -> record.authorPub.bytes.toHex,
      "devicePub"      -> record.devicePub.bytes.toHex,
      "seq"            -> record.seq,
      "body"           -> new String(record.body.toArray, "UTF-8"),
      "state"          -> stateCode(record.state),
      "createdAt"      -> record.createdAt,
      "syncSeq"        -> 0L
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
    joinCursorSourceId: Long
  ): IO[DeviceSyncService] =
    Ref.of[IO, (Long, ByteVector)]((0L, ByteVector.fill(32)(0))).map { state =>
      new DeviceSyncService(store, relay, peerDevicePub, authHash, syncRoot, joinCursorSourceId, state)
    }
