package yochat.server

import cats.effect.IO
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Ed25519Public, Key32}
import yochat.core.s2.{JoinManifest, LogicalKey, ManifestKey, OrderDependency, OrderStamp, SyncStatus}
import yochat.store.{CompanionMessageRecord, ContactConfirmation, ContactData, ContactState, MessageState, StorageError, Store}
import yochat.transport.{RelayClient, RelayPayload}

/** S2-A receiver. It verifies the A1 snapshot, then upserts contacts, conversations and companion messages. */
final class CompanionSyncService(
  store: Store,
  relay: RelayClient,
  primaryDevicePub: Ed25519Public,
  authHash: ByteVector,
  syncRoot: Key32,
  fanout: Option[CompanionFanoutService],
  onApplied: IO[Unit] = IO.unit
):

  def run: IO[Unit] =
    relay.deviceIncoming
      .flatMap(incoming =>
        incoming.payload match
          case RelayPayload.DeviceList(bytes) =>
            IO.realTime
              .flatMap(time => PeerDeviceListService.receiveCompanion(store, incoming.from, bytes, time.toMillis))
              .flatMap {
                case Right(_) => fanout.fold(IO.unit)(_.clearAuthorization)
                case Left(_)  => IO.unit
              }
              .attempt
              .void
          case RelayPayload.Fanout(bytes)     =>
            fanout match
              case None        => IO.unit
              case Some(value) => value.receive(bytes).attempt.void
          case RelayPayload.DeviceSync(bytes) =>
            DeviceSyncProtocol.decodeSyncStatusRequest(primaryDevicePub, authHash, bytes) match
              case Right(_) =>
                statusBytes.flatMap {
                  case Left(_)      => IO.unit
                  case Right(value) => relay.send(primaryDevicePub, RelayPayload.DeviceSync(value)).attempt.void
                }
              case Left(_)  =>
                receive(bytes)
                  .flatMap {
                    case Right(_) => onApplied
                    case Left(_)  => IO.unit
                  }
                  .attempt
                  .void
          case _                              => IO.unit
      )
      .foreverM

  def statusBytes: IO[Either[String, ByteVector]] =
    store.loadDeviceSync(primaryDevicePub).flatMap {
      case Left(error) => IO.pure(Left(error.code))
      case Right(sync) =>
        SyncStatus.create(
          store.identity.devicePrivate,
          authHash,
          sync.lastBatchSeq,
          sync.lastBatchHash,
          sync.lastAckedSyncSeq
        ) match
          case Left(_)      => IO.pure(Left("sync-status"))
          case Right(value) => IO.pure(Right(DeviceSyncProtocol.syncStatus(value)))
    }

  def receive(bytes: ByteVector): IO[Either[String, Unit]] =
    DeviceSyncProtocol.open(syncRoot, primaryDevicePub, authHash, bytes) match
      case Left(error)   => IO.pure(Left(error))
      case Right(opened) =>
        loadManifest.flatMap {
          case Left(error)     => IO.pure(Left(error))
          case Right(manifest) =>
            applyRecords(opened.records, manifest).flatMap {
              case Left(error) => IO.pure(Left(error))
              case Right(_)    => recordCheckpoint(opened, bytes) *> acknowledge(opened, bytes).as(Right(()))
            }
        }

  private def recordCheckpoint(opened: DeviceSyncProtocol.Opened, bytes: ByteVector): IO[Either[String, Unit]] =
    store.loadDeviceSync(primaryDevicePub).flatMap {
      case Left(error) => IO.pure(Left(error.code))
      case Right(sync) =>
        val updated = sync.copy(
          lastAckedSyncSeq = opened.lastSyncSeq,
          lastBatchSeq = opened.batchSeq,
          lastBatchHash = DeviceSyncProtocol.batchHash(bytes)
        )
        store.lockGeneration.flatMap {
          case Left(error) => IO.pure(Left(error.code))
          case Right(lock) => store.updateDeviceSync(updated, sync.version, lock).map(_.map(_ => ()).left.map(_.code))
        }
    }

  private def acknowledge(opened: DeviceSyncProtocol.Opened, bytes: ByteVector): IO[Unit] =
    DeviceSyncProtocol.ack(
      authHash,
      opened.batchSeq,
      DeviceSyncProtocol.batchHash(bytes),
      opened.lastSyncSeq,
      store.identity.devicePrivate
    ) match
      case Left(_)    => IO.unit
      case Right(ack) => relay.send(primaryDevicePub, RelayPayload.DeviceSync(ack)).attempt.void

  private def loadManifest: IO[Either[String, JoinManifest]] =
    store.loadDevice(store.identity.devicePublic).map {
      case Left(StorageError.NotFound(_)) => Right(JoinManifest.empty)
      case Left(error)                    => Left(error.code)
      case Right(record)                  =>
        JoinManifest.fromCanonical(record.joinManifest).left.map(_ => "join-manifest")
    }

  private def applyRecords(value: Value, manifest: JoinManifest): IO[Either[String, Unit]] =
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
                               case Right(_)    => applyMessage(json, manifest)
                             }
                           }
    yield result

  private def applyContact(json: Value): IO[Either[String, Unit]] =
    parseHex(json, "userPub").flatMap(Ed25519Public.from(_).left.map(_ => "user-pub")) match
      case Left(error)    => IO.pure(Left(error))
      case Right(userPub) =>
        val contactState = string(json, "state").getOrElse("active") match
          case "deleted" => ContactState.Deleted
          case _         => ContactState.Active
        val data         = ContactData(
          channelLabel = string(json, "channelLabel").getOrElse(""),
          channelAccount = string(json, "channelAccount").getOrElse(""),
          peerChannelAccount = string(json, "peerChannelAccount"),
          alias = string(json, "alias"),
          note = string(json, "note"),
          safetyVerified = bool(json, "safetyVerified").getOrElse(false),
          confirmationState = confirmation(json),
          state = contactState,
          recreated = bool(json, "recreated").getOrElse(false)
        )
        val blocked      = bool(json, "blocked").getOrElse(false)
        store.loadContactForSync(userPub).flatMap {
          case Left(error)     => IO.pure(Left(error.code))
          case Right(existing) =>
            val write =
              contactState match
                case ContactState.Deleted =>
                  existing match
                    case Some(record) if record.isActive => store.deleteContact(userPub, record.version, lockGeneration).map(mapStore)
                    case _                               => IO.pure[Either[String, Unit]](Right(()))
                case ContactState.Active  =>
                  store.upsertContactFromSync(userPub, data, lockGeneration).map(mapStore)
            write.flatMap {
              case Left(error) => IO.pure(Left(error))
              case Right(_)    =>
                (if blocked then store.blockPeer(userPub, lockGeneration) else store.unblockPeer(userPub, lockGeneration)).map(
                  _.map(_ => ()).left.map(_.code)
                )
            }
        }

  private def applyConversation(json: Value): IO[Either[String, Unit]] =
    (parseHex(json, "id"), parseHex(json, "peerUserPub")) match
      case (Right(id), Right(peerRaw)) =>
        Ed25519Public.from(peerRaw) match
          case Left(_)     => IO.pure(Left("peer-user-pub"))
          case Right(peer) =>
            store.loadConversation(id).flatMap {
              case Right(_)                       => IO.pure(Right(()))
              case Left(StorageError.NotFound(_)) =>
                store.createConversation(id, peer, lockGeneration).map(mapStore)
              case Left(error)                    => IO.pure(Left(error.code))
            }
      case _                           => IO.pure(Left("conversation"))

  private def applyMessage(json: Value, manifest: JoinManifest): IO[Either[String, Unit]] =
    for
      sourceId     <- IO.pure(long(json, "sourceId"))
      conversation <- IO.pure(parseHex(json, "conversationId"))
      authorRaw    <- IO.pure(parseHex(json, "authorPub"))
      deviceRaw    <- IO.pure(parseHex(json, "devicePub"))
      result       <- (sourceId, conversation, authorRaw, deviceRaw) match
                        case (Some(id), Right(conv), Right(author), Right(device)) =>
                          (Ed25519Public.from(author), Ed25519Public.from(device)) match
                            case (Right(authorPub), Right(devicePub)) =>
                              val seq       = long(json, "seq").getOrElse(0L)
                              val createdAt = long(json, "createdAt").getOrElse(0L)
                              if manifest.covers(ManifestKey(conv, authorPub, devicePub), seq) then IO.pure(Right(()))
                              else
                                val key = LogicalKey(conv, devicePub, seq)
                                store
                                  .upsertCompanionMessage(
                                    CompanionMessageRecord(
                                      sourceId = id,
                                      conversationId = conv,
                                      authorPub = authorPub,
                                      devicePub = devicePub,
                                      seq = seq,
                                      body = ByteVector.view(string(json, "body").getOrElse("").getBytes("UTF-8")),
                                      state = state(json),
                                      createdAt = createdAt,
                                      syncSeq = long(json, "syncSeq").getOrElse(0L),
                                      logicalKey = Some(key)
                                    ),
                                    Some(orderStamp(json, seq))
                                  )
                                  .flatMap {
                                    case Left(error) => IO.pure(Left(error.code))
                                    case Right(_)    => IO.pure(Right(()))
                                  }
                            case _                                    => IO.pure(Left("message-key"))
                        case (None, _, _, _)                                       => IO.pure(Left("sourceId"))
                        case (_, Left(error), _, _)                                => IO.pure(Left(s"conversation:$error"))
                        case (_, _, Left(error), _)                                => IO.pure(Left(s"author:$error"))
                        case (_, _, _, Left(error))                                => IO.pure(Left(s"device:$error"))
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

  private def orderStamp(json: Value, seq: Long): OrderStamp =
    val lamport = long(json, "lamport").getOrElse(seq)
    val deps    = json.obj.get("deps").collect { case ujson.Arr(values) => values.toVector }.getOrElse(Vector.empty).flatMap {
      case ujson.Arr(values) if values.size == 2 =>
        (values(0), values(1)) match
          case (ujson.Str(raw), value) =>
            ByteVector
              .fromHex(raw)
              .flatMap(Ed25519Public.from(_).toOption)
              .map(public => OrderDependency(public, longValue(value).getOrElse(0L)))
          case _                       => None
      case _                                     => None
    }
    OrderStamp.create(lamport, deps).getOrElse(OrderStamp.legacy(seq))

  private def longValue(value: Value): Option[Long] =
    value match
      case ujson.Num(number) => Some(number.toLong)
      case ujson.Str(text)   => text.toLongOption
      case _                 => None

  private def state(json: Value): MessageState =
    long(json, "state").flatMap(value => MessageState.fromCode(value.toInt).toOption).getOrElse(MessageState.Received)
