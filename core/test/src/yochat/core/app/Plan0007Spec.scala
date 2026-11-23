package yochat.core.app

import munit.FunSuite

import yochat.core.TestKeys

class Plan0007Spec extends FunSuite:

  test("local rekey attempts are capped in a 60s window and reset afterwards") {
    val peers = AppTestSupport.connected
    val id    = peers.aliceState.sessions.keys.head
    var state = peers.aliceState
    var now   = 1_000L

    (1 to AppLimits.MaxRekeyWindowAttempts).foreach { attempt =>
      val (next, _) = Application.step(
        state,
        AppEvent.StartRekey(id, AppTestSupport.xpair(attempt), TestKeys.rekeyId(attempt), TestKeys.nonce(attempt), now)
      )
      state = next
      now += 1_000L
    }

    assertEquals(state.rekeyWindowAttempts, AppLimits.MaxRekeyWindowAttempts)
    val (capped, effects) = Application.step(
      state,
      AppEvent.StartRekey(id, AppTestSupport.xpair(20), TestKeys.rekeyId(20), TestKeys.nonce(20), now)
    )
    assert(effects.contains(AppEffect.Notify(Notification.RekeyFailed)))

    val (reset, _) = Application.step(
      capped,
      AppEvent.StartRekey(id, AppTestSupport.xpair(21), TestKeys.rekeyId(21), TestKeys.nonce(21), now + AppLimits.RekeyWindowMs)
    )
    assertEquals(reset.rekeyWindowAttempts, 1)
  }
