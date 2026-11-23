package yochat.core.app

import munit.FunSuite

import yochat.core.{Crypto, TestKeys}

class OutboxSpec extends FunSuite:

  test("Q4: reconnect replays unconfirmed messages and offline cancels timers") {
    val peers                     = AppTestSupport.connected
    val conversation              = peers.aliceState.sessions.values.head.conversationId
    val (persisting, _)           = Application.step(peers.aliceState, AppEvent.UserSend(1L, conversation, Crypto.text("body"), TestKeys.nonce(1), 0L))
    val generation                = persisting.outgoing(1L).generation
    val (sent, _)                 = Application.step(persisting, AppEvent.PersistOutboundSucceeded(1L, generation, 1L))
    assertEquals(sent.outgoing(1L).state, OutgoingState.InFlight)
    val (offline, offlineEffects) = Application.step(sent, AppEvent.ConnectionChanged(online = false, nowMs = 2L))
    assert(offlineEffects.exists { case AppEffect.CancelTimer(_) => true; case _ => false })
    assert(!offline.timers.keys.exists {
      case TimerId.Message(_, _) => true
      case _                     => false
    })
    assert(offline.timers.contains(TimerId.Idle))
    assertEquals(offline.outgoing(1L).state, OutgoingState.Queued)
    val (online, onlineEffects)   = Application.step(offline, AppEvent.ConnectionChanged(online = true, nowMs = 3L))
    assert(onlineEffects.exists { case AppEffect.SendWire(_, 1L, _, _) => true; case _ => false })
    assertEquals(online.outgoing(1L).attempts, 2)
  }

  test("Q3: retry is bounded and the final failure is notified") {
    val peers                      = AppTestSupport.connected
    val conversation               = peers.aliceState.sessions.values.head.conversationId
    val (persisting, _)            = Application.step(peers.aliceState, AppEvent.UserSend(2L, conversation, Crypto.text("body"), TestKeys.nonce(2), 0L))
    val generation                 = persisting.outgoing(2L).generation
    val (queued, _)                = Application.step(persisting, AppEvent.PersistOutboundSucceeded(2L, generation, 1L))
    val (afterOne, oneEffects)     = Application.step(queued, AppEvent.SendFailed(2L, generation, 2L))
    assert(oneEffects.exists { case AppEffect.StartTimer(TimerId(TimerKind.Retry, 2L), _) => true; case _ => false })
    val (afterTwo, twoEffects)     = Application.step(afterOne, AppEvent.TimerFired(TimerId(TimerKind.Retry, 2L), generation, 3L))
    assert(twoEffects.exists { case AppEffect.SendWire(_, 2L, _, _) => true; case _ => false })
    val (afterThree, threeEffects) = Application.step(afterTwo, AppEvent.SendFailed(2L, generation, 4L))
    assert(threeEffects.exists { case AppEffect.Notify(Notification.MessageFailed) => true; case _ => false })
    assertEquals(afterThree.outgoing(2L).state, OutgoingState.Failed)
  }

  test("Q3: persistence failure never sends and clears the candidate") {
    val peers             = AppTestSupport.connected
    val conversation      = peers.aliceState.sessions.values.head.conversationId
    val (persisting, _)   = Application.step(peers.aliceState, AppEvent.UserSend(3L, conversation, Crypto.text("body"), TestKeys.nonce(3), 0L))
    val generation        = persisting.outgoing(3L).generation
    val (failed, effects) = Application.step(persisting, AppEvent.PersistOutboundFailed(3L, generation, 1L))
    assertEquals(failed.outgoing(3L).state, OutgoingState.Failed)
    assert(!effects.exists { case AppEffect.SendWire(_, _, _, _) => true; case _ => false })
    assert(effects.exists { case AppEffect.Notify(Notification.MessageFailed) => true; case _ => false })
  }
