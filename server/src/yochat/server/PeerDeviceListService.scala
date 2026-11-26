package yochat.server

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.s2.{PeerDeviceEntry, PeerDeviceList, PeerDeviceRole}
import yochat.core.Ed25519Public
import yochat.store.{DeviceRecord, DeviceRole, Store}
import yochat.transport.{RelayClient, RelayPayload}

/** B1 contact-visible device list. Only the primary holds the user private key, so this is a strict signed snapshot, not a mergeable op set. */
object PeerDeviceListService:

  def build(store: Store, issuedAt: Long): IO[Either[String, PeerDeviceList]] =
    store.identity.userPrivate match
      case None       => IO.pure(Left("primary-user-key"))
      case Some(priv) =>
        for
          version <- store.nextPeerDeviceListVersion
          seen    <- store.s2bSeen
          result  <- version match
                       case Left(error)  => IO.pure(Left(error.code))
                       case Right(value) =>
                         store.listDevices.map {
                           case Left(error)    => Left(error.code)
                           case Right(devices) =>
                             val entries  = (devices.map(toEntry) ++ primaryFallback(devices, store)).toVector
                             val features =
                               if seen.getOrElse(false) || devices.exists(record =>
                                   record.role == DeviceRole.Companion && record.revokedAt.isEmpty && record.fanoutPub.isDefined
                                 )
                               then PeerDeviceList.FeatureS2b
                               else 0L
                             PeerDeviceList
                               .create(priv, store.identity.userPublic, value, issuedAt, entries, features)
                               .left
                               .map(_ => "device-list")
                         }
        yield result

  def publish(store: Store, relay: RelayClient, issuedAt: Long): IO[Unit] =
    build(store, issuedAt).flatMap {
      case Left(_)     => IO.unit
      case Right(list) =>
        store.listSessions.flatMap {
          case Left(_)         => IO.unit
          case Right(sessions) =>
            val targets = sessions.map(_.state).groupBy(state => (state.peerUserPub, state.peerDevicePub)).keys.toVector
            targets.foldLeft(IO.unit) { case (acc, (_, devicePub)) =>
              acc *> relay.send(devicePub, RelayPayload.DeviceList(list.bytes)).attempt.void
            } *> publishToCompanions(store, relay, list)
        }
    }

  /** Companion devices have no S1 session. Send the current signed list to every non-revoked companion named by a contact's list. */
  private def publishToCompanions(store: Store, relay: RelayClient, list: PeerDeviceList): IO[Unit] =
    store.listContacts.flatMap {
      case Left(_)         => IO.unit
      case Right(contacts) =>
        contacts.foldLeft(IO.unit) { (acc, contact) =>
          acc *> store.loadPeerDeviceList(contact.userPub).flatMap {
            case Left(_)         => IO.unit
            case Right(peerList) =>
              peerList.entries
                .filter(entry => entry.role == PeerDeviceRole.Companion && entry.revokedAt.isEmpty)
                .foldLeft(IO.unit) { (inner, entry) =>
                  inner *> relay.send(entry.devicePub, RelayPayload.DeviceList(list.bytes)).attempt.void
                }
          }
        }
    }

  def receive(store: Store, from: Ed25519Public, bytes: ByteVector, receivedAt: Long): IO[Either[String, Unit]] =
    PeerDeviceList.fromBytes(bytes) match
      case Left(_)     => IO.pure(Left("device-list"))
      case Right(list) =>
        store.listSessions.flatMap {
          case Left(error)     => IO.pure(Left(error.code))
          case Right(sessions) =>
            sessions.find(session => session.state.peerDevicePub == from && session.state.peerUserPub == list.userPub) match
              case None    => IO.pure(Left("not-a-contact"))
              case Some(_) =>
                store.upsertPeerDeviceList(list, receivedAt).flatMap { result =>
                  val mark =
                    if result.isRight then
                      FanoutService.invalidateTargets(store, list.userPub)
                      if (list.features & PeerDeviceList.FeatureS2b) != 0L then store.markS2bSeen.void else IO.unit
                    else IO.unit
                  mark.as(result.map(_ => ()).left.map(_.code))
                }
        }

  def receiveCompanion(
    store: Store,
    from: Ed25519Public,
    bytes: ByteVector,
    receivedAt: Long
  ): IO[Either[String, Unit]] =
    PeerDeviceList.fromBytes(bytes) match
      case Left(_)     => IO.pure(Left("device-list"))
      case Right(list) =>
        val sender = list.entries.find(entry => entry.devicePub == from)
        store.loadContact(list.userPub).flatMap {
          case Left(_)                                                                                               => IO.pure(Left("not-a-contact"))
          case Right(_) if sender.forall(entry => entry.role != PeerDeviceRole.Primary || entry.revokedAt.isDefined) =>
            IO.pure(Left("not-primary"))
          case Right(_)                                                                                              =>
            store.upsertPeerDeviceList(list, receivedAt).flatMap { result =>
              val mark =
                if result.isRight then
                  FanoutService.invalidateTargets(store, list.userPub)
                  if (list.features & PeerDeviceList.FeatureS2b) != 0L then store.markS2bSeen.void else IO.unit
                else IO.unit
              mark.as(result.map(_ => ()).left.map(_.code))
            }
        }

  def devicesFor(store: Store, userPub: Ed25519Public): IO[Either[String, Vector[PeerDeviceEntry]]] =
    store
      .loadPeerDeviceList(userPub)
      .map[Either[String, Vector[PeerDeviceEntry]]] {
        case Right(list) => Right(list.entries)
        case Left(_)     => Right(Vector.empty)
      }
      .flatMap {
        case Right(entries) if entries.exists(_.role == PeerDeviceRole.Primary) => IO.pure(Right(entries))
        case Right(entries)                                                     =>
          store.listSessions.map {
            case Left(_)         => Right(entries)
            case Right(sessions) =>
              sessions
                .map(_.state)
                .find(state => state.peerUserPub == userPub)
                .map(state => PeerDeviceEntry(state.peerDevicePub, PeerDeviceRole.Primary, "", 0L, None))
                .map(primary => Right(entries :+ primary))
                .getOrElse(Right(entries))
          }
        case Left(error)                                                        => IO.pure(Left(error))
      }

  private def toEntry(record: DeviceRecord): PeerDeviceEntry =
    PeerDeviceEntry(
      record.devicePub,
      record.role match
        case DeviceRole.Primary   => PeerDeviceRole.Primary
        case DeviceRole.Companion => PeerDeviceRole.Companion,
      record.name,
      record.createdAt,
      record.revokedAt,
      record.fanoutPub
    )

  private def primaryFallback(devices: List[DeviceRecord], store: Store): Vector[PeerDeviceEntry] =
    if devices.exists(_.role == DeviceRole.Primary) then Vector.empty
    else Vector(PeerDeviceEntry(store.identity.devicePublic, PeerDeviceRole.Primary, "本机", 0L, None))
