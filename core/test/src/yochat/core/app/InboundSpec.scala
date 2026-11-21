package yochat.core.app

import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.SessionId

class InboundSpec extends FunSuite:

  test("Q2/Q4: inbound frames are admitted, buffered across a gap, flushed, and deduplicated") {
    val peers     = AppTestSupport.connected
    val frames    = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 3)._2
    val sessionId = peers.bobState.sessions.keys.head
    val first     = Application.step(peers.bobState, AppEvent.InboundFrame(101L, sessionId, frames(0), None, 0L))
    val third     = Application.step(first._1, AppEvent.InboundFrame(103L, sessionId, frames(2), None, 0L))
    val second    = Application.step(third._1, AppEvent.InboundFrame(102L, sessionId, frames(1), None, 0L))
    val duplicate = Application.step(second._1, AppEvent.InboundFrame(102L, sessionId, frames(1), None, 0L))

    assert(first._2.exists { case AppEffect.PersistInbound(message) => message.seq == 1L; case _ => false })
    assert(third._2.exists { case AppEffect.Notify(Notification.Waiting) => true; case _ => false })
    assert(second._2.exists { case AppEffect.PersistInbound(message) => message.seq == 2L; case _ => false })
    assertEquals(second._1.conversations.values.head.applied.map(_.seq), Vector(1L, 2L, 3L))
    assert(!duplicate._2.exists { case AppEffect.PersistInbound(_) => true; case _ => false })
    assertEquals(duplicate._1.conversations.values.head.applied.size, 3)
  }

  test("Q4: unknown sessions and locked state are silent, with no application response") {
    val peers               = AppTestSupport.connected
    val frames              = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 1)._2
    val unknown             = SessionId.from(ByteVector.fill(32)(0x77.toByte)).fold(error => fail(error.toString), identity)
    val sessionId           = peers.bobState.sessions.keys.head
    val (_, unknownEffects) = Application.step(peers.bobState, AppEvent.InboundFrame(1L, unknown, frames.head, None, 0L))
    val (_, lateEffects)    =
      Application.step(peers.bobState.copy(lock = LockState.Locked(1L)), AppEvent.InboundFrame(1L, sessionId, frames.head, None, 0L))
    assertEquals(unknownEffects, Vector.empty)
    assertEquals(lateEffects, Vector.empty)
  }

  test("Q4: a failed decrypt persists the failure counter and reports rekey at the threshold") {
    val peers                         = AppTestSupport.connected
    val frames                        = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 1)._2
    val sessionId                     = peers.bobState.sessions.keys.head
    val original                      = frames.head
    val badCiphertext                 = original.ciphertext.update(0, (original.ciphertext(0) ^ 0x01).toByte)
    val signature                     = yochat.core.Crypto
      .sign(peers.alice.device.privateKey, yochat.core.Crypto.ascii("yochat-s1-msg") ++ original.header.canonical ++ badCiphertext)
      .fold(error => fail(error.toString), identity)
    val tampered                      = original.copy(ciphertext = badCiphertext, signature = signature)
    val (next, effects)               = Application.step(peers.bobState, AppEvent.InboundFrame(1L, sessionId, tampered, None, 0L))
    assert(effects.exists { case AppEffect.PersistSession(_) => true; case _ => false })
    assertEquals(next.sessions(sessionId).state.rekeyFailures, 1)
    val (threshold, thresholdEffects) = Application.step(
      next.copy(sessions =
        next.sessions.updated(sessionId, next.sessions(sessionId).copy(state = next.sessions(sessionId).state.copy(rekeyFailures = 2)))
      ),
      AppEvent.InboundFrame(2L, sessionId, tampered, None, 0L)
    )
    assertEquals(threshold.sessions(sessionId).state.rekeyFailures, 3)
    assert(thresholdEffects.exists { case AppEffect.Notify(Notification.RekeyRequired) => true; case _ => false })
  }
