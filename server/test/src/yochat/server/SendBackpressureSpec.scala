package yochat.server

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import yochat.core.Crypto
import yochat.core.app.*
import yochat.transport.{RelayClient, RelayServer}

class SendBackpressureSpec extends FunSuite:

  override def munitTimeout: Duration = 30.seconds

  test("a full outgoing queue rejects a new send instead of acknowledging a dropped message") {
    ExecutorTestSupport.withStore { store =>
      RelayServer.start(0).use { relay =>
        RelayClient.connect("127.0.0.1", relay.port, store.identity.devicePublic).use { client =>
          val fixture = ExecutorTestSupport.fixture()
          val base    = ExecutorTestSupport.appState(fixture)
          val template = OutgoingMessage(
            id = 0L,
            conversationId = fixture.conversationId,
            sessionId = fixture.sessionId,
            authorPub = fixture.identity.user.publicKey,
            devicePub = fixture.identity.device.publicKey,
            peerDevicePub = fixture.peerDevice,
            seq = 1L,
            body = Crypto.text("full"),
            wire = ExecutorTestSupport.frame(fixture, 1L),
            generation = 0L,
            state = OutgoingState.Failed,
            attempts = 0
          )
          val full = base.copy(outgoing = (1 to AppLimits.MaxOutgoing).map(id => id.toLong -> template.copy(id = id.toLong)).toMap)
          AppRuntime.startWithState(store, client, full).use { runtime =>
            for
              _      <- IO.sleep(150.millis)
              fatal  <- runtime.fatalError
              _      <- if fatal.isEmpty then IO.unit
                        else IO.raiseError(new AssertionError(s"startup produced fatal: $fatal"))
              outcome <- runtime.sendMessage(fixture.conversationId, "queue-full").attempt
            yield assertEquals(outcome.left.map(_.getMessage), Left("queue-full"))
          }
        }
      }
    }
    ()
  }

  test("fatal failures publish a state callback") {
    val state = ExecutorTestSupport.appState(ExecutorTestSupport.fixture())
    val error = AppExecutionError.Unsupported("test-fatal")
    (for
      fatal    <- Ref.of[IO, Option[AppExecutionError]](None)
      stateRef <- Ref.of[IO, AppState](state)
      seen     <- Ref.of[IO, Option[AppState]](None)
      _        <- AppRuntime.publishFatal(fatal, stateRef, value => seen.set(Some(value)), error)
      stored   <- fatal.get
      published <- seen.get
    yield
      assertEquals(stored, Some(error))
      assertEquals(published.flatMap(_.identity), state.identity)
    ).unsafeRunSync()
  }
  test("two concurrent sends at the cap admit exactly one") {
    ExecutorTestSupport.withStore { store =>
      RelayServer.start(0).use { relay =>
        RelayClient.connect("127.0.0.1", relay.port, store.identity.devicePublic).use { client =>
          val fixture = ExecutorTestSupport.fixture()
          val base    = ExecutorTestSupport.appState(fixture)
          val template = OutgoingMessage(
            id = 0L,
            conversationId = fixture.conversationId,
            sessionId = fixture.sessionId,
            authorPub = fixture.identity.user.publicKey,
            devicePub = fixture.identity.device.publicKey,
            peerDevicePub = fixture.peerDevice,
            seq = 1L,
            body = Crypto.text("full"),
            wire = ExecutorTestSupport.frame(fixture, 1L),
            generation = 0L,
            state = OutgoingState.Failed,
            attempts = 0
          )
          val almost = base.copy(outgoing = (1 until AppLimits.MaxOutgoing).map(id => id.toLong -> template.copy(id = id.toLong)).toMap)
          for
            _ <- ExecutorTestSupport.initialize(fixture, store)
            _ <- store.createConversation(fixture.conversationId, fixture.peerUser, 0L).flatMap(value => IO.fromEither(value.left.map(error => new AssertionError(error.code))))
            _ <- AppRuntime.startWithState(store, client, almost).use { runtime =>
                   for
                     _       <- IO.sleep(200.millis)
                     fatal   <- runtime.fatalError
                     _       <- if fatal.isEmpty then IO.unit
                                else IO.raiseError(new AssertionError(s"startup produced fatal: $fatal"))
                     results <- IO.both(
                                  runtime.sendMessage(fixture.conversationId, "first").attempt,
                                  runtime.sendMessage(fixture.conversationId, "second").attempt
                                )
                   yield
                     val outcomes = Vector(results._1, results._2)
                     assertEquals(outcomes.count(_.isRight), 1)
                     assertEquals(outcomes.collectFirst { case Left(error) => error.getMessage }, Some("queue-full"))
                 }
          yield ()
        }
      }
    }
    ()
  }
