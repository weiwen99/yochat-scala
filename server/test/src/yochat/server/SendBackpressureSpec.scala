package yochat.server

import scala.concurrent.duration.*

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
            state = OutgoingState.Queued,
            attempts = 0
          )
          val full = base.copy(outgoing = (1 to AppLimits.MaxOutgoing).map(id => id.toLong -> template.copy(id = id.toLong)).toMap)
          AppRuntime.startWithState(store, client, full).use { runtime =>
            runtime.sendMessage(fixture.conversationId, "queue-full").attempt.map { outcome =>
              assertEquals(outcome.left.map(_.getMessage), Left("queue-full"))
            }
          }
        }
      }
    }
    ()
  }
