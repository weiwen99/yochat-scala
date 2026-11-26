package yochat.server

import scala.concurrent.duration.*

import cats.effect.{Fiber, IO, Ref}
import cats.effect.std.Queue
import scodec.bits.ByteVector

import yochat.core.{Crypto, Ed25519Public, Key32}
import yochat.store.{DeviceRecord, DeviceRole, Store}
import yochat.transport.{RelayClient, RelayPayload}

/** A1-side automatic snapshot trigger. A2 sends a small `s2-sync-request` after pairing, restart, or reconnect; A1 answers with a sealed
  * snapshot. A1 also coalesces visible-state changes and pushes a fresh snapshot to active companion devices. No polling loop or relaying of
  * user messages is added to the S1 peer path.
  */
final class DeviceSyncScheduler private (
  requestFiber: Fiber[IO, Throwable, Unit],
  changeFiber: Fiber[IO, Throwable, Unit],
  changes: Queue[IO, Unit],
  active: Ref[IO, Boolean]
):

  def notifyChange: IO[Unit] =
    active.get.flatMap(value => if value then changes.tryOffer(()).void else IO.unit) // scalafix:ok DisableSyntax.no-unsafe-get

  def close: IO[Unit] = requestFiber.cancel *> changeFiber.cancel

object DeviceSyncScheduler:

  def create(store: Store, relay: RelayClient): IO[DeviceSyncScheduler] =
    for
      changes      <- Queue.bounded[IO, Unit](16)
      active       <- Ref.of[IO, Boolean](false)
      requestFiber <- listenRequests(store, relay, active).start
      changeFiber  <- listenChanges(store, relay, changes).start
      initial      <- store.listDevices.map {
                        case Right(values) => values.exists(isActiveCompanion)
                        case Left(_)       => false
                      }
      _            <- active.set(initial)
    yield new DeviceSyncScheduler(requestFiber, changeFiber, changes, active)

  private def listenRequests(store: Store, relay: RelayClient, active: Ref[IO, Boolean]): IO[Unit] =
    relay.deviceIncoming.flatMap { incoming =>
      incoming.payload match
        case RelayPayload.DeviceSync(bytes) =>
          DeviceSyncProtocol.decodeSyncRequest(bytes) match
            case Left(_)         => acceptAck(store, incoming.from, bytes).attempt.void
            case Right(authHash) =>
              answer(store, relay, incoming.from, authHash).attempt *>
                active.set(true)
        case _                              => IO.unit
    }.foreverM

  private def listenChanges(store: Store, relay: RelayClient, changes: Queue[IO, Unit]): IO[Unit] =
    (changes.take *> IO.sleep(250.millis) *> drain(changes) *> syncAll(store, relay)).foreverM

  private def drain(changes: Queue[IO, Unit]): IO[Unit] =
    changes.tryTake.flatMap {
      case Some(_) => drain(changes)
      case None    => IO.unit
    }

  private def syncAll(store: Store, relay: RelayClient): IO[Unit] =
    store.listDevices.flatMap {
      case Left(_)        => IO.unit
      case Right(devices) =>
        devices.foldLeft(IO.unit) { (acc, record) =>
          acc *> syncDevice(store, relay, record).attempt.void
        }
    }

  private def isActiveCompanion(record: DeviceRecord): Boolean =
    record.role == DeviceRole.Companion && record.revokedAt.isEmpty

  private def syncDevice(store: Store, relay: RelayClient, record: DeviceRecord): IO[Unit] =
    if !isActiveCompanion(record) then IO.unit
    else
      store.loadDeviceSync(record.devicePub).flatMap {
        case Left(_)     => IO.unit
        case Right(sync) =>
          val authHash = Crypto.sha256(record.authorization).bytes
          Key32.from(sync.syncRoot) match
            case Left(_)     => IO.unit
            case Right(root) =>
              DeviceSyncService
                .create(store, relay, record.devicePub, authHash, root, sync.lastAckedSyncSeq, sync.lastBatchSeq, sync.lastBatchHash)
                .flatMap(_.sendSnapshot)
                .void
      }

  private def acceptAck(store: Store, device: Ed25519Public, bytes: ByteVector): IO[Unit] =
    store.loadDevice(device).flatMap {
      case Left(_)                                              => IO.unit
      case Right(record) if record.role != DeviceRole.Companion => IO.unit
      case Right(record) if record.revokedAt.isDefined          => IO.unit
      case Right(record)                                        =>
        val authHash = Crypto.sha256(record.authorization).bytes
        DeviceSyncProtocol.decodeAck(device, authHash, bytes) match
          case Left(_)    => IO.unit
          case Right(ack) =>
            store.loadDeviceSync(device).flatMap {
              case Left(_)     => IO.unit
              case Right(sync)
                  if ack.lastAckedSyncSeq < sync.lastAckedSyncSeq ||
                    ack.batchSeq < sync.lastBatchSeq ||
                    (ack.batchSeq == sync.lastBatchSeq && ack.batchHash != sync.lastBatchHash) =>
                IO.unit
              case Right(sync) =>
                val updated = sync.copy(
                  lastAckedSyncSeq = ack.lastAckedSyncSeq,
                  lastBatchSeq = ack.batchSeq,
                  lastBatchHash = ack.batchHash
                )
                store.lockGeneration.flatMap {
                  case Left(_)     => IO.unit
                  case Right(lock) => store.updateDeviceSync(updated, sync.version, lock).void
                }
            }
    }

  private def answer(
    store: Store,
    relay: RelayClient,
    device: Ed25519Public,
    authHash: ByteVector
  ): IO[Unit] =
    store.loadDevice(device).flatMap {
      case Left(_)                                                                => IO.unit
      case Right(record) if record.role != DeviceRole.Companion                   => IO.unit
      case Right(record) if record.revokedAt.isDefined                            => IO.unit
      case Right(record) if Crypto.sha256(record.authorization).bytes != authHash => IO.unit
      case Right(record)                                                          => syncDevice(store, relay, record)
    }
