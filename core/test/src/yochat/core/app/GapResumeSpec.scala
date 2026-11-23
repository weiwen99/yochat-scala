package yochat.core.app

import munit.FunSuite

import yochat.core.{SessionPhase, TestKeys}

class GapResumeSpec extends FunSuite:

  test("an outstanding buffered gap is resent with a persisted ratchet advance") {
    val peers        = AppTestSupport.connected
    val sessionId    = peers.aliceState.sessions.keys.head
    val conversation = peers.aliceState.conversations.values.head
    val state        = peers.aliceState.copy(
      conversations = peers.aliceState.conversations.updated(conversation.id, conversation.copy(gapRequested = Some(1L)))
    )
    val (resumed, effects) = Application.step(
      state,
      AppEvent.ResumeGap(conversation.id, TestKeys.nonce(23), 3_000L)
    )
    assertEquals(resumed.conversations(conversation.id).gapRequested, None)
    assert(effects.exists { case AppEffect.PersistSession(value) => value.state.sessionId == sessionId; case _ => false })
    assert(effects.exists { case AppEffect.SendGap(`sessionId`, _, _) => true; case _ => false })
    assertEquals(resumed.sessions(sessionId).state.phase, SessionPhase.Active)
  }
