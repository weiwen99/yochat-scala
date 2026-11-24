package yochat.core.app

import munit.FunSuite

import yochat.core.{ControlExtension, Crypto, RekeyAccept, RekeyRequest, SessionPhase, TestKeys}

class InternalReviewSpec extends FunSuite:

  private final case class Cutover(
    aliceStarted: AppState,
    aliceAccepted: AppState,
    aliceAcked: AppState,
    bobStarted: AppState,
    bobConfirmed: AppState,
    accept: RekeyAccept,
    ack: yochat.core.WireFrame,
    control: ControlExtension,
    oldSessionId: yochat.core.SessionId,
    newSessionId: yochat.core.SessionId
  )

  private def startWithOutgoing(state: AppState): AppState =
    val conversation = state.sessions.values.head.conversationId
    val (persisting, _) = Application.step(
      state,
      AppEvent.UserSend(1L, conversation, Crypto.text("old-pending"), TestKeys.nonce(80), 0L)
    )
    val generation = persisting.outgoing(1L).generation
    val (queued, _) = Application.step(persisting, AppEvent.PersistOutboundSucceeded(1L, generation, 1L))
    queued

  private def completeCutover(alice0: AppState, bob0: AppState): Cutover =
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
      bob0,
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
    Cutover(aliceStarted, aliceAccepted, aliceAcked, bobStarted, bobConfirmed, accept, ack, expected, oldSessionId, newSessionId)

  test("B2: an Active new session is not deleted by an old rekey timer") {
    val peers = AppTestSupport.connected
    val alice0 = startWithOutgoing(peers.aliceState)
    val cutover = completeCutover(alice0, peers.bobState)
    assertEquals(cutover.aliceAcked.sessions(cutover.newSessionId).state.phase, SessionPhase.Active)
    assert(cutover.aliceAcked.outgoing.values.exists(_.sessionId == cutover.oldSessionId))

    val (offline, _) = Application.step(cutover.aliceAcked, AppEvent.ConnectionChanged(online = false, nowMs = 10L))
    val (online, timerEffects) = Application.step(offline, AppEvent.ConnectionChanged(online = true, nowMs = 11L))
    assert(!timerEffects.exists {
      case AppEffect.StartTimer(timer, _, _) => timer == TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Initiator)
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
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    assert(!cutover.aliceAccepted.timers.contains(TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Initiator)))
    assert(cutover.aliceAccepted.timers.contains(TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)))
    val (disconnected, _) = Application.step(cutover.aliceAccepted, AppEvent.ConnectionChanged(online = false, nowMs = 90L))
    assert(disconnected.timers.contains(TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)))
    val (recovered, effects) = Application.step(
      cutover.aliceAccepted.copy(timers = Map.empty),
      AppEvent.RecoverRekey(cutover.oldSessionId, TestKeys.nonce(90), 100L)
    )
    assert(effects.exists {
      case AppEffect.SendRekeyConfirm(sessionId, _, _) => sessionId == cutover.newSessionId
      case _                                             => false
    })
    assert(effects.exists {
      case AppEffect.StartTimer(timer, _, _) => timer == TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)
      case _                              => false
    })
    assertEquals(recovered.sessions(cutover.newSessionId).state.phase, SessionPhase.Pending)
    val (_, duplicateRecoveryEffects) = Application.step(
      recovered,
      AppEvent.RecoverRekey(cutover.oldSessionId, TestKeys.nonce(98), 150L)
    )
    assert(!duplicateRecoveryEffects.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
    val (retried, retryEffects) = Application.step(
      recovered,
      AppEvent.RekeyConfirmRetry(cutover.oldSessionId, TestKeys.nonce(96), 200L)
    )
    assert(retryEffects.exists {
      case AppEffect.SendRekeyConfirm(sessionId, _, _) => sessionId == cutover.newSessionId
      case _                                             => false
    })
    assert(retryEffects.exists {
      case AppEffect.StartTimer(timer, _, _) => timer == TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)
      case _                              => false
    })
    assertEquals(retried.sessions(cutover.newSessionId).state.phase, SessionPhase.Pending)
    val offline = cutover.aliceAccepted.copy(connection = ConnectionState.Offline)
    val (_, offlineEffects) = Application.step(
      offline,
      AppEvent.RecoverRekey(cutover.oldSessionId, TestKeys.nonce(97), 300L)
    )
    assert(!offlineEffects.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
  }

  test("B1-write: a locked kernel rejects safety and invitation mutations") {
    val peers = AppTestSupport.connected
    val locked = peers.aliceState.copy(
      lock = LockState.Locked(1L),
      idleDeadline = None,
      contacts = peers.aliceState.contacts.map { case (id, contact) => id -> contact.copy(safetyVerified = false) },
      sessions = peers.aliceState.sessions.map { case (id, session) => id -> session.copy(state = session.state.copy(safetyVerified = false)) }
    )
    val (afterSafety, safetyEffects) = Application.step(
      locked,
      AppEvent.SafetyVerified(peers.bob.user.publicKey, 1L)
    )
    assert(!afterSafety.contacts.values.head.safetyVerified)
    assertEquals(safetyEffects, Vector.empty)
    val (afterInvite, inviteEffects) = Application.step(
      locked,
      AppEvent.InvitationCreated(peers.invitation, peers.aliceEphemeral.privateKey, peers.aliceEphemeral.privateKey, None, 1L)
    )
    assertEquals(afterInvite.invitations, locked.invitations)
    assertEquals(inviteEffects, Vector.empty)
  }

  test("R3-N1: rebinding the last old message removes the old session") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(startWithOutgoing(peers.aliceState), peers.bobState)
    val (after, effects) = Application.step(
      cutover.aliceAcked,
      AppEvent.RebindOutgoing(1L, cutover.newSessionId, TestKeys.nonce(99))
    )
    assert(!after.sessions.contains(cutover.oldSessionId))
    assert(effects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, retired, _) => retired.exists(_._1 == cutover.oldSessionId)
      case _                                                       => false
    })
    val (_, lateFrames) = AppTestSupport.sendFrames(peers.bob, peers.bobState.sessions.values.head.state, 1, "@bob", "@alice")
    val (_, lateEffects) = Application.step(
      after,
      AppEvent.InboundFrame(102L, cutover.oldSessionId, lateFrames.head, Some(AppTestSupport.xpair(103)), TestKeys.nonce(102), 80L)
    )
    assertEquals(lateEffects, Vector.empty)
  }

  test("R3-N2: responder does not retire old before first new-session Data") {
    val peers = AppTestSupport.connected
    val bob0 = startWithOutgoing(peers.bobState)
    val cutover = completeCutover(peers.aliceState, bob0)
    val (after, effects) = Application.step(
      cutover.bobConfirmed,
      AppEvent.RebindOutgoing(1L, cutover.newSessionId, TestKeys.nonce(101))
    )
    assert(after.sessions.contains(cutover.oldSessionId))
    assert(!effects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, retired, _) => retired.exists(_._1 == cutover.oldSessionId)
      case _                                                       => false
    })
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
    val alice0 = startWithOutgoing(peers.aliceState)
    val failed = alice0.copy(outgoing = alice0.outgoing.map { case (id, value) => id -> value.copy(state = OutgoingState.Failed, attempts = 3) })
    val cutover = completeCutover(failed, peers.bobState)
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
    val freshRequest = RekeyRequest
      .create(
        oldSessionId,
        request.epoch + 1L,
        TestKeys.rekeyId(32),
        AppTestSupport.xpair(35).publicKey,
        peers.alice.device.publicKey,
        peers.alice.device.privateKey,
        TestKeys.nonce(33),
        peers.aliceState.sessions(oldSessionId).state.rekeyBase
      )
      .fold(error => fail(error.toString), identity)
    val (after, effects) = Application.step(
      aborted,
      AppEvent.RekeyRequestReceived(oldSessionId, freshRequest, AppTestSupport.xpair(33), AppTestSupport.xpair(34), TestKeys.nonce(32), 5_002L)
    )
    assertEquals(after.sessions(oldSessionId).state.phase, SessionPhase.Active)
    assert(effects.contains(AppEffect.Notify(Notification.RekeyFailed)))
    assert(!after.sessions.values.exists(_.state.phase == SessionPhase.ReadOnly))
  }

  test("B3: a late Accept aborts without arming a Confirm retry") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val expired = cutover.aliceStarted.copy(
      sessions = cutover.aliceStarted.sessions.updated(
        cutover.oldSessionId,
        cutover.aliceStarted.sessions(cutover.oldSessionId).copy(
          state = cutover.aliceStarted.sessions(cutover.oldSessionId).state.copy(
            rekey = cutover.aliceStarted.sessions(cutover.oldSessionId).state.rekey.map(_.copy(deadlineMs = 1L))
          )
        )
      )
    )
    val (after, effects) = Application.step(
      expired,
      AppEvent.RekeyAcceptReceived(cutover.oldSessionId, cutover.accept, TestKeys.nonce(120), 2L)
    )
    assert(after.sessions(cutover.oldSessionId).state.rekey.isEmpty)
    assert(!after.sessions.contains(cutover.newSessionId))
    assert(!effects.exists { case AppEffect.StartTimer(_, _, _) => true; case _ => false })
    assert(effects.exists { case AppEffect.Notify(Notification.RekeyFailed) => true; case _ => false })
  }

  test("B3: Confirm retry is capped by the original deadline") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val deadline = cutover.aliceAccepted.sessions(cutover.oldSessionId).state.rekey.flatMap(_.deadlineMs match
      case value if value > 0L => Some(value)
      case _                   => None
    ).getOrElse(fail("missing persisted deadline"))
    val (retried, effects) = Application.step(
      cutover.aliceAccepted,
      AppEvent.RekeyConfirmRetry(cutover.oldSessionId, TestKeys.nonce(121), 4_900L)
    )
    assertEquals(retried.timers(TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)), deadline)
    assert(effects.exists { case AppEffect.StartTimer(_, value, _) => value == deadline; case _ => false })
  }

  test("R11: skipped cleanup can retire the last ReadOnly session") {
    val peers = AppTestSupport.connected
    val alice0 = startWithOutgoing(peers.aliceState)
    val cutover = completeCutover(alice0, peers.bobState)
    val withoutOutgoing = cutover.aliceAcked.copy(outgoing = Map.empty)
    val oldId           = cutover.oldSessionId
    val (next, effects) = Application.step(withoutOutgoing, AppEvent.RecoverRetirements)
    assert(!next.sessions.contains(cutover.oldSessionId))
    assert(next.sessions.contains(cutover.newSessionId))
    assert(effects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, retire, _) => retire.exists(_._1 == cutover.oldSessionId)
      case _                                                     => false
    })
    assert(effects.exists {
      case AppEffect.CancelTimer(TimerId.Rekey(`oldId`, _)) => true
      case _                                                               => false
    })
  }

  test("R11: rebind invalidates the old Retry installation") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(startWithOutgoing(peers.aliceState), peers.bobState)
    val old = cutover.aliceAcked.outgoing(1L)
    val (failed, failedEffects) = Application.step(
      cutover.aliceAcked,
      AppEvent.SendFailed(1L, old.generation, 10L)
    )
    val retry = TimerId(TimerKind.Retry, 1L)
    val installation = failedEffects.collectFirst {
      case AppEffect.StartTimer(`retry`, _, value) => value
    }.getOrElse(fail("missing retry installation"))
    val (rebound, rebindEffects) = Application.step(
      failed,
      AppEvent.RebindOutgoing(1L, cutover.newSessionId, TestKeys.nonce(122))
    )
    assert(!rebound.timers.contains(retry))
    assert(rebindEffects.exists { case AppEffect.CancelTimer(`retry`) => true; case _ => false })
    assert(!rebindEffects.exists { case AppEffect.SendWire(_, 1L, _, _) => true; case _ => false })
    val (stale, staleEffects) = Application.step(
      rebound,
      AppEvent.TimerFiredAt(retry, old.generation, 40L, installation)
    )
    assertEquals(stale, rebound)
    assert(staleEffects.isEmpty)
  }

  test("R11: disconnect/reconnect preserves the Confirm retry cadence") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val confirm = TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)
    val original = cutover.aliceAccepted.timers(confirm)
    val (offline, _) = Application.step(cutover.aliceAccepted, AppEvent.ConnectionChanged(online = false, nowMs = 10L))
    assert(offline.timers.contains(confirm))
    val (online, onlineEffects) = Application.step(offline, AppEvent.ConnectionChanged(online = true, nowMs = 20L))
    assertEquals(online.timers.get(confirm), Some(original))
    assert(!onlineEffects.exists { case AppEffect.StartTimer(`confirm`, _, _) => true; case _ => false })
    assert(!onlineEffects.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
    val (offline2, _) = Application.step(online, AppEvent.ConnectionChanged(online = false, nowMs = 30L))
    val (online2, effects2) = Application.step(offline2, AppEvent.ConnectionChanged(online = true, nowMs = 40L))
    assertEquals(online2.timers.get(confirm), Some(original))
    assert(!effects2.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
  }

  test("R11: responder cleanup requires first new-session Data") {
    val peers = AppTestSupport.connected
    val bob0 = startWithOutgoing(peers.bobState)
    val cutover = completeCutover(peers.aliceState, bob0)
    val oldId = cutover.oldSessionId
    val noOutgoing = cutover.bobConfirmed.copy(outgoing = Map.empty)
    val (waiting, waitingEffects) = Application.step(noOutgoing, AppEvent.RecoverRetirements)
    assert(waiting.sessions.contains(oldId))
    assert(!waitingEffects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, retire, _) => retire.exists(_._1 == oldId)
      case _                                                     => false
    })
    val successor = waiting.sessions(cutover.newSessionId).copy(retireOldOnFirstData = false)
    val ready = waiting.copy(sessions = waiting.sessions.updated(cutover.newSessionId, successor))
    val (retired, effects) = Application.step(ready, AppEvent.RecoverRetirements)
    assert(!retired.sessions.contains(oldId))
    assert(effects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, retire, _) => retire.exists(_._1 == oldId)
      case _                                                     => false
    })
  }

  test("R11: expired Ack aborts instead of activating the pending successor") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val old = cutover.aliceAccepted.sessions(cutover.oldSessionId)
    val expired = cutover.aliceAccepted.copy(
      sessions = cutover.aliceAccepted.sessions.updated(
        cutover.oldSessionId,
        old.copy(state = old.state.copy(rekey = old.state.rekey.map(_.copy(deadlineMs = 1L))))
      )
    )
    val (after, effects) = Application.step(
      expired,
      AppEvent.RekeyAckReceived(cutover.newSessionId, cutover.ack, cutover.control, AppTestSupport.xpair(9), 2L)
    )
    assert(after.sessions(cutover.oldSessionId).state.rekey.isEmpty)
    assert(!after.sessions.contains(cutover.newSessionId))
    assert(!after.timers.contains(TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)))
    assert(!after.timerInstallations.contains(TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)))
    assert(effects.exists { case AppEffect.Notify(Notification.RekeyFailed) => true; case _ => false })
  }

  test("R11: a legacy zero deadline aborts instead of extending forever") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val old = cutover.aliceAccepted.sessions(cutover.oldSessionId)
    val legacy = cutover.aliceAccepted.copy(
      timers = Map.empty,
      sessions = cutover.aliceAccepted.sessions.updated(
        cutover.oldSessionId,
        old.copy(state = old.state.copy(rekey = old.state.rekey.map(_.copy(deadlineMs = 0L))))
      )
    )
    val (after, effects) = Application.step(
      legacy,
      AppEvent.RecoverRekey(cutover.oldSessionId, TestKeys.nonce(123), 1L)
    )
    assert(after.sessions(cutover.oldSessionId).state.rekey.isEmpty)
    assert(!after.sessions.contains(cutover.newSessionId))
    assert(!effects.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
  }

  test("R11: a previously valid Confirm installation cannot act after rearm") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val confirm = TimerId.Rekey(cutover.oldSessionId, RekeyTimerStage.Confirm)
    val oldInstallation = cutover.aliceAccepted.timerInstallations(confirm)
    val (retried, retryEffects) = Application.step(
      cutover.aliceAccepted,
      AppEvent.RekeyConfirmRetry(cutover.oldSessionId, TestKeys.nonce(124), 100L)
    )
    assert(retryEffects.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
    assert(retried.timerInstallations.get(confirm).exists(_ != oldInstallation))
    val (stale, staleEffects) = Application.step(
      retried,
      AppEvent.TimerFiredAt(confirm, 0L, 2_600L, oldInstallation)
    )
    assertEquals(stale, retried)
    assert(staleEffects.isEmpty)
  }

  test("R11: Confirm retry at the deadline aborts without sending") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val deadline = cutover.aliceAccepted.sessions(cutover.oldSessionId).state.rekey.map(_.deadlineMs).getOrElse(fail("missing deadline"))
    val (after, effects) = Application.step(
      cutover.aliceAccepted,
      AppEvent.RekeyConfirmRetry(cutover.oldSessionId, TestKeys.nonce(125), deadline)
    )
    assert(after.sessions(cutover.oldSessionId).state.rekey.isEmpty)
    assert(!effects.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
    assert(effects.exists { case AppEffect.Notify(Notification.RekeyFailed) => true; case _ => false })
  }

  test("R11: rekey expires locally while disconnected") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val (offline, _) = Application.step(cutover.aliceAccepted, AppEvent.ConnectionChanged(online = false, nowMs = 10L))
    val (aborted, effects) = Application.step(
      offline,
      AppEvent.RekeyConfirmRetry(cutover.oldSessionId, TestKeys.nonce(126), 5_000L)
    )
    assert(aborted.sessions(cutover.oldSessionId).state.rekey.isEmpty)
    assert(aborted.sessions(cutover.oldSessionId).state.phase == SessionPhase.Active)
    assert(!effects.exists { case AppEffect.SendRekeyConfirm(_, _, _) => true; case _ => false })
    assert(effects.exists { case AppEffect.Notify(Notification.RekeyFailed) => true; case _ => false })
  }

  test("R11: a previously valid Delivery installation cannot fire after rebind") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(startWithOutgoing(peers.aliceState), peers.bobState)
    val delivery = TimerId(TimerKind.Delivery, 1L)
    val oldInstallation = cutover.aliceAcked.timerInstallations(delivery)
    val (rebound, rebindEffects) = Application.step(
      cutover.aliceAcked,
      AppEvent.RebindOutgoing(1L, cutover.newSessionId, TestKeys.nonce(127))
    )
    assert(!rebound.timers.contains(delivery))
    assert(rebindEffects.exists { case AppEffect.CancelTimer(`delivery`) => true; case _ => false })
    val (stale, staleEffects) = Application.step(
      rebound,
      AppEvent.TimerFiredAt(delivery, rebound.outgoing(1L).generation, 5_000L, oldInstallation)
    )
    assertEquals(stale, rebound)
    assert(staleEffects.isEmpty)

    val (pumped, pumpEffects) = Application.step(rebound, AppEvent.Pump(50L))
    val newInstallation = pumpEffects.collectFirst {
      case AppEffect.StartTimer(`delivery`, _, value) => value
    }.getOrElse(fail("missing replacement delivery installation"))
    val (retried, retryEffects) = Application.step(
      pumped,
      AppEvent.TimerFiredAt(delivery, pumped.outgoing(1L).generation, 5_050L, newInstallation)
    )
    assert(retryEffects.exists { case AppEffect.SendWire(_, 1L, _, _) => true; case _ => false })
    assertEquals(retried.outgoing(1L).sessionId, cutover.newSessionId)
  }

  test("R11: a rejected last rebind retires the predecessor") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(startWithOutgoing(peers.aliceState), peers.bobState)
    val contact = cutover.aliceAcked.contacts.values.head.copy(confirmation = ContactConfirmation.AccountMismatch)
    val rejected = cutover.aliceAcked.copy(contacts = cutover.aliceAcked.contacts.updated(contact.userPub, contact))
    val (after, effects) = Application.step(
      rejected,
      AppEvent.RebindOutgoing(1L, cutover.newSessionId, TestKeys.nonce(128))
    )
    assert(!after.sessions.contains(cutover.oldSessionId))
    assert(effects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, retire, _) => retire.exists(_._1 == cutover.oldSessionId)
      case _                                                     => false
    })
    assert(effects.exists { case AppEffect.Notify(Notification.MessageFailed) => true; case _ => false })
  }

  test("R11: an active successor without a local Ack does not retire the initiator") {
    val peers = AppTestSupport.connected
    val cutover = completeCutover(peers.aliceState, peers.bobState)
    val newActive = cutover.aliceAccepted.sessions(cutover.newSessionId).copy(state =
      cutover.aliceAccepted.sessions(cutover.newSessionId).state.copy(phase = SessionPhase.Active)
    )
    val state = cutover.aliceAccepted
      .copy(outgoing = Map.empty, sessions = cutover.aliceAccepted.sessions.updated(cutover.newSessionId, newActive))
    val (after, effects) = Application.step(state, AppEvent.RecoverRetirements)
    assert(after.sessions.contains(cutover.oldSessionId))
    assert(!effects.exists {
      case AppEffect.PersistHandshake(_, _, _, _, _, retire, _) => retire.exists(_._1 == cutover.oldSessionId)
      case _                                                     => false
    })
  }
