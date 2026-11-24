package yochat.core.app

import munit.FunSuite
import scodec.bits.ByteVector

class ReplyRetrySpec extends FunSuite:

  test("an accepted reply stays durable, retries on its own timer, and leaves only after send success") {
    val peers                                      = AppTestSupport.connected
    val id                                         = peers.invitation.fields.ephPub.bytes
    val conversation                               = ByteVector.fill(32)(0x61.toByte)
    val (received, _)                              =
      Application.step(
        AppState.empty.copy(identity = Some(peers.bob), lock = LockState.Unlocked(0L)),
        AppEvent.InvitationReceived(peers.invitation, Some("@alice"), 0L)
      )
    val (pending, effects)                         = Application.step(received, AppEvent.ReplyCreated(id, conversation, peers.bobReply, 1_000L))
    val invitation                                 = pending.invitations(id)
    assertEquals(invitation.lifecycle, InvitationLifecycle.PendingReply)
    assertEquals(invitation.replyText, Some(peers.bobReply.text))
    assert(effects.exists {
      case AppEffect.PersistHandshake(_, _, _, Some(value), _, _, _) =>
        value.lifecycle == InvitationLifecycle.PendingReply && value.replyText.contains(peers.bobReply.text)
      case _ => false
    })
    val handshakeIndex = effects.indexWhere { case _: AppEffect.PersistHandshake => true; case _ => false }
    val sendIndex      = effects.indexWhere { case _: AppEffect.SendReply => true; case _ => false }
    assert(handshakeIndex >= 0 && sendIndex > handshakeIndex)

    val (retry, failedEffects) = Application.step(pending, AppEvent.ReplySendFailed(id, 2_000L))
    assertEquals(retry.invitations(id).replyAttempts, 1)
    assert(failedEffects.exists { case AppEffect.PersistInvitation(value) => value.replyAttempts == 1; case _ => false })
    assert(failedEffects.exists {
      case AppEffect.StartTimer(TimerId.Reply(`id`), deadline, _) => deadline == 2_000L + AppLimits.ReplyRetryMs
      case _                                                   => false
    })

    val (_, timerEffects) = Application.step(retry, AppEvent.TimerFired(TimerId.Reply(id), 0L, 4_000L))
    assert(timerEffects.exists { case AppEffect.SendReply(`id`, _, _) => true; case _ => false })

    val (sent, sentEffects) = Application.step(retry, AppEvent.ReplySent(id))
    assert(sent.invitations.get(id).isEmpty)
    assert(sentEffects.contains(AppEffect.DeleteInvitation(id)))
  }
