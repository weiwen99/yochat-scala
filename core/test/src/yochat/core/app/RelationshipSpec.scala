package yochat.core.app

import munit.FunSuite
import scodec.bits.ByteVector

class RelationshipSpec extends FunSuite:

  test("Q1: responder confirms on reply; inviter stays pending until its own operation") {
    val peers                          = AppTestSupport.connected
    val id                             = peers.invitation.fields.ephPub.bytes
    val aliceBase                      = AppState.empty.copy(identity = Some(peers.alice), lock = LockState.Unlocked(0L))
    val bobBase                        = AppState.empty.copy(identity = Some(peers.bob), lock = LockState.Unlocked(0L))
    val (aliceInvited, _)              =
      Application.step(aliceBase, AppEvent.InvitationCreated(
        peers.invitation,
        peers.aliceEphemeral.privateKey,
        AppTestSupport.xpair(2).privateKey,
        Some("@bob"),
        0L
      ))
    val (bobInvited, _)                = Application.step(bobBase, AppEvent.InvitationReceived(peers.invitation, Some("@alice"), 0L))
    val conversation                   = ByteVector.fill(32)(0x55.toByte)
    val (bobReplied, replyEffects)     = Application.step(bobInvited, AppEvent.ReplyCreated(id, conversation, peers.bobReply, 1_000L))
    assert(replyEffects.exists { case AppEffect.SendReply(invitationId, _, _) => invitationId == id; case _ => false })
    assertEquals(bobReplied.contacts(peers.alice.user.publicKey).confirmation, ContactConfirmation.Confirmed)
    val (aliceAccepted, acceptEffects) = Application.step(
      aliceInvited,
      AppEvent.ReplyReceived(id, conversation, peers.aliceReply, Some("@bob"), 1_000L)
    )
    assertEquals(aliceAccepted.contacts(peers.bob.user.publicKey).confirmation, ContactConfirmation.Pending)
    assert(!acceptEffects.exists { case AppEffect.SendWire(_, _, _, _) => true; case _ => false })
    assert(!acceptEffects.exists { case AppEffect.SendReply(_, _, _) => true; case _ => false })
  }

  test("Q1: account mismatch is marked, rejection is silent, and expiry removes the contact path") {
    val peers                       = AppTestSupport.connected
    val id                          = peers.invitation.fields.ephPub.bytes
    val conversation                = ByteVector.fill(32)(0x56.toByte)
    val aliceBase                   = AppState.empty.copy(identity = Some(peers.alice), lock = LockState.Unlocked(0L))
    val (aliceInvited, _)           =
      Application.step(aliceBase, AppEvent.InvitationCreated(
        peers.invitation,
        peers.aliceEphemeral.privateKey,
        AppTestSupport.xpair(2).privateKey,
        Some("@bob"),
        0L
      ))
    val (mismatch, mismatchEffects) = Application.step(
      aliceInvited,
      AppEvent.ReplyReceived(id, conversation, peers.aliceReply, Some("@wrong"), 1_000L)
    )
    assertEquals(mismatch.contacts(peers.bob.user.publicKey).confirmation, ContactConfirmation.AccountMismatch)
    assert(!mismatchEffects.exists { case AppEffect.SendWire(_, _, _, _) => true; case _ => false })

    val (rejected, rejectEffects) = Application.step(aliceInvited, AppEvent.RejectInvitation(id))
    assert(rejected.invitations.get(id).isEmpty)
    assert(!rejectEffects.exists { case AppEffect.SendWire(_, _, _, _) => true; case _ => false })
    assert(!rejectEffects.exists { case AppEffect.Notify(_) => true; case _ => false })

    val (expired, expiredEffects) = Application.step(
      aliceInvited,
      AppEvent.ReplyReceived(id, conversation, peers.aliceReply, Some("@bob"), peers.invitation.fields.expiresAt + 1L)
    )
    assertEquals(expired.invitations(id).lifecycle, InvitationLifecycle.Expired)
    assert(expired.contacts.get(peers.bob.user.publicKey).isEmpty)
    assert(!expiredEffects.exists { case AppEffect.SendWire(_, _, _, _) => true; case _ => false })
  }

  test("safety is contact-authoritative and gates outbound body before verification") {
    val peers                     = AppTestSupport.connected
    val bob                       = peers.bob.user.publicKey
    val unverified                =
      peers.aliceState.copy(contacts = peers.aliceState.contacts.updated(bob, peers.aliceState.contacts(bob).copy(safetyVerified = false)))
    val conversation              = unverified.sessions.values.head.conversationId
    val (_, blocked)              =
      Application.step(unverified, AppEvent.UserSend(9L, conversation, yochat.core.Crypto.text("body"), yochat.core.TestKeys.nonce(4), 0L))
    assertEquals(blocked, Vector.empty)
    val (verified, verifyEffects) = Application.step(peers.aliceState, AppEvent.SafetyVerified(bob, 10L))
    assertEquals(verifyEffects, Vector(AppEffect.PersistSafetyVerified(bob, verified = true)))
    assertEquals(verified.contacts(bob).safetyVerified, true)
    assert(verified.sessions.values.forall(_.state.safetyVerified))
  }
