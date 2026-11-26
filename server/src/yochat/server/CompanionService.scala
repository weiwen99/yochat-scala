package yochat.server

import cats.effect.{Fiber, IO, Resource}
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Ed25519Public, Key32}
import yochat.store.{KeySource, Store, StoreConfig}
import yochat.transport.RelayClient

/** Read-only A2 runtime: loads its own device authorization and sync root, keeps the relay connected, and applies sealed snapshots from A1. It
  * never opens the S1 session runtime and never accepts a user private key.
  */
final class CompanionService private (
  store: Store,
  fiber: Fiber[IO, Throwable, Unit],
  releaseRelay: IO[Unit],
  relayHost: String,
  relayPort: Int
):

  def sessionJson: IO[Either[ServiceError, Value]] =
    CompanionView.load(store).map {
      case Left(error)     => Left(ServiceError.StoreFailed(error.code))
      case Right(snapshot) => Right(CompanionView.stateJson(store, snapshot, relayHost, relayPort, online = true))
    }

  def stateJson: IO[Either[ServiceError, Value]] = sessionJson

  def messagesJson(conversationId: ByteVector, limit: Int): IO[Either[ServiceError, Value]] =
    CompanionView.messagesJson(store, conversationId, limit).map(_.left.map(error => ServiceError.StoreFailed(error.code)))

  def close: IO[Unit] = fiber.cancel *> releaseRelay

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
              receiver               = CompanionSyncService(store, client, auth.primaryDevicePub, authHash(record = sync._2), sync._1)
              fiber                 <- receiver.run.start
            yield new CompanionService(store, fiber, releaseRelay, config.relayHost, config.relayPort)
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

  private def authHash(record: ByteVector): ByteVector = Crypto.sha256(record).bytes
