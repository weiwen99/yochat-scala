package yochat.server

import cats.effect.IO
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Ed25519Public, Key32}
import yochat.store.{
  CompanionMessageRecord,
  ContactConfirmation,
  ContactData,
  MessageState,
  StorageError,
  Store
}
import yochat.transport.{RelayClient, RelayPayload}

/** S2-A receiver. It verifies the A1 snapshot, then upserts contacts, conversations and companion messages. */
final class CompanionSyncService(
  store: Store,
  relay: RelayClient,
  primaryDevicePub: Ed25519Public,
  authHash: ByteVector,
  syncRoot: Key32
):

  def run: IO[Unit] =
    relay.deviceIncoming
      .flatMap(incoming =>
        incoming.payload match
          case RelayPayload.DeviceSync(bytes) => receive(bytes).attempt.void
          case _                              => IO.unit
      )
      .foreverM

  def receive(bytes: ByteVector): IO[Either[String, Unit]] =
    DeviceSyncProtocol.open(syncRoot, primaryDevicePub, authHash, bytes) match
      case Left(error) => IO.pure(Left(error))
      case Right(value) => applyRecords(value)

  private def applyRecords(value: Value): IO[Either[String, Unit]] =
    for
      contacts      <- IO.pure(value.obj.get("contacts").map(asArray).getOrElse(Vector.empty))
      conversations <- IO.pure(value.obj.get("conversations").map(asArray).getOrElse(Vector.empty))
      messages      <- IO.pure(value.obj.get("messages").map(asArray).getOrElse(Vector.empty))
      result        <- contacts.foldLeft(IO.pure[Either[String, Unit]](Right(()))) { (acc, json) =>
                         acc.flatMap {
                           case Left(error) => IO.pure(Left(error))
                           case Right(_)    => applyContact(json)
                         }
                       }
      result        <- result match
                         case Left(error) => IO.pure(Left(error))
                         case Right(_)    =>
                           conversations.foldLeft(IO.pure[Either[String, Unit]](Right(()))) { (acc, json) =>
                             acc.flatMap {
                               case Left(error) => IO.pure(Left(error))
                               case Right(_)    => applyConversation(json)
                             }
                           }
      result        <- result match
                         case Left(error) => IO.pure(Left(error))
                         case Right(_)    =>
                           messages.foldLeft(IO.pure[Either[String, Unit]](Right(()))) { (acc, json) =>
                             acc.flatMap {
                               case Left(error) => IO.pure(Left(error))
                               case Right(_)    => applyMessage(json)
                             }
                           }
    yield result

  private def applyContact(json: Value): IO[Either[String, Unit]] =
    parseHex(json, "userPub").flatMap(Ed25519Public.from(_).left.map(_ => "user-pub")) match
      case Left(error) => IO.pure(Left(error))
      case Right(userPub) =>
        val data = ContactData(
          channelLabel = string(json, "channelLabel").getOrElse(""),
          channelAccount = string(json, "channelAccount").getOrElse(""),
          peerChannelAccount = string(json, "peerChannelAccount"),
          alias = string(json, "alias"),
          note = None,
          safetyVerified = bool(json, "safetyVerified").getOrElse(false),
          confirmationState = confirmation(json)
        )
        store.loadContact(userPub).flatMap {
          case Right(record) => store.updateContact(userPub, data, record.version, lockGeneration).map(mapStore)
          case Left(StorageError.NotFound(_)) => store.insertContact(userPub, data, lockGeneration).map(mapStore)
          case Left(error) => IO.pure(Left(error.code))
        }

  private def applyConversation(json: Value): IO[Either[String, Unit]] =
    (parseHex(json, "id"), parseHex(json, "peerUserPub")) match
      case (Right(id), Right(peerRaw)) =>
        Ed25519Public.from(peerRaw) match
          case Left(_)       => IO.pure(Left("peer-user-pub"))
          case Right(peer)   =>
            store.loadConversation(id).flatMap {
              case Right(_)                       => IO.pure(Right(()))
              case Left(StorageError.NotFound(_)) =>
                store.createConversation(id, peer, lockGeneration).map(mapStore)
              case Left(error)                    => IO.pure(Left(error.code))
            }
      case _ => IO.pure(Left("conversation"))

  private def applyMessage(json: Value): IO[Either[String, Unit]] =
    for
      sourceId   <- IO.pure(long(json, "sourceId"))
      conversation <- IO.pure(parseHex(json, "conversationId"))
      authorRaw  <- IO.pure(parseHex(json, "authorPub"))
      deviceRaw  <- IO.pure(parseHex(json, "devicePub"))
      result     <- (sourceId, conversation, authorRaw, deviceRaw) match
                      case (Some(id), Right(conv), Right(author), Right(device)) =>
                        (Ed25519Public.from(author), Ed25519Public.from(device)) match
                          case (Right(authorPub), Right(devicePub)) =>
                            store
                              .upsertCompanionMessage(
                                CompanionMessageRecord(
                                  sourceId = id,
                                  conversationId = conv,
                                  authorPub = authorPub,
                                  devicePub = devicePub,
                                  seq = long(json, "seq").getOrElse(0L),
                                  body = ByteVector.view(string(json, "body").getOrElse("").getBytes("UTF-8")),
                                  state = state(json),
                                  createdAt = long(json, "createdAt").getOrElse(0L),
                                  syncSeq = long(json, "syncSeq").getOrElse(0L)
                                )
                              )
                              .map(mapStore)
                          case _ => IO.pure(Left("message-key"))
                      case (None, _, _, _) => IO.pure(Left("sourceId"))
                      case (_, Left(error), _, _) => IO.pure(Left(s"conversation:$error"))
                      case (_, _, Left(error), _) => IO.pure(Left(s"author:$error"))
                      case (_, _, _, Left(error)) => IO.pure(Left(s"device:$error"))
    yield result

  private def lockGeneration: Long = 0L

  private def mapStore(value: Either[StorageError, ?]): Either[String, Unit] =
    value match
      case Right(_)    => Right(())
      case Left(error) => Left(error.code)

  private def asArray(value: Value): Vector[Value] =
    value match
      case ujson.Arr(values) => values.toVector
      case _                 => Vector.empty

  private def parseHex(json: Value, name: String): Either[String, ByteVector] =
    json.obj.get(name).collect { case ujson.Str(text) => text }.flatMap(text => ByteVector.fromHex(text)) match
      case Some(value) => Right(value)
      case None        => Left(name)

  private def string(json: Value, name: String): Option[String] =
    json.obj.get(name).collect { case ujson.Str(value) => value }

  private def bool(json: Value, name: String): Option[Boolean] =
    json.obj.get(name).collect { case ujson.Bool(value) => value }

  private def long(json: Value, name: String): Option[Long] =
    json.obj.get(name).flatMap {
      case ujson.Num(value) => Some(value.toLong)
      case ujson.Str(value) => value.toLongOption
      case _                => None
    }

  private def confirmation(json: Value): ContactConfirmation =
    long(json, "confirmation").getOrElse(1L) match
      case 2L => ContactConfirmation.Confirmed
      case 3L => ContactConfirmation.AccountMismatch
      case _  => ContactConfirmation.Pending

  private def state(json: Value): MessageState =
    long(json, "state").flatMap(value => MessageState.fromCode(value.toInt).toOption).getOrElse(MessageState.Received)
