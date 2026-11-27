package yochat.server

import scala.concurrent.duration.*

import cats.effect.{Fiber, IO, Ref, Resource}
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Ed25519Public, Key32}
import yochat.store.{KeySource, Store, StoreConfig}
import yochat.transport.{RelayClient, RelayPayload}

/** Read-only A2 runtime: loads its own device authorization and sync root, keeps the relay connected, and applies sealed snapshots from A1. It
  * never opens the S1 session runtime and never accepts a user private key.
  */
final class CompanionService private (
  store: Store,
  fiber: Fiber[IO, Throwable, Unit],
  requestFiber: Fiber[IO, Throwable, Unit],
  releaseRelay: IO[Unit],
  relayHost: String,
  relayPort: Int
):

  def sessionJson: IO[Either[ServiceError, Value]] =
    CompanionView.load(store).flatMap {
      case Left(error)     => IO.pure(Left(ServiceError.StoreFailed(error.code)))
      case Right(snapshot) =>
        CompanionView.stateJson(store, snapshot, relayHost, relayPort, online = true).map {
          case Right(value) => Right(value)
          case Left(code)   => Left(ServiceError.StoreFailed(code))
        }
    }

  def stateJson: IO[Either[ServiceError, Value]] = sessionJson

  def messagesJson(conversationId: ByteVector, limit: Int): IO[Either[ServiceError, Value]] =
    CompanionView.messagesJson(store, conversationId, limit).map(_.left.map(error => ServiceError.StoreFailed(error.code)))

  def close: IO[Unit] = fiber.cancel *> requestFiber.cancel *> releaseRelay

object CompanionService:

  def create(
    config: ServiceConfig,
    userPub: Ed25519Public,
    passphrase: Array[Char]
  ): Resource[IO, CompanionService] =
    Store
      .openCompanion(StoreConfig.default(config.dataDir, KeySource.KeyFile(passphrase)), userPub)
      .flatMap {
        case Left(error)  => Resource.eval(IO.raiseError(new IllegalStateException(error.code)))
        case Right(store) =>
          Resource.make {
            for
              auth                  <- loadAuth(store)
              sync                  <- loadSync(store, auth)
              relay                 <- RelayClient.connect(config.relayHost, config.relayPort, store.identity.devicePublic).allocated
              (client, releaseRelay) = relay
              received              <- Ref.of[IO, Boolean](false)
              fanoutKey             <- FanoutKeyFile.read(config.dataDir)
              hash                   = authHash(sync._2)
              authCache             <- Ref.of[IO, Set[yochat.core.Ed25519Public]](Set.empty)
              conversationCache     <- Ref.of[IO, Map[yochat.core.Ed25519Public, ByteVector]](Map.empty)
              fanout                 = fanoutKey.map(key => CompanionFanoutService(store, client, key, authCache, conversationCache))
              receiver               = CompanionSyncService(store, client, auth.primaryDevicePub, hash, sync._1, fanout, received.set(true))
              fiber                 <- receiver.run.start
              status                 = receiver.statusBytes
              requestFiber          <- requestLoop(client, auth.primaryDevicePub, status, received).start
            yield new CompanionService(store, fiber, requestFiber, releaseRelay, config.relayHost, config.relayPort)
          }(_.close)
      }

  private def loadAuth(store: Store): IO[yochat.core.s2.DeviceAuthorization] =
    store.loadDevice(store.identity.devicePublic).flatMap {
      case Left(error)   => IO.raiseError(new IllegalStateException(error.code))
      case Right(record) =>
        DevicePairing.decodeAuth(new String(record.authorization.toArray, java.nio.charset.StandardCharsets.UTF_8)) match
          case Left(error) => IO.raiseError(new IllegalStateException(error))
          case Right(auth) => IO.pure(auth)
    }

  private def loadSync(
    store: Store,
    auth: yochat.core.s2.DeviceAuthorization
  ): IO[(Key32, ByteVector)] =
    for
      loaded <- store.loadDeviceSync(auth.primaryDevicePub)
      result <- loaded match
                  case Left(error) => IO.raiseError(new IllegalStateException(error.code))
                  case Right(sync) =>
                    Key32.from(sync.syncRoot) match
                      case Left(_)                                                             => IO.raiseError(new IllegalStateException("sync-root"))
                      case Right(root) if Crypto.sha256(root.bytes).bytes != auth.syncRootHash =>
                        IO.raiseError(new IllegalStateException("sync-root"))
                      case Right(root)                                                         =>
                        for
                          record  <- store.loadDevice(store.identity.devicePublic)
                          authRaw <- record match
                                       case Left(error)  => IO.raiseError(new IllegalStateException(error.code))
                                       case Right(value) => IO.pure(value.authorization)
                        yield (root, authRaw)
    yield result

  private def requestLoop(
    relay: RelayClient,
    primaryDevicePub: Ed25519Public,
    status: IO[Either[String, ByteVector]],
    received: Ref[IO, Boolean]
  ): IO[Unit] =
    def waitForSnapshot(attempt: Int): IO[Unit] =
      if attempt >= 60 then IO.unit
      else
        relay.awaitOnline(30.seconds).flatMap {
          case false => IO.sleep(2.seconds) *> waitForSnapshot(attempt + 1)
          case true  =>
            status.flatMap {
              case Left(_)      => IO.unit
              case Right(bytes) =>
                relay.send(primaryDevicePub, RelayPayload.DeviceSync(bytes)).attempt *>
                  IO.sleep(5.seconds) *>
                  received.get
                    .flatMap(done => if done then IO.unit else waitForSnapshot(attempt + 1)) // scalafix:ok DisableSyntax.no-unsafe-get
            }
        }

    def afterReconnect: IO[Unit] =
      relay.updates.flatMap {
        case true  => received.set(false) *> waitForSnapshot(0) *> afterReconnect
        case false => afterReconnect
      }

    waitForSnapshot(0) *> afterReconnect

  private def authHash(record: ByteVector): ByteVector = Crypto.sha256(record).bytes
