package yochat.core.app

import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.*

class BindingSpec extends FunSuite:

  private val peers = AppTestSupport.connected

  private def firstFrame(state: AppState, body: ByteVector): WireFrame =
    val session = state.sessions.values.head
    Ratchet
      .encryptUser(session.state, peers.alice.device.publicKey, peers.alice.device.privateKey, 1L, TestKeys.nonce(1), body)
      .fold(error => fail(error.toString), identity)
      ._2

  private def inbound(state: AppState, frame: WireFrame): (AppState, Vector[AppEffect]) =
    Application.step(
      state,
      AppEvent.InboundFrame(1L, state.sessions.keys.head, frame, None, TestKeys.nonce(2), 0L)
    )

  private def expectedSafety: String =
    SafetyNumber
      .derive(peers.alice.user.publicKey, peers.bob.user.publicKey)
      .fold(error => fail(error.toString), identity)
      .digits

  test("the first UserSend carries both accounts and the safety number") {
    val conversation = peers.aliceState.sessions.values.head.conversationId
    val (_, effects)  = Application.step(
      peers.aliceState,
      AppEvent.UserSend(1L, conversation, Crypto.text("hello"), TestKeys.nonce(1), 0L)
    )
    val outgoing      = effects.collectFirst { case AppEffect.PersistOutbound(value, _) => value }.getOrElse(fail("missing outbound"))
    assertEquals(outgoing.body, Crypto.text("hello"))
    val received      = Ratchet
      .decrypt(peers.bobState.sessions.values.head.state, outgoing.wire, false, None)
      .fold(error => fail(error.toString), identity)
    val payload       = DataPayload.decode(received.plaintext).fold(error => fail(error.toString), identity)
    assertEquals(payload.binding, Some(DataBinding("@alice", "@bob", expectedSafety)))
    assertEquals(payload.body, Crypto.text("hello"))
  }

  test("missing, wrong-account, wrong-safety, or mismatched first Data is rejected without state changes") {
    val cases = Vector(
      Crypto.text("legacy"),
      encoded(DataPayload(None, Crypto.text("body"))),
      payload(DataBinding("@mallory", "@bob", expectedSafety)),
      payload(DataBinding("@alice", "@mallory", expectedSafety)),
      payload(DataBinding("@alice", "@bob", "0" * DataPayload.SafetyDigitsLength))
    )
    cases.foreach { body =>
      val (next, effects) = inbound(peers.bobState, firstFrame(peers.aliceState, body))
      assertEquals(next, peers.bobState)
      assertEquals(effects, Vector.empty)
    }

    val mismatch = peers.bobState.copy(
      contacts = peers.bobState.contacts.map { case (key, contact) =>
        key -> contact.copy(confirmation = ContactConfirmation.AccountMismatch)
      }
    )
    val (next, effects) = inbound(mismatch, firstFrame(peers.aliceState, payload(DataBinding("@alice", "@bob", expectedSafety))))
    assertEquals(next, mismatch)
    assertEquals(effects, Vector.empty)
  }

  test("binding on a later sequence is rejected, while an unbound later sequence applies") {
    val firstState = peers.aliceState.sessions.values.head.state
    val (afterFirst, firstFrameWire) = Ratchet
      .encryptUser(firstState, peers.alice.device.publicKey, peers.alice.device.privateKey, 1L, TestKeys.nonce(1), payload(DataBinding("@alice", "@bob", expectedSafety)))
      .fold(error => fail(error.toString), identity)
    val boundLater = Ratchet
      .encryptUser(afterFirst, peers.alice.device.publicKey, peers.alice.device.privateKey, 2L, TestKeys.nonce(2), payload(DataBinding("@alice", "@bob", expectedSafety)))
      .fold(error => fail(error.toString), identity)
      ._2
    val (firstBob, firstEffects) = inbound(peers.bobState, firstFrameWire)
    assert(firstEffects.exists { case AppEffect.PersistInbound(value, _) => value.seq == 1L; case _ => false })
    val (secondBob, secondEffects) = inbound(firstBob, boundLater)
    assertEquals(secondBob, firstBob)
    assertEquals(secondEffects, Vector.empty)
  }

  test("repeated invalid first Data stays silent; a duplicate admitted first Data only re-ACKs") {
    val invalid = firstFrame(peers.aliceState, Crypto.text("legacy"))
    val (firstInvalid, firstInvalidEffects) = inbound(peers.bobState, invalid)
    val (secondInvalid, secondInvalidEffects) = inbound(firstInvalid, invalid)
    assertEquals(firstInvalidEffects, Vector.empty)
    assertEquals(secondInvalid, firstInvalid)
    assertEquals(secondInvalidEffects, Vector.empty)

    val valid = firstFrame(peers.aliceState, payload(DataBinding("@alice", "@bob", expectedSafety)))
    val (admitted, admittedEffects) = inbound(peers.bobState, valid)
    assert(admittedEffects.exists { case AppEffect.PersistInbound(_, _) => true; case _ => false })
    val (duplicate, duplicateEffects) = inbound(admitted, valid)
    assertEquals(duplicate.conversations, admitted.conversations)
    assert(duplicateEffects.exists { case AppEffect.SendAck(_, _, _) => true; case _ => false })
    assert(!duplicateEffects.exists { case AppEffect.PersistInbound(_, _) => true; case _ => false })
  }

  test("B-first first Data also carries and validates the binding") {
    val conversation = peers.bobState.sessions.values.head.conversationId
    val (_, bobEffects) = Application.step(
      peers.bobState,
      AppEvent.UserSend(2L, conversation, Crypto.text("body-1"), TestKeys.nonce(3), 0L)
    )
    val frame  = bobEffects.collectFirst { case AppEffect.PersistOutbound(value, _) => value.wire }.getOrElse(fail("missing outbound"))
    val newDh  = Some(AppTestSupport.xpair(33))
    val direct = Ratchet.decrypt(peers.aliceState.sessions.values.head.state, frame, false, newDh)
    assert(direct.isRight, direct.toString)
    val (next, effects) = Application.step(
      peers.aliceState,
      AppEvent.InboundFrame(2L, peers.aliceState.sessions.keys.head, frame, newDh, TestKeys.nonce(3), 0L)
    )
    assert(
      effects.exists { case AppEffect.PersistInbound(value, _) => value.body == Crypto.text("body-1"); case _ => false },
      effects.toString
    )
    assertEquals(next.conversations.values.head.nextSeq.getOrElse(peers.bob.user.publicKey, 1L), 2L)
  }

  test("an account-mismatch contact cannot send or receive first Data") {
    val blocked = peers.aliceState.copy(
      contacts = peers.aliceState.contacts.map { case (key, contact) =>
        key -> contact.copy(confirmation = ContactConfirmation.AccountMismatch)
      }
    )
    val (next, effects) = Application.step(
      blocked,
      AppEvent.UserSend(1L, blocked.sessions.values.head.conversationId, Crypto.text("blocked"), TestKeys.nonce(4), 0L)
    )
    assertEquals(next, blocked)
    assertEquals(effects, Vector.empty)
  }

  test("a duplicate ReplyCreated for the same session is idempotent") {
    val invitation = peers.invitation
    val record = AppInvitation(
      invitation.fields.ephPub.bytes,
      invitation,
      Some(peers.aliceEphemeral.privateKey),
      Some(peers.aliceEphemeral.privateKey),
      None,
      InvitationLifecycle.Issued,
      invitation.fields.expiresAt
    )
    val withInvitation = peers.bobState.copy(invitations = Map(record.id -> record))
    val event = AppEvent.ReplyCreated(record.id, ByteVector.fill(32)(0x56.toByte), peers.bobReply, 1_000L)
    val (next, _) = Application.step(withInvitation, event)
    val (again, effects) = Application.step(next, event)
    assertEquals(again.contacts(peers.alice.user.publicKey).confirmation, ContactConfirmation.Confirmed)
    assert(effects.isEmpty)
  }

  test("a conflicting ReplyCreated also marks the existing contact unusable") {
    val invitation = peers.invitation
    val record = AppInvitation(
      invitation.fields.ephPub.bytes,
      invitation,
      Some(peers.aliceEphemeral.privateKey),
      Some(peers.aliceEphemeral.privateKey),
      None,
      InvitationLifecycle.Issued,
      invitation.fields.expiresAt
    )
    val conflictingSession = SessionId
      .from(ByteVector.fill(SessionId.Length)(0x77.toByte))
      .fold(error => fail(error.toString), identity)
    val conflictingReply   = peers.bobReply.copy(state = peers.bobReply.state.copy(sessionId = conflictingSession))
    val withInvitation = peers.bobState.copy(invitations = Map(record.id -> record))
    val (next, effects) = Application.step(
      withInvitation,
      AppEvent.ReplyCreated(record.id, ByteVector.fill(32)(0x56.toByte), conflictingReply, 1_000L)
    )
    assertEquals(next.contacts(peers.alice.user.publicKey).confirmation, ContactConfirmation.AccountMismatch)
    assert(effects.exists { case AppEffect.PersistContact(value) => value.confirmation == ContactConfirmation.AccountMismatch; case _ => false })
    assert(!effects.exists { case AppEffect.PersistHandshake(_, _, _, _, _, _, _) => true; case _ => false })
  }

  test("a conflicting re-pairing reply marks the contact unusable instead of replacing it") {
    val invitation = peers.invitation
    val record     = AppInvitation(
      invitation.fields.ephPub.bytes,
      invitation,
      Some(peers.aliceEphemeral.privateKey),
      Some(peers.aliceEphemeral.privateKey),
      None,
      InvitationLifecycle.Issued,
      invitation.fields.expiresAt
    )
    val withInvitation = peers.aliceState.copy(invitations = Map(record.id -> record))
    val replacement    = Reply
      .create(
        invitation,
        peers.bob.user,
        peers.bob.device,
        AppTestSupport.xpair(11),
        AppTestSupport.xpair(12),
        AppTestSupport.xpair(13),
        "test",
        "@bob2",
        "other pairing",
        TestKeys.nonce(5),
        1_000L
      )
      .fold(error => fail(error.toString), identity)
    val (next, effects) = Application.step(
      withInvitation,
      AppEvent.ReplyReceived(record.id, ByteVector.fill(32)(0x55.toByte), replacement, None, 1_000L)
    )
    assertEquals(next.contacts(peers.bob.user.publicKey).confirmation, ContactConfirmation.AccountMismatch)
    assert(effects.exists { case AppEffect.PersistContact(value) => value.confirmation == ContactConfirmation.AccountMismatch; case _ => false })
    assert(!effects.exists { case AppEffect.PersistHandshake(_, _, _, _, _, _, _) => true; case _ => false })
  }

  private def payload(binding: DataBinding): ByteVector =
    encoded(DataPayload(Some(binding), Crypto.text("body")))

  private def encoded(value: DataPayload): ByteVector =
    DataPayload.encode(value).fold(error => fail(error.toString), identity)
