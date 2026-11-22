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
    assert(!persistEffects.exists { case AppEffect.SendWire(_, _, _, _) => true; case _ => false })
    val outgoing                     = persisting.outgoing(7L)
    assertEquals(outgoing.state, OutgoingState.Persisting)

    val (queued, successEffects) = Application.step(
      persisting,
      AppEvent.PersistOutboundSucceeded(7L, outgoing.generation, 10L)
    )
    assert(successEffects.exists { case AppEffect.SendWire(_, 7L, _, _: WireFrame) => true; case _ => false })
    assert(successEffects.exists { case AppEffect.StartTimer(TimerId(TimerKind.Delivery, 7L), _) => true; case _ => false })
    assertEquals(queued.outgoing(7L).state, OutgoingState.InFlight)
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

  test("a late send success after disconnect does not bypass the retry timer") {
    val peers                    = AppTestSupport.connected
    val conversation             = peers.aliceState.sessions.values.head.conversationId
    val (persisting, _)          = Application.step(
      peers.aliceState,
      AppEvent.UserSend(9L, conversation, yochat.core.Crypto.text("body"), TestKeys.nonce(3), 0L)
    )
    val generation               = persisting.outgoing(9L).generation
    val (queued, _)              = Application.step(persisting, AppEvent.PersistOutboundSucceeded(9L, generation, 10L))
    assertEquals(queued.outgoing(9L).state, OutgoingState.InFlight)
    val (offline, _)             = Application.step(queued, AppEvent.ConnectionChanged(online = false, nowMs = 20L))
    assertEquals(offline.outgoing(9L).state, OutgoingState.Queued)
    val (late, effects)          = Application.step(offline, AppEvent.SendSucceeded(9L, generation))
    assertEquals(late.outgoing(9L).state, OutgoingState.Queued)
    assertEquals(effects, Vector.empty)
  }

  test("a locked runtime ignores reply import and cannot pump queued data") {
    val peers       = AppTestSupport.connected
    val conversation = yochat.core.Crypto.sha256(yochat.core.Crypto.text("locked")).bytes
    val invitation  = peers.invitation
    val (created, _) = Application.step(
      AppState.empty.copy(identity = Some(peers.alice), lock = LockState.Unlocked(0L)),
      AppEvent.InvitationCreated(
        invitation,
        peers.aliceEphemeral.privateKey,
        AppTestSupport.xpair(2).privateKey,
        Some("@bob"),
        0L
      )
    )
    val locked      = created.copy(lock = LockState.Locked(1L))
    val (reply, replyEffects) = Application.step(
      locked,
      AppEvent.ReplyReceived(invitation.fields.ephPub.bytes, conversation, peers.aliceReply, Some("@bob"), 1_000L)
    )
    assertEquals(reply, locked)
    assertEquals(replyEffects, Vector.empty)
    val (connected, connectionEffects) = Application.step(locked, AppEvent.ConnectionChanged(online = true, nowMs = 2_000L))
    assertEquals(connected.connection, ConnectionState.Online)
    assertEquals(connected.outgoing, locked.outgoing)
    assertEquals(connectionEffects, Vector.empty)
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
