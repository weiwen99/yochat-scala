package yochat.server

import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.Arrays

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Ed25519Public, Key32, SessionId}
import yochat.core.app.{AppEvent, AppLimits, AppState}
import yochat.store.{KeySource, MessageRecord, Store, StoreConfig, StorePaths}
import yochat.transport.RelayClient

final case class ServiceConfig(
  port: Int,
  dataDir: Path,
  relayHost: String,
  relayPort: Int,
  idleTimeoutMs: Long
)

enum ServiceError:
  case Locked
  case UnlockFailed
  case WeakPassphrase
  case AlreadyUnlocked
  case SessionUnavailable
  case NotFound
  case SendRefused(reason: String)
  case BadRequest(message: String)
  case StoreFailed(detail: String)
  case RuntimeFailed(detail: String)

  def code: String =
    this match
      case Locked                => "locked"
      case UnlockFailed          => "unlock-failed"
      case WeakPassphrase        => "weak-passphrase"
      case AlreadyUnlocked       => "already-unlocked"
      case SessionUnavailable    => "session-unavailable"
      case NotFound              => "not-found"
      case SendRefused(reason)   => s"send-refused:$reason"
      case BadRequest(message)   => s"bad-request:$message"
      case StoreFailed(detail)   => s"store-failed:$detail"
      case RuntimeFailed(detail) => s"runtime-failed:$detail"

final private[server] case class Running(
  store: Store,
  runtime: AppRuntime,
  relay: RelayClient,
  pairing: DevicePairingService,
  release: IO[Unit]
):

  def close: IO[Unit] = runtime.close *> relay.close *> release

