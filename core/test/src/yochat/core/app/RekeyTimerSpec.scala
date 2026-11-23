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
      case AppEffect.StartTimer(TimerId.Rekey(`sessionId`, RekeyTimerStage.Initiator), deadline) =>
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
