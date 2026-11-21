package yochat.core.app

import munit.FunSuite

import yochat.core.{TestKeys, WireFrame}

class AppLockSpec extends FunSuite:

  test("locked state emits no send or persist effects") {
    val peers           = AppTestSupport.connected
    val locked          = peers.aliceState.copy(lock = LockState.Locked(0L))
    val (next, effects) = Application.step(
      locked,
      AppEvent.UserSend(1L, peers.aliceState.sessions.values.head.conversationId, yochat.core.Crypto.text("body"), TestKeys.nonce(1), 0L)
    )
    assertEquals(next, locked)
    assertEquals(effects, Vector.empty)
  }

  test("write-before-send: persistence success is required before SendWire") {
    val peers                        = AppTestSupport.connected
    val conversation                 = peers.aliceState.sessions.values.head.conversationId
    val (persisting, persistEffects) = Application.step(
      peers.aliceState,
      AppEvent.UserSend(7L, conversation, yochat.core.Crypto.text("body"), TestKeys.nonce(1), 0L)
    )
    assert(persistEffects.exists { case AppEffect.PersistOutbound(_, _) => true; case _ => false })
    assert(!persistEffects.exists { case AppEffect.SendWire(_, _) => true; case _ => false })
    val outgoing                     = persisting.outgoing(7L)
    assertEquals(outgoing.state, OutgoingState.Persisting)

    val (queued, successEffects) = Application.step(
      persisting,
      AppEvent.PersistOutboundSucceeded(7L, outgoing.generation, 10L)
    )
    assert(successEffects.exists { case AppEffect.SendWire(7L, _: WireFrame) => true; case _ => false })
    assert(successEffects.exists { case AppEffect.StartTimer(TimerId(TimerKind.Delivery, 7L), _) => true; case _ => false })
    assertEquals(queued.outgoing(7L).state, OutgoingState.Queued)
  }

  test("lock increments generation and stale persistence results do not publish") {
    val peers                 = AppTestSupport.connected
    val conversation          = peers.aliceState.sessions.values.head.conversationId
    val (persisting, _)       = Application.step(
      peers.aliceState,
      AppEvent.UserSend(8L, conversation, yochat.core.Crypto.text("body"), TestKeys.nonce(2), 0L)
    )
    val (locked, lockEffects) = Application.step(persisting, AppEvent.Lock)
    assert(lockEffects.exists { case AppEffect.CancelTimer(_) => true; case _ => false } || lockEffects.isEmpty)
    assertEquals(locked.lock, LockState.Locked(1L))
    assert(locked.outgoing.get(8L).isEmpty)

    val (next, effects) = Application.step(
      locked,
      AppEvent.PersistOutboundSucceeded(8L, 0L, 10L)
    )
    assertEquals(next, locked)
    assertEquals(effects, Vector.empty)
  }

  test("idle timeout locks exactly at the injected deadline and unlock installs it") {
    val peers                 = AppTestSupport.connected
    val (unlocked, _)         = Application.step(
      peers.aliceState.copy(lock = LockState.Locked(0L)),
      AppEvent.Unlock(peers.alice, nowMs = 100L, idleTimeoutMs = 1_000L)
    )
    assertEquals(unlocked.lock, LockState.Unlocked(0L))
    assertEquals(unlocked.idleDeadline, Some(1_100L))
    val (early, earlyEffects) = Application.step(unlocked, AppEvent.IdleTimeout(1_099L))
    assertEquals(early.lock, LockState.Unlocked(0L))
    assertEquals(earlyEffects, Vector.empty)
    val (late, _)             = Application.step(unlocked, AppEvent.IdleTimeout(1_100L))
    assertEquals(late.lock, LockState.Locked(1L))
    assertEquals(late.idleDeadline, None)
  }
