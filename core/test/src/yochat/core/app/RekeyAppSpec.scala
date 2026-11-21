package yochat.core.app

import munit.FunSuite

import yochat.core.{ControlExtension, Ratchet, TestKeys}

class RekeyAppSpec extends FunSuite:

  test("Q5: request/accept/confirm/ack activates the new session and retires old sessions at cutover") {
    val peers                        = AppTestSupport.connected
    val oldSessionId                 = peers.aliceState.sessions.keys.head
    val r1                           = AppTestSupport.xpair(6)
    val r2                           = AppTestSupport.xpair(7)
    val r2Next                       = AppTestSupport.xpair(8)
    val rekeyId                      = TestKeys.rekeyId(1)
    val (aliceStarted, startEffects) = Application.step(
      peers.aliceState,
      AppEvent.StartRekey(oldSessionId, r1, rekeyId, TestKeys.nonce(10), 0L)
    )
    val request                      = startEffects.collectFirst { case AppEffect.SendRekeyRequest(_, value) => value }.getOrElse(fail("missing request"))
    assert(startEffects.exists { case AppEffect.PersistSession(_) => true; case _ => false })

    val (bobStarted, bobStartEffects) = Application.step(
      peers.bobState,
      AppEvent.RekeyRequestReceived(oldSessionId, request, r2, r2Next, TestKeys.nonce(11))
    )
    val accept                        = bobStartEffects.collectFirst { case AppEffect.SendRekeyAccept(_, value) => value }.getOrElse(fail("missing accept"))
    assert(bobStarted.sessions.values.exists(_.state.phase == yochat.core.SessionPhase.Pending))

    val (aliceAccepted, aliceAcceptEffects) = Application.step(
      aliceStarted,
      AppEvent.RekeyAcceptReceived(oldSessionId, accept, TestKeys.nonce(12))
    )
    val confirm                             = aliceAcceptEffects.collectFirst { case AppEffect.SendRekeyConfirm(_, value) => value }.getOrElse(fail("missing confirm"))
    val newSessionId                        = aliceAccepted.sessions.keys.find(_ != oldSessionId).getOrElse(fail("missing new session"))
    assertEquals(aliceAccepted.sessions(oldSessionId).state.phase, yochat.core.SessionPhase.ReadOnly)

    val expected                          = ControlExtension(request.epoch, request.id)
    val (bobConfirmed, bobConfirmEffects) = Application.step(
      bobStarted,
      AppEvent.RekeyConfirmReceived(newSessionId, confirm, expected, TestKeys.nonce(13), None)
    )
    val ack                               = bobConfirmEffects.collectFirst { case AppEffect.SendRekeyConfirmAck(_, value) => value }.getOrElse(fail("missing ack"))
    assertEquals(bobConfirmed.sessions(newSessionId).state.phase, yochat.core.SessionPhase.Active)

    val (aliceAcked, ackEffects) = Application.step(
      aliceAccepted,
      AppEvent.RekeyAckReceived(newSessionId, ack, expected, AppTestSupport.xpair(9))
    )
    assertEquals(aliceAcked.sessions(newSessionId).state.phase, yochat.core.SessionPhase.Active)
    assert(!aliceAcked.sessions.contains(oldSessionId))
    assert(ackEffects.exists { case AppEffect.DeleteSession(`oldSessionId`, _) => true; case _ => false })

    val aliceNew                    = aliceAcked.sessions(newSessionId)
    val (_, frame)                  = Ratchet
      .encryptUser(
        aliceNew.state,
        peers.alice.device.publicKey,
        peers.alice.device.privateKey,
        1L,
        TestKeys.nonce(14),
        yochat.core.Crypto.text("new")
      )
      .fold(error => fail(error.toString), identity)
    val direct                      = Ratchet.decrypt(bobConfirmed.sessions(newSessionId).state, frame, false, Some(AppTestSupport.xpair(10)))
    assert(direct.isRight, direct.toString)
    val (bobAfterData, dataEffects) = Application.step(
      bobConfirmed,
      AppEvent.InboundFrame(1L, newSessionId, frame, Some(AppTestSupport.xpair(10)), 0L)
    )
    assertEquals(bobConfirmed.sessions(newSessionId).retireOldOnFirstData, true)
    assertEquals(bobConfirmed.sessions(oldSessionId).state.phase, yochat.core.SessionPhase.ReadOnly)
    assert(dataEffects.exists { case AppEffect.DeleteSession(`oldSessionId`, _) => true; case _ => false }, dataEffects.toString)
    assert(!bobAfterData.sessions.contains(oldSessionId))
  }
