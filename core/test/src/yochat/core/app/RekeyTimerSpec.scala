package yochat.core.app

import munit.FunSuite

import yochat.core.TestKeys

class RekeyTimerSpec extends FunSuite:

  test("rekey timeout aborts the pending state, removes the candidate, and persists atomically") {
    val peers                 = AppTestSupport.connected
    val sessionId             = peers.aliceState.sessions.keys.head
    val (started, startEffects) = Application.step(
      peers.aliceState,
      AppEvent.StartRekey(sessionId, AppTestSupport.xpair(10), TestKeys.rekeyId(2), TestKeys.nonce(20), 0L)
    )
    assert(startEffects.exists {
      case AppEffect.StartTimer(TimerId.Rekey(`sessionId`, RekeyTimerStage.Initiator), deadline, _) =>
        deadline == AppLimits.RekeyTimeoutMs
      case _ => false
    })
    assertEquals(started.sessions(sessionId).state.rekey.isDefined, true)

    val deadline = AppLimits.RekeyTimeoutMs
    val (aborted, effects) = Application.step(
      started,
      AppEvent.TimerFired(TimerId.Rekey(sessionId, RekeyTimerStage.Initiator), 0L, deadline)
    )
    assertEquals(aborted.sessions(sessionId).state.rekey, None)
    assertEquals(aborted.sessions(sessionId).state.phase, yochat.core.SessionPhase.Active)
    assert(aborted.sessions.size == 1)
    assert(effects.exists { case AppEffect.Notify(Notification.RekeyFailed) => true; case _ => false })
    assert(effects.exists {
      case AppEffect.PersistHandshake(_, _, sessions, _, _, _, _) => sessions.exists(_.state.sessionId == sessionId)
      case _                                                      => false
    })
  }

  test("a stale timer installation cannot abort a rearmed timer") {
    val peers                   = AppTestSupport.connected
    val sessionId               = peers.aliceState.sessions.keys.head
    val timer                   = TimerId.Rekey(sessionId, RekeyTimerStage.Initiator)
    val (started, startEffects) = Application.step(
      peers.aliceState,
      AppEvent.StartRekey(sessionId, AppTestSupport.xpair(11), TestKeys.rekeyId(3), TestKeys.nonce(21), 0L)
    )
    val installation = startEffects.collectFirst {
      case AppEffect.StartTimer(`timer`, _, value) => value
    }.getOrElse(fail("missing timer installation"))
    assertEquals(started.timerInstallations.get(timer), Some(installation))

    val (stale, staleEffects) = Application.step(
      started,
      AppEvent.TimerFiredAt(timer, 0L, AppLimits.RekeyTimeoutMs, installation + 1L)
    )
    assertEquals(stale, started)
    assert(staleEffects.isEmpty)

    val (aborted, effects) = Application.step(
      stale,
      AppEvent.TimerFiredAt(timer, 0L, AppLimits.RekeyTimeoutMs, installation)
    )
    assertEquals(aborted.sessions(sessionId).state.rekey, None)
    assert(effects.exists { case AppEffect.Notify(Notification.RekeyFailed) => true; case _ => false })
  }
