package yochat.core.app

import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.{SessionId, TestKeys}

class InboundSpec extends FunSuite:

  test("Q2/Q4: inbound frames are admitted, buffered across a gap, flushed, and deduplicated") {
    val peers     = AppTestSupport.connected
    val frames    = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 3)._2
    val sessionId = peers.bobState.sessions.keys.head
    val first     = Application.step(peers.bobState, AppEvent.InboundFrame(101L, sessionId, frames(0), None, TestKeys.nonce(1), 0L))
    val third     = Application.step(first._1, AppEvent.InboundFrame(103L, sessionId, frames(2), None, TestKeys.nonce(2), 0L))
    val second    = Application.step(third._1, AppEvent.InboundFrame(102L, sessionId, frames(1), None, TestKeys.nonce(3), 0L))
    val duplicate = Application.step(second._1, AppEvent.InboundFrame(102L, sessionId, frames(1), None, TestKeys.nonce(4), 0L))

    assert(first._2.exists { case AppEffect.PersistInbound(message, _) => message.seq == 1L; case _ => false })
    assert(first._2.exists { case AppEffect.SendAck(_, _, _) => true; case _ => false })
    assert(third._2.exists { case AppEffect.Notify(Notification.Waiting) => true; case _ => false })
    assert(third._2.exists { case AppEffect.SendGap(_, _, _) => true; case _ => false })
    assert(second._2.exists { case AppEffect.PersistInbound(message, _) => message.seq == 2L; case _ => false })
    assertEquals(second._1.conversations.values.head.nextSeq.getOrElse(peers.alice.user.publicKey, 1L), 4L)
    assert(!duplicate._2.exists { case AppEffect.PersistInbound(_, _) => true; case _ => false })
    assert(duplicate._2.exists { case AppEffect.SendAck(_, _, _) => true; case _ => false })
    assertEquals(duplicate._1.conversations.values.head.nextSeq.getOrElse(peers.alice.user.publicKey, 1L), 4L)
  }

  test("Q4: unknown sessions and locked state are silent, with no application response") {
    val peers               = AppTestSupport.connected
    val frames              = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 1)._2
    val unknown             = SessionId.from(ByteVector.fill(32)(0x77.toByte)).fold(error => fail(error.toString), identity)
    val sessionId           = peers.bobState.sessions.keys.head
    val (_, unknownEffects) = Application.step(peers.bobState, AppEvent.InboundFrame(1L, unknown, frames.head, None, TestKeys.nonce(1), 0L))
    val (_, lateEffects)    =
      Application.step(
        peers.bobState.copy(lock = LockState.Locked(1L)),
        AppEvent.InboundFrame(1L, sessionId, frames.head, None, TestKeys.nonce(2), 0L)
      )
    assertEquals(unknownEffects, Vector.empty)
    assertEquals(lateEffects, Vector.empty)
  }

  test("Q4: a failed decrypt persists the failure counter and starts automatic rekey at the threshold") {
    val peers                         = AppTestSupport.connected
    val frames                        = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 1)._2
    val sessionId                     = peers.bobState.sessions.keys.head
    val original                      = frames.head
    val badCiphertext                 = original.ciphertext.update(0, (original.ciphertext(0) ^ 0x01).toByte)
    val signature                     = yochat.core.Crypto
      .sign(peers.alice.device.privateKey, yochat.core.Crypto.ascii("yochat-s1-msg") ++ original.header.canonical ++ badCiphertext)
      .fold(error => fail(error.toString), identity)
    val tampered                      = original.copy(ciphertext = badCiphertext, signature = signature)
    val (next, effects)               = Application.step(peers.bobState, AppEvent.InboundFrame(1L, sessionId, tampered, None, TestKeys.nonce(1), 0L))
    assert(effects.exists { case AppEffect.PersistSession(_) => true; case _ => false })
    assertEquals(next.sessions(sessionId).state.rekeyFailures, 1)
    val (threshold, thresholdEffects) = Application.step(
      next.copy(sessions =
        next.sessions.updated(sessionId, next.sessions(sessionId).copy(state = next.sessions(sessionId).state.copy(rekeyFailures = 2)))
      ),
      AppEvent.InboundFrame(2L, sessionId, tampered, None, TestKeys.nonce(2), 0L)
    )
    assertEquals(threshold.sessions(sessionId).state.rekeyFailures, 3)
    assert(thresholdEffects.exists { case AppEffect.StartRekey(`sessionId`) => true; case _ => false })
  }

  test("Q4: a flushed buffered message is reported as one late arrival keyed by its id") {
    val peers     = AppTestSupport.connected
    val frames    = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 3)._2
    val sessionId = peers.bobState.sessions.keys.head
    val first     = Application.step(peers.bobState, AppEvent.InboundFrame(101L, sessionId, frames(0), None, TestKeys.nonce(1), 0L))
    val third     = Application.step(first._1, AppEvent.InboundFrame(103L, sessionId, frames(2), None, TestKeys.nonce(2), 0L))
    val second    = Application.step(third._1, AppEvent.InboundFrame(102L, sessionId, frames(1), None, TestKeys.nonce(3), 0L))

    assertEquals(third._1.lateArrival, None)
    assertEquals(second._1.lateArrival.map(_.messageId), Some(103L))
    assertEquals(second._1.lateArrival.map(_.conversationId), Some(peers.bobState.conversations.keys.head))
  }

  test("Q4: a gap beyond the skip budget notifies and asks the runtime to rebuild the channel") {
    val peers     = AppTestSupport.connected
    val frames    = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 66)._2
    val sessionId = peers.bobState.sessions.keys.head
    val (next, effects) = Application.step(peers.bobState, AppEvent.InboundFrame(900L, sessionId, frames(65), None, TestKeys.nonce(1), 0L))

    assert(effects.contains(AppEffect.StartRekey(sessionId)))
    assert(effects.contains(AppEffect.Notify(Notification.ChannelRebuild)))
    assertEquals(next.conversations.values.head.nextSeq.get(peers.alice.user.publicKey), None)
  }
