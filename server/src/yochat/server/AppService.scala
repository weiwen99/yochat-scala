package yochat.server

import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.Arrays

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.app.{AppEvent, AppLimits, AppState}
import yochat.core.SessionId
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
      case BadRequest(message)   => s"bad-request:$message"
      case StoreFailed(detail)   => s"store-failed:$detail"
      case RuntimeFailed(detail) => s"runtime-failed:$detail"

final private[server] case class Running(
  store: Store,
  runtime: AppRuntime,
  relay: RelayClient,
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
    withRunning { running =>
      for
        appState <- running.runtime.state
        fatal    <- running.runtime.fatalError
        online   <- running.relay.isOnline
        json     <- ApiJson.state(config, running.store, appState, online, fatal)
      yield json
    }

  def sessionJson: IO[Value] =
    AppService
      .readRef(sessionRef)
      .flatMap {
        case None        => instanceExists.map(exists => ApiJson.locked(exists, config))
        case Some(value) =>
          for
            appState <- value.runtime.state
            fatal    <- value.runtime.fatalError
            online   <- value.relay.isOnline
            json     <- ApiJson.state(config, value.store, appState, online, fatal)
          yield json
      }
      .handleErrorWith(error => IO.pure(ApiJson.error(ServiceError.RuntimeFailed(error.getClass.getSimpleName))))

  def messages(conversationId: ByteVector, limit: Int): IO[Either[ServiceError, List[MessageRecord]]] =
    withRunningEither { running =>
      running.store
        .listRecentMessages(conversationId, limit)
        .map(_.left.map(error => ServiceError.StoreFailed(error.code)))
    }

  def messagesJson(conversationId: ByteVector, limit: Int): IO[Either[ServiceError, Value]] =
    withRunningEither { running =>
      for
        state   <- running.runtime.state
        records <- running.store.listRecentMessages(conversationId, math.min(1_000, limit + AppLimits.MaxBuffered))
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

  def createInvitation(
    channelLabel: String,
    channelAccount: String,
    expectedAccount: Option[String],
    ttlMs: Long,
    note: String
  ): IO[Either[ServiceError, Value]] =
    withRunningEither { running =>
      running.runtime
        .createInvitation(channelLabel, channelAccount, expectedAccount, ttlMs, note)
        .map(invitation => Right(ApiJson.invitation(invitation)))
        .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
    }

  def receiveInvitation(text: String, expectedAccount: Option[String]): IO[Either[ServiceError, Value]] =
    withRunningEither { running =>
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
    withRunningEither { running =>
      running.runtime
        .acceptInvitation(invitationId, channelLabel, channelAccount, note)
        .map(result => Right(ujson.Obj("replyText" -> result.text, "invitationId" -> invitationId.toHex)))
        .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
    }

  def rejectInvitation(invitationId: ByteVector): IO[Either[ServiceError, Unit]] =
    withRunningEither { running =>
      running.runtime.submit(AppEvent.RejectInvitation(invitationId)).map {
        case true  => Right(())
        case false => Left(ServiceError.SessionUnavailable)
      }
    }

  def setAlias(user: ByteVector, alias: Option[String]): IO[Either[ServiceError, Unit]] =
    AppService.publicKey(user).flatMap {
      case Left(error)    => IO.pure(Left(error))
      case Right(userPub) =>
        withRunningEither { running =>
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
        withRunningEither { running =>
          running.runtime.submit(AppEvent.SafetyVerified(userPub, clock.millis())).map {
            case true  => Right(())
            case false => Left(ServiceError.SessionUnavailable)
          }
        }
    }

  def sendMessage(conversationId: ByteVector, body: String): IO[Either[ServiceError, Long]] =
    withRunningEither { running =>
      running.runtime
        .sendMessage(conversationId, body)
        .map(id => Right(id))
        .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
    }

  def startRekey(sessionId: ByteVector): IO[Either[ServiceError, Unit]] =
    SessionId.from(sessionId) match
      case Left(_)      => IO.pure(Left(ServiceError.BadRequest("session-id")))
      case Right(value) =>
        withRunningEither { running =>
          running.runtime
            .startRekey(value)
            .map(_ => Right(()))
            .handleErrorWith(error => IO.pure(Left(ServiceError.BadRequest(error.getClass.getSimpleName))))
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
              val running = Running(store, runtime, relay, releaseRuntime *> releaseRelay *> releaseStore)
              running.runtime.state.attempt.flatMap {
                case Right(_)    => IO.pure(Right(running))
                case Left(error) => running.close.as(Left(ServiceError.RuntimeFailed(error.getClass.getSimpleName)))
              }
            }
            .handleErrorWith(error => releaseRelay *> releaseStore *> IO.pure(Left(ServiceError.RuntimeFailed(error.getClass.getSimpleName))))
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
