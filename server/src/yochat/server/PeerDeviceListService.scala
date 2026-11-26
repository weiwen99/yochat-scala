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
          result  <- version match
                       case Left(error)  => IO.pure(Left(error.code))
                       case Right(value) =>
                         store.listDevices.map {
                           case Left(error)    => Left(error.code)
                           case Right(devices) =>
                             val entries = (devices.map(toEntry) ++ primaryFallback(devices, store)).toVector
                             PeerDeviceList
                               .create(priv, store.identity.userPublic, value, issuedAt, entries)
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
                store.upsertPeerDeviceList(list, receivedAt).map(_.map(_ => ()).left.map(_.code))
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
      record.revokedAt
    )

  private def primaryFallback(devices: List[DeviceRecord], store: Store): Vector[PeerDeviceEntry] =
    if devices.exists(_.role == DeviceRole.Primary) then Vector.empty
    else Vector(PeerDeviceEntry(store.identity.devicePublic, PeerDeviceRole.Primary, "本机", 0L, None))