final class AppService private (
  val config: ServiceConfig,
  hub: EventHub,
  sessionRef: Ref[IO, Option[Running]],
  mutex: Semaphore[IO],
  clock: Clock
):

  def isUnlocked: IO[Boolean] = AppService.readRef(sessionRef).map(_.isDefined)

  def instanceExists: IO[Boolean] =
    IO.blocking {
      val paths = StorePaths.under(config.dataDir)
      Files.isRegularFile(paths.database) &&
      Files.isRegularFile(paths.identityFile) &&
      Files.isRegularFile(paths.keyFile)
    }

  def unlock(passphrase: Array[Char]): IO[Either[ServiceError, AppState]] =
    mutex.permit
      .use { _ =>
        AppService.readRef(sessionRef).flatMap {
          case Some(_) => IO.pure(Left(ServiceError.AlreadyUnlocked))
          case None    =>
            instanceExists.flatMap { existing =>
              if !existing && passphrase.length < AppService.MinPassphraseLength then IO.pure(Left(ServiceError.WeakPassphrase))
              else
                open(passphrase).flatMap {
                  case Left(error)    => IO.pure(Left(error))
                  case Right(running) =>
                    sessionRef.set(Some(running)) *>
                      running.runtime.awaitState(_.lock.isUnlocked, AppService.UnlockWait).attempt.flatMap {
                        case Right(true) =>
                          running.runtime.state.attempt.flatMap {
                            case Right(state) => IO.pure(Right(state))
                            case Left(error)  =>
                              sessionRef.set(None) *> running.close.as(Left(ServiceError.RuntimeFailed(error.getClass.getSimpleName)))
                          }
                        case _           =>
                          sessionRef.set(None) *> running.close.as(Left(ServiceError.SessionUnavailable))
                      }
                }
            }
        }
      }
      .guarantee(IO.delay(Arrays.fill(passphrase, '\u0000')).void)

  def lock: IO[Unit] =
    mutex.permit.use { _ =>
      sessionRef.getAndSet(None).flatMap {
        case None        => IO.unit
        case Some(value) =>
          value.runtime.submit(AppEvent.Lock) *>
            value.runtime.awaitState(!_.lock.isUnlocked, 2.seconds).attempt.void *>
            value.close *> IO.delay(hub.publish("state"))
      }
    }

  def state: IO[Either[ServiceError, AppState]] =
    withRunning(_.runtime.state)

  def fatal: IO[Either[ServiceError, Option[AppExecutionError]]] =
    withRunning(_.runtime.fatalError)

  def stateJson: IO[Either[ServiceError, Value]] =
    withRunningEither { running =>
      unlockedState(running).flatMap {
        case Left(error)  => IO.pure(Left(error))
        case Right(state) =>
          for
            fatal        <- running.runtime.fatalError
            online       <- running.relay.isOnline
            notification <- running.runtime.lastNotification
            json         <- ApiJson.state(config, running.store, state, online, fatal, notification)
          yield Right(json)
      }
    }

  def sessionJson: IO[Value] =
    AppService
      .readRef(sessionRef)
      .flatMap {
        case None        => instanceExists.map(exists => ApiJson.locked(exists, config))
        case Some(value) =>
          value.runtime.state.flatMap {
            case appState if !appState.lock.isUnlocked => instanceExists.map(exists => ApiJson.locked(exists, config))
            case appState                              =>
              for
                fatal        <- value.runtime.fatalError
                online       <- value.relay.isOnline
                notification <- value.runtime.lastNotification
                json         <- ApiJson.state(config, value.store, appState, online, fatal, notification)
              yield json
          }
      }
      .handleErrorWith(error => IO.pure(ApiJson.error(ServiceError.RuntimeFailed(error.getClass.getSimpleName))))

  def messages(conversationId: ByteVector, limit: Int): IO[Either[ServiceError, List[MessageRecord]]] =
    withRunningEither { running =>
      unlockedState(running).flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right(_)    =>
          running.store
            .listRecentMessages(conversationId, limit)
            .map(_.left.map(error => ServiceError.StoreFailed(error.code)))
      }
    }

  def messagesJson(conversationId: ByteVector, limit: Int): IO[Either[ServiceError, Value]] =
    withRunningEither { running =>
      unlockedState(running).flatMap {
        case Left(error)  => IO.pure(Left(error))
        case Right(state) =>
          for records <- running.store.listRecentMessages(conversationId, math.min(1_000, limit + AppLimits.MaxBuffered))
          yield records
            .map { values =>
              val localUser    = running.store.identity.userPublic.bytes
              val conversation = state.conversations.get(conversationId)
              val visible      = values.filter(record =>
                record.authorPub.bytes == localUser ||
                  conversation.exists(value => ApiJson.visible(record, value, localUser))
              )
              ApiJson.messages(ApiJson.ordered(visible).takeRight(limit), localUser)
            }
            .left
            .map(error => ServiceError.StoreFailed(error.code))
      }
    }

  def createInvitation(
    channelLabel: String,
    channelAccount: String,
    expectedAccount: Option[String],
    ttlMs: Long,
    note: String
  ): IO[Either[ServiceError, Value]] =
    withUnlockedEither { running =>
      running.runtime
        .createInvitation(channelLabel, channelAccount, expectedAccount, ttlMs, note)
        .map(invitation => Right(ApiJson.invitation(invitation)))
        .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
    }

  def receiveInvitation(text: String, expectedAccount: Option[String]): IO[Either[ServiceError, Value]] =
    withUnlockedEither { running =>
      running.runtime
        .receiveInvitation(text, expectedAccount)
        .map(invitation => Right(ApiJson.invitation(invitation)))
        .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
    }

  def acceptInvitation(
    invitationId: ByteVector,
    channelLabel: String,
    channelAccount: String,
    note: String
  ): IO[Either[ServiceError, Value]] =
    mutex.permit.use { _ =>
      withUnlockedEither { running =>
        running.runtime
          .acceptInvitation(invitationId, channelLabel, channelAccount, note)
          .map(result => Right(ujson.Obj("replyText" -> result.text, "invitationId" -> invitationId.toHex)))
          .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
      }
    }

  def rejectInvitation(invitationId: ByteVector): IO[Either[ServiceError, Unit]] =
    withUnlockedEither { running =>
      running.runtime.submit(AppEvent.RejectInvitation(invitationId)).map {
        case true  => Right(())
        case false => Left(ServiceError.SessionUnavailable)
      }
    }

  def setAlias(user: ByteVector, alias: Option[String]): IO[Either[ServiceError, Unit]] =
    AppService.publicKey(user).flatMap {
      case Left(error)    => IO.pure(Left(error))
      case Right(userPub) =>
        withUnlockedEither { running =>
          running.runtime.submit(AppEvent.SetContactAlias(userPub, alias)).map {
            case true  => Right(())
            case false => Left(ServiceError.SessionUnavailable)
          }
        }
    }

  def verifySafety(user: ByteVector): IO[Either[ServiceError, Unit]] =
    AppService.publicKey(user).flatMap {
      case Left(error)    => IO.pure(Left(error))
      case Right(userPub) =>
        withUnlockedEither { running =>
          running.runtime.submit(AppEvent.SafetyVerified(userPub, clock.millis())).map {
            case true  => Right(())
            case false => Left(ServiceError.SessionUnavailable)
          }
        }
    }

  def sendMessage(conversationId: ByteVector, body: String): IO[Either[ServiceError, Long]] =
    withUnlockedEither { running =>
      running.runtime
        .sendMessage(conversationId, body)
        .map(id => Right(id))
        .handleErrorWith {
          case error: RuntimeError =>
            error.getMessage match
              case "queue-full" | "send-timeout" => IO.pure(Left(ServiceError.SendRefused(error.getMessage)))
              case "send-rejected"               => IO.pure(Left(ServiceError.BadRequest("send-rejected")))
              case reason                        => IO.pure(Left(ServiceError.RuntimeFailed(reason)))
          case error               =>
            IO.pure(Left(ServiceError.RuntimeFailed(error.getClass.getSimpleName)))
        }
    }

  def startRekey(sessionId: ByteVector): IO[Either[ServiceError, Unit]] =
    SessionId.from(sessionId) match
      case Left(_)      => IO.pure(Left(ServiceError.BadRequest("session-id")))
      case Right(value) =>
        withUnlockedEither { running =>
          running.runtime
            .startRekey(value)
            .map(_ => Right(()))
            .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
        }

  def createPairingInvite(ttlMs: Long): IO[Either[ServiceError, Value]] =
    withUnlockedEither { running =>
      running.pairing
        .createInvite(clock.millis(), ttlMs)
        .map {
          case Left(error) => Left(ServiceError.BadRequest(error))
          case Right(code) => Right(ujson.Obj("code" -> code))
        }
    }

  def confirmPairing(requestCode: String): IO[Either[ServiceError, Value]] =
    withUnlockedEither { running =>
      running.pairing
        .confirm(requestCode, clock.millis())
        .map {
          case Left(error) => Left(ServiceError.BadRequest(error))
          case Right(code) => Right(ujson.Obj("authCode" -> code))
        }
    }

  def devicesJson: IO[Either[ServiceError, Value]] =
    withUnlockedEither { running =>
      running.pairing.listDevicesJson.map {
        case Left(error)  => Left(ServiceError.BadRequest(error))
        case Right(value) => Right(ujson.Obj("devices" -> value))
      }
    }

  def syncDevice(deviceRaw: String): IO[Either[ServiceError, Unit]] =
    withUnlockedEither { running =>
      ByteVector.fromHex(deviceRaw).flatMap(Ed25519Public.from(_).toOption) match
        case None         => IO.pure(Left(ServiceError.BadRequest("device")))
        case Some(device) =>
          for
            loaded <- running.store.loadDevice(device)
            synced <- running.store.loadDeviceSync(device)
            result <- (loaded, synced) match
                        case (Left(error), _)             => IO.pure(Left(ServiceError.BadRequest(error.code)))
                        case (_, Left(error))             => IO.pure(Left(ServiceError.BadRequest(error.code)))
                        case (Right(record), Right(sync)) =>
                          Key32.from(sync.syncRoot) match
                            case Left(_)     => IO.pure(Left(ServiceError.BadRequest("sync-root")))
                            case Right(root) =>
                              DeviceSyncService
                                .create(
                                  running.store,
                                  running.relay,
                                  device,
                                  Crypto.sha256(record.authorization).bytes,
                                  root,
                                  record.joinCursorSyncSeq
                                )
                                .flatMap(_.sendSnapshot)
                                .map(_.left.map(ServiceError.BadRequest.apply))
          yield result
    }

  def revokeDevice(deviceRaw: String): IO[Either[ServiceError, Unit]] =
    withUnlockedEither { running =>
      running.pairing.revoke(deviceRaw).map(_.left.map(ServiceError.BadRequest.apply))
    }

  def close: IO[Unit] = lock

  private def open(passphrase: Array[Char]): IO[Either[ServiceError, Running]] =
    val storeConfig = StoreConfig.default(config.dataDir, KeySource.KeyFile(passphrase))
    Store.open(storeConfig).allocated.flatMap {
      case (Left(yochat.store.StorageError.UnlockFailed), releaseStore) =>
        releaseStore.as(Left(ServiceError.UnlockFailed))
      case (Left(error), releaseStore)                                  => releaseStore.as(Left(ServiceError.StoreFailed(error.code)))
      case (Right(store), releaseStore)                                 =>
        RelayClient.connect(config.relayHost, config.relayPort, store.identity.devicePublic).allocated.flatMap { case (relay, releaseRelay) =>
          AppRuntime
            .start(
              store,
              relay,
              idleTimeoutMs = config.idleTimeoutMs,
              onState = _ => IO.delay(hub.publish("state"))
            )
            .allocated
            .flatMap { case (runtime, releaseRuntime) =>
              DevicePairingService.create(store).flatMap { pairing =>
                val running = Running(store, runtime, relay, pairing, releaseRuntime *> releaseRelay *> releaseStore)
                running.runtime.state.attempt.flatMap {
                  case Right(_)    => IO.pure(Right(running))
                  case Left(error) => running.close.as(Left(ServiceError.RuntimeFailed(error.getClass.getSimpleName)))
                }
              }
            }
            .handleErrorWith(error => releaseRelay *> releaseStore *> IO.pure(Left(ServiceError.RuntimeFailed(error.getClass.getSimpleName))))
        }
    }

  private def unlockedState(running: Running): IO[Either[ServiceError, AppState]] =
    running.runtime.state.map(state => if state.lock.isUnlocked then Right(state) else Left(ServiceError.Locked))

  private def withUnlockedEither[A](action: Running => IO[Either[ServiceError, A]]): IO[Either[ServiceError, A]] =
    withRunningEither { running =>
      unlockedState(running).flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right(_)    => action(running)
      }
    }

  private def withRunning[A](action: Running => IO[A]): IO[Either[ServiceError, A]] =
    AppService.readRef(sessionRef).flatMap {
      case None        => IO.pure(Left(ServiceError.Locked))
      case Some(value) => action(value).map(Right(_))
    }

  private def withRunningEither[A](action: Running => IO[Either[ServiceError, A]]): IO[Either[ServiceError, A]] =
    AppService.readRef(sessionRef).flatMap {
      case None        => IO.pure(Left(ServiceError.Locked))
      case Some(value) => action(value)
    }

object AppService:

  private val MinPassphraseLength: Int   = 8
  private val UnlockWait: FiniteDuration = 5.seconds

  def create(config: ServiceConfig, hub: EventHub, clock: Clock = Clock.systemUTC()): IO[AppService] =
    for
      session <- Ref.of[IO, Option[Running]](None)
      mutex   <- Semaphore[IO](1)
    yield new AppService(config, hub, session, mutex, clock)

  private def readRef[A](ref: Ref[IO, A]): IO[A] =
    ref.get // scalafix:ok DisableSyntax.no-unsafe-get

  private def publicKey(raw: ByteVector): IO[Either[ServiceError, yochat.core.Ed25519Public]] =
    yochat.core.Ed25519Public.from(raw) match
      case Right(value) => IO.pure(Right(value))
      case Left(_)      => IO.pure(Left(ServiceError.BadRequest("public-key")))
