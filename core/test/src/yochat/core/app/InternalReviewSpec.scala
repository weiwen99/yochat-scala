package yochat.core.app

import munit.FunSuite

import yochat.core.{ControlExtension, Crypto, SessionPhase, TestKeys}

class InternalReviewSpec extends FunSuite:

  private final case class Cutover(
    aliceStarted: AppState,
    aliceAccepted: AppState,
    aliceAcked: AppState,
    bobStarted: AppState,
    bobConfirmed: AppState,
    oldSessionId: yochat.core.SessionId,
    newSessionId: yochat.core.SessionId
  )

  private def startWithOutgoing(peers: AppTestSupport.Peers): AppState =
    val conversation = peers.aliceState.sessions.values.head.conversationId
    val (persisting, _) = Application.step(
      peers.aliceState,
      AppEvent.UserSend(1L, conversation, Crypto.text("old-pending"), TestKeys.nonce(80), 0L)
    )
    val generation = persisting.outgoing(1L).generation
    val (queued, _) = Application.step(persisting, AppEvent.PersistOutboundSucceeded(1L, generation, 1L))
    queued

  private def completeCutover(peers: AppTestSupport.Peers, alice0: AppState): Cutover =
    val oldSessionId = alice0.sessions.keys.head
    val r1 = AppTestSupport.xpair(6)
    val r2 = AppTestSupport.xpair(7)
    val r2Next = AppTestSupport.xpair(8)
    val (aliceStarted, startEffects) = Application.step(
      alice0,
      AppEvent.StartRekey(oldSessionId, r1, TestKeys.rekeyId(1), TestKeys.nonce(10), 0L)
    )
    val request = startEffects.collectFirst { case AppEffect.SendRekeyRequest(_, _, value) => value }.getOrElse(fail("missing request"))
    val (bobStarted, bobStartEffects) = Application.step(
      peers.bobState,
      AppEvent.RekeyRequestReceived(oldSessionId, request, r2, r2Next, TestKeys.nonce(11), 0L)
    )
    val accept = bobStartEffects.collectFirst { case AppEffect.SendRekeyAccept(_, _, value) => value }.getOrElse(fail("missing accept"))
    val (aliceAccepted, aliceAcceptEffects) = Application.step(
      aliceStarted,
      AppEvent.RekeyAcceptReceived(oldSessionId, accept, TestKeys.nonce(12), 0L)
    )
    val confirm = aliceAcceptEffects.collectFirst { case AppEffect.SendRekeyConfirm(_, _, value) => value }.getOrElse(fail("missing confirm"))
    val newSessionId = aliceAccepted.sessions.keys.find(_ != oldSessionId).getOrElse(fail("missing new session"))
    val expected = ControlExtension(request.epoch, request.id)
    val (bobConfirmed, bobConfirmEffects) = Application.step(
      bobStarted,
      AppEvent.RekeyConfirmReceived(newSessionId, confirm, expected, TestKeys.nonce(13), None)
    )
    val ack = bobConfirmEffects.collectFirst { case AppEffect.SendRekeyConfirmAck(_, _, value) => value }.getOrElse(fail("missing ack"))
    val (aliceAcked, _) = Application.step(
      aliceAccepted,
      AppEvent.RekeyAckReceived(newSessionId, ack, expected, AppTestSupport.xpair(9))
    )
    Cutover(aliceStarted, aliceAccepted, aliceAcked, bobStarted, bobConfirmed, oldSessionId, newSessionId)

  test("B2: an Active new session is not deleted by an old rekey timer") {
    val peers = AppTestSupport.connected
    val alice0 = startWithOutgoing(peers)
    val cutover = completeCutover(peers, alice0)
    assertEquals(cutover.aliceAcked.sessions(cutover.newSessionId).state.phase, SessionPhase.Active)
    assert(cutover.aliceAcked.outgoing.values.exists(_.sessionId == cutover.oldSessionId))

    val (offline, _) = Application.step(cutover.aliceAcked, AppEvent.ConnectionChanged(online = false, nowMs = 10L))
    val (online, timerEffects) = Application.step(offline, AppEvent.ConnectionChanged(online = true, nowMs = 11L))
    assert(!timerEffects.exists {
      case AppEffect.StartTimer(timer, _) => timer == TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Initiator)
      case _                              => false
    })

    val (after, effects) = Application.step(
      online,
      AppEvent.TimerFired(TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Initiator), 0L, 20L)
    )
    assert(after.sessions.contains(cutover.newSessionId))
    assertEquals(after.sessions(cutover.oldSessionId).state.phase, SessionPhase.ReadOnly)
    assert(!effects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, _, deletePending) => deletePending.contains(cutover.newSessionId)
      case _                                                            => false
    })
  }

  test("B3: a persisted initiator Accepted state resends a fresh Confirm after restart") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers, peers.aliceState)
    val (recovered, effects) = Application.step(
      cutover.aliceAccepted,
      AppEvent.RecoverRekey(cutover.oldSessionId, TestKeys.nonce(90), 100L)
    )
    assert(effects.exists {
      case AppEffect.SendRekeyConfirm(sessionId, _, _) => sessionId == cutover.newSessionId
      case _                                             => false
    })
    assert(effects.exists {
      case AppEffect.StartTimer(timer, _) => timer == TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Initiator)
      case _                              => false
    })
    assertEquals(recovered.sessions(cutover.newSessionId).state.phase, SessionPhase.Pending)
  }

  test("M1: an ACK cannot deliver a never-sent queued message") {
    val peers = AppTestSupport.connected
    val aliceSession = peers.aliceState.sessions.keys.head
    val bobSession = peers.bobState.sessions.keys.head
    val (_, frames) = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 1, "@alice", "@bob")
    val (_, bobEffects) = Application.step(
      peers.bobState,
      AppEvent.InboundFrame(1L, bobSession, frames.head, None, TestKeys.nonce(91), 1L)
    )
    val ack = bobEffects.collectFirst { case AppEffect.SendAck(_, _, value) => value }.getOrElse(fail("missing ack"))
    val queued = peers.aliceState.copy(
      outgoing = Map(
        1L -> OutgoingMessage(
          1L,
          peers.aliceState.sessions.values.head.conversationId,
          aliceSession,
          peers.alice.user.publicKey,
          peers.alice.device.publicKey,
          peers.aliceState.sessions.values.head.state.peerDevicePub,
          1L,
          Crypto.text("queued"),
          frames.head,
          0L,
          OutgoingState.Queued,
          0
        )
      )
    )
    val (after, effects) = Application.step(
      queued.copy(connection = ConnectionState.Offline),
      AppEvent.InboundFrame(2L, aliceSession, ack, Some(AppTestSupport.xpair(92)), TestKeys.nonce(93), 2L)
    )
    assertEquals(after.outgoing.get(1L).map(_.state), Some(OutgoingState.Queued))
    assert(!effects.exists {
      case AppEffect.UpdateMessageState(_, _, _, _, _, MessageTransition.Delivered) => true
      case _                                                                         => false
    })
  }

  test("M2: a ReadOnly old session ACKs late data instead of applying it silently") {
    val peers = AppTestSupport.connected
    val oldSessionId = peers.aliceState.sessions.keys.head
    val (_, frames) = AppTestSupport.sendFrames(peers.bob, peers.bobState.sessions.values.head.state, 1, "@bob", "@alice")
    val old = peers.aliceState.sessions(oldSessionId)
    val readOnly = peers.aliceState.copy(sessions = peers.aliceState.sessions.updated(oldSessionId, old.copy(state = old.state.copy(phase = SessionPhase.ReadOnly))))
    val (after, effects) = Application.step(
      readOnly,
      AppEvent.InboundFrame(3L, oldSessionId, frames.head, Some(AppTestSupport.xpair(95)), TestKeys.nonce(94), 3L)
    )
    assert(effects.exists { case AppEffect.SendAck(_, _, _) => true; case _ => false })
    assertEquals(after.sessions(oldSessionId).state.phase, SessionPhase.ReadOnly)
  }

  test("M3: a Failed old message does not block retirement") {
    val peers = AppTestSupport.connected
    val alice0 = startWithOutgoing(peers)
    val failed = alice0.copy(outgoing = alice0.outgoing.map { case (id, value) => id -> value.copy(state = OutgoingState.Failed, attempts = 3) })
    val cutover = completeCutover(peers, failed)
    assert(!cutover.aliceAcked.sessions.contains(cutover.oldSessionId))
    assertEquals(cutover.aliceAcked.outgoing.get(1L).map(_.state), Some(OutgoingState.Failed))
  }

  test("M4: responder cooldown rejects a new rekey request without changing phase") {
    val peers = AppTestSupport.connected
    val oldSessionId = peers.aliceState.sessions.keys.head
    val (_, startEffects) = Application.step(
      peers.aliceState,
      AppEvent.StartRekey(oldSessionId, AppTestSupport.xpair(30), TestKeys.rekeyId(30), TestKeys.nonce(30), 0L)
    )
    val request = startEffects.collectFirst { case AppEffect.SendRekeyRequest(_, _, value) => value }.getOrElse(fail("missing request"))
    val (bobStarted, _) = Application.step(
      peers.bobState,
      AppEvent.RekeyRequestReceived(oldSessionId, request, AppTestSupport.xpair(31), AppTestSupport.xpair(32), TestKeys.nonce(31), 0L)
    )
    val (aborted, _) = Application.step(
      bobStarted,
      AppEvent.TimerFired(TimerId.Rekey(oldSessionId, RekeyTimerStage.Responder), 0L, 5_001L)
    )
    val (after, effects) = Application.step(
      aborted,
      AppEvent.RekeyRequestReceived(oldSessionId, request, AppTestSupport.xpair(33), AppTestSupport.xpair(34), TestKeys.nonce(32), 5_002L)
    )
    assertEquals(after.sessions(oldSessionId).state.phase, SessionPhase.Active)
    assert(effects.contains(AppEffect.Notify(Notification.RekeyFailed)))
    assert(!after.sessions.values.exists(_.state.phase == SessionPhase.ReadOnly))
  }
