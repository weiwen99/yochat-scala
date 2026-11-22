package yochat.core.app

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import munit.FunSuite
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{ControlExtension, Crypto, Ratchet, TestKeys}

class ReplayModelsSpec extends FunSuite:

  test("ITF replay: Q1 relationship trace feeds reply, safety, and content gate") {
    val states      = readStates("models/traces/q1_relationship.itf.json", "947600b5068052871d45fdea828c141fd956bc74e73ba4f9b270bcc2370a2250")
    val peers       = AppTestSupport.connected
    val id          = peers.invitation.fields.ephPub.bytes
    val conv        = peers.aliceReply.state.sessionId.bytes
    val alice0      = Application
      .step(
        AppState.empty.copy(identity = Some(peers.alice), lock = LockState.Unlocked(0L)),
        AppEvent.InvitationCreated(peers.invitation, peers.aliceEphemeral.privateKey, Some("@bob"), 0L)
      )
      ._1
    val bob0        = Application
      .step(
        AppState.empty.copy(identity = Some(peers.bob), lock = LockState.Unlocked(0L)),
        AppEvent.InvitationReceived(peers.invitation, Some("@alice"), 0L)
      )
      ._1
    val initial     = (alice0, bob0, Vector.empty[AppEffect])
    val transitions = states
      .sliding(2)
      .flatMap {
        case Seq(previous, next) => Some((previous, next))
        case _                   => None
      }
      .toVector
    transitions.foldLeft(initial) { case ((alice, bob, effectsSeen), (_, next)) =>
      val before                              = q1Observed(alice, bob, id, peers.alice.user.publicKey, peers.bob.user.publicKey)
      val target                              = q1State(next)
      val (aliceAfterReply, bobAfterReply)    =
        if target.replied && !before.replied then
          val bobEvents   = Application.step(bob, AppEvent.ReplyCreated(id, conv, peers.bobReply, 1_000L))
          val expected    = if target.mismatch then Some("@wrong") else Some("@bob")
          val aliceEvents = Application.step(alice, AppEvent.ReplyReceived(id, conv, peers.aliceReply, expected, 1_000L))
          (aliceEvents._1, bobEvents._1)
        else (alice, bob)
      val (aliceAfterSafety, bobAfterSafety)  =
        if target.safetyVerified && !before.safetyVerified then
          (
            Application.step(aliceAfterReply, AppEvent.SafetyVerified(peers.bob.user.publicKey, 1_001L))._1,
            Application.step(bobAfterReply, AppEvent.SafetyVerified(peers.alice.user.publicKey, 1_001L))._1
          )
        else (aliceAfterReply, bobAfterReply)
      val (aliceAfterContent, contentEffects) =
        if target.contentSent && !before.contentSent then
          Application.step(
            aliceAfterSafety,
            AppEvent.UserSend(1L, conv, Crypto.text("trace-content"), TestKeys.nonce(1), 1_002L)
          )
        else (aliceAfterSafety, Vector.empty[AppEffect])
      val (aliceAfterExpiry, expiryEffects)   =
        if target.expired && !before.expired then
          Application.step(
            aliceAfterContent,
            AppEvent.ReplyReceived(id, conv, peers.aliceReply, Some("@bob"), peers.invitation.fields.expiresAt + 1L)
          )
        else (aliceAfterContent, Vector.empty[AppEffect])
      val (aliceAfterReject, rejectEffects)   =
        if target.rejected && !before.rejected then Application.step(aliceAfterExpiry, AppEvent.RejectInvitation(id))
        else (aliceAfterExpiry, Vector.empty[AppEffect])
      val actual                              = q1Observed(aliceAfterReject, bobAfterSafety, id, peers.alice.user.publicKey, peers.bob.user.publicKey)
      assertEquals(actual, target, s"Q1 state mismatch after ${before} -> ${target}")
      (aliceAfterReject, bobAfterSafety, effectsSeen ++ contentEffects ++ expiryEffects ++ rejectEffects)
    }
  }

  test("ITF replay: Q3 resend trace feeds bounded attempts and exhaustion notification") {
    val states                      = readStates("models/traces/q3_resend.itf.json", "0bfe51b88d60a9a9780c99c96fdea88320ce7a4fd3bdb433d1407ecd26d44116")
    val peers                       = AppTestSupport.connected
    val conv                        = peers.aliceState.sessions.values.head.conversationId
    val (sent, _)                   = Application.step(peers.aliceState, AppEvent.UserSend(1L, conv, Crypto.text("trace"), TestKeys.nonce(1), 0L))
    val generation                  = sent.outgoing(1L).generation
    val transitions                 = states
      .sliding(2)
      .flatMap {
        case Seq(previous, next) => Some((previous, next))
        case _                   => None
      }
      .toVector
    val (finalState, finalNotified) = transitions.foldLeft((sent, false)) { case ((state, notified), (previous, next)) =>
      val before                         = q3State(previous)
      val after                          = q3State(next)
      val current                        = state.outgoing.get(1L)
      val currentAttempts                = current.fold(0)(_.attempts)
      val (attemptState, attemptEffects) =
        if after.attempts == currentAttempts + 1 then
          current.map(_.state) match
            case Some(OutgoingState.Persisting)                                                    => Application.step(state, AppEvent.PersistOutboundSucceeded(1L, generation, 1L))
            case Some(OutgoingState.InFlight)                                                      => Application.step(state, AppEvent.SendFailed(1L, generation, 2L))
            case Some(OutgoingState.Queued) if state.timers.keys.exists(_.kind == TimerKind.Retry) =>
              Application.step(state, AppEvent.TimerFired(TimerId(TimerKind.Retry, 1L), generation, 3L))
            case Some(OutgoingState.Queued)                                                        => Application.step(state, AppEvent.ConnectionChanged(online = true, nowMs = 3L))
            case _                                                                                 => (state, Vector.empty[AppEffect])
        else (state, Vector.empty[AppEffect])
      val (notifiedState, notifyEffects) =
        if after.notified && !notified then Application.step(attemptState, AppEvent.SendFailed(1L, generation, 4L))
        else (attemptState, Vector.empty[AppEffect])
      val onlineState                    =
        if after.online != before.online then Application.step(notifiedState, AppEvent.ConnectionChanged(after.online, 5L))._1
        else notifiedState
      val notifiedNow                    =
        notified || attemptEffects.exists { case AppEffect.Notify(Notification.MessageFailed) => true; case _ => false } ||
          notifyEffects.exists { case AppEffect.Notify(Notification.MessageFailed) => true; case _ => false }
      assertEquals(onlineState.outgoing.get(1L).fold(0)(_.attempts), after.attempts)
      assertEquals(onlineState.connection == ConnectionState.Online, after.online)
      assertEquals(onlineState.outgoing.contains(1L), after.pending)
      assertEquals(notifiedNow, after.notified)
      assert(!after.delivered)
      (onlineState, notifiedNow)
    }
    assertEquals(finalState.outgoing(1L).state, OutgoingState.Failed)
    assert(finalNotified)
  }

  test("ITF replay: Q4 reconnect trace feeds offline queue and bounded replay") {
    val states      = readStates("models/traces/q4_reconnect.itf.json", "54d10666a98ada1fd50e70a56095fbe7094176388a3c787c491a1289dd4ed23f")
    val peers       = AppTestSupport.connected
    val conv        = peers.aliceState.sessions.values.head.conversationId
    val offline     = peers.aliceState.copy(connection = ConnectionState.Offline)
    val (sent, _)   = Application.step(offline, AppEvent.UserSend(1L, conv, Crypto.text("trace"), TestKeys.nonce(1), 0L))
    val generation  = sent.outgoing(1L).generation
    val initial     = Application.step(sent, AppEvent.PersistOutboundSucceeded(1L, generation, 1L))._1
    val transitions = states
      .sliding(2)
      .flatMap {
        case Seq(previous, next) => Some((previous, next))
        case _                   => None
      }
      .toVector
    val finalState  = transitions.foldLeft(initial) { case (state, (previous, next)) =>
      val before             = q4State(previous)
      val after              = q4State(next)
      val (changed, effects) =
        if after.connected != before.connected then Application.step(state, AppEvent.ConnectionChanged(after.connected, 2L))
        else (state, Vector.empty[AppEffect])
      assertEquals(changed.connection == ConnectionState.Online, after.connected)
      assertEquals(changed.outgoing.get(1L).fold(0)(_.attempts), after.attempts)
      assertEquals(changed.outgoing.contains(1L), after.pending)
      if after.connected && !before.connected then assert(effects.exists { case AppEffect.SendWire(_, _) => true; case _ => false })
      changed
    }
    assert(finalState.outgoing(1L).attempts <= 3)
    assert(finalState.outgoing.contains(1L))
  }

  test("ITF replay: Q5 rekey trace covers cutover and the safety invariants") {
    val states                    = readStates("models/traces/q5_rekey.itf.json", "71b4efdc5186e7578be741fbf3df7a1525bcc3d0f8e8fd502e7e90188c0c6526")
    val peers                     = AppTestSupport.connected
    val oldSession                = peers.aliceState.sessions.keys.head
    val r1                        = AppTestSupport.xpair(61)
    val r2                        = AppTestSupport.xpair(62)
    val r2Next                    = AppTestSupport.xpair(63)
    val rekeyId                   = TestKeys.rekeyId(1)
    val (alice0, startEffects)    = Application.step(
      peers.aliceState,
      AppEvent.StartRekey(oldSession, r1, rekeyId, TestKeys.nonce(10), 0L)
    )
    val request                   = startEffects.collectFirst { case AppEffect.SendRekeyRequest(_, value) => value }.getOrElse(fail("missing request"))
    val (bob0, bobEffects)        = Application.step(
      peers.bobState,
      AppEvent.RekeyRequestReceived(oldSession, request, r2, r2Next, TestKeys.nonce(11))
    )
    val accept                    = bobEffects.collectFirst { case AppEffect.SendRekeyAccept(_, value) => value }.getOrElse(fail("missing accept"))
    val (alice1, aliceEffects)    = Application.step(alice0, AppEvent.RekeyAcceptReceived(oldSession, accept, TestKeys.nonce(12)))
    val confirm                   = aliceEffects.collectFirst { case AppEffect.SendRekeyConfirm(_, value) => value }.getOrElse(fail("missing confirm"))
    val newSession                = alice1.sessions.keys.find(_ != oldSession).getOrElse(fail("missing new session"))
    val expected                  = ControlExtension(request.epoch, request.id)
    val (bob1, bobConfirmEffects) = Application.step(
      bob0,
      AppEvent.RekeyConfirmReceived(newSession, confirm, expected, TestKeys.nonce(13), None)
    )
    val ack                       = bobConfirmEffects.collectFirst { case AppEffect.SendRekeyConfirmAck(_, value) => value }.getOrElse(fail("missing ack"))
    val alice2                    = Application.step(alice1, AppEvent.RekeyAckReceived(newSession, ack, expected, AppTestSupport.xpair(64)))._1
    val (_, frame)                = Ratchet
      .encryptUser(
        alice2.sessions(newSession).state,
        peers.alice.device.publicKey,
        peers.alice.device.privateKey,
        1L,
        TestKeys.nonce(14),
        Crypto.text("new-session-data")
      )
      .fold(error => fail(error.toString), identity)
    val (bob2, dataEffects)       = Application.step(
      bob1,
      AppEvent.InboundFrame(1L, newSession, frame, Some(AppTestSupport.xpair(65)), TestKeys.nonce(6), 6L)
    )
    assert(dataEffects.exists { case AppEffect.DeleteSession(`oldSession`, SessionRetirement.ResponderFirstData) => true; case _ => false })
    assert(!bob2.sessions.contains(oldSession))
    assertEquals(alice2.sessions(newSession).state.phase, yochat.core.SessionPhase.Active)
    assertEquals(bob2.sessions(newSession).state.phase, yochat.core.SessionPhase.Active)
    assert(alice2.contacts.values.forall(_.safetyVerified))
    assert(bob2.contacts.values.forall(_.safetyVerified))
    assert(states.nonEmpty)
    states.foreach { value =>
      val trace = q5State(value)
      assert(!(trace.oldActive && trace.newActive))
      assert(!trace.newActive || trace.oldReadOnly)
      assert(!trace.oldDeleted || !trace.pendingOld)
      assert(!trace.oldDeleted || trace.ack || trace.firstData)
      assert(!trace.oldDeleted || trace.newActive)
      assert(trace.safetyVerified)
    }
  }

  final private case class Q1Observed(
    replied: Boolean,
    responderConfirmed: Boolean,
    inviterConfirmed: Boolean,
    mismatch: Boolean,
    safetyVerified: Boolean,
    contentSent: Boolean,
    rejected: Boolean,
    expired: Boolean
  )

  private def q1State(value: Value): Q1Observed =
    val s = value("s")
    Q1Observed(
      boolOf(s("replied")),
      boolOf(s("responderConfirmed")),
      boolOf(s("inviterConfirmed")),
      boolOf(s("mismatch")),
      boolOf(s("safetyVerified")),
      boolOf(s("contentSent")),
      boolOf(s("rejected")),
      boolOf(s("expired"))
    )

  private def q1Observed(
    alice: AppState,
    bob: AppState,
    invitationId: ByteVector,
    aliceUser: yochat.core.Ed25519Public,
    bobUser: yochat.core.Ed25519Public
  ): Q1Observed =
    val aliceContact = alice.contacts.get(bobUser)
    val bobContact   = bob.contacts.get(aliceUser)
    val invitation   = alice.invitations.get(invitationId)
    Q1Observed(
      replied = bobContact.nonEmpty,
      responderConfirmed = bobContact.nonEmpty,
      inviterConfirmed = aliceContact.exists(_.safetyVerified),
      mismatch = aliceContact.exists(_.confirmation == ContactConfirmation.AccountMismatch),
      safetyVerified = aliceContact.exists(_.safetyVerified) && bobContact.exists(_.safetyVerified),
      contentSent = alice.outgoing.nonEmpty,
      rejected = alice.invitations.get(invitationId).isEmpty && bobContact.isEmpty,
      expired = invitation.exists(_.lifecycle == InvitationLifecycle.Expired)
    )

  final private case class Q3State(attempts: Int, delivered: Boolean, online: Boolean, pending: Boolean, notified: Boolean)

  private def q3State(value: Value): Q3State =
    val s = value("s")
    Q3State(intOf(s("attempts")), boolOf(s("delivered")), boolOf(s("online")), boolOf(s("pending")), boolOf(s("notified")))

  final private case class Q4State(attempts: Int, connected: Boolean, delivered: Boolean, pending: Boolean)

  private def q4State(value: Value): Q4State =
    val s = value("s")
    Q4State(intOf(s("attempts")), boolOf(s("connected")), boolOf(s("delivered")), boolOf(s("pending")))

  final private case class Q5State(
    oldActive: Boolean,
    oldReadOnly: Boolean,
    newPending: Boolean,
    newActive: Boolean,
    ack: Boolean,
    firstData: Boolean,
    pendingOld: Boolean,
    oldDeleted: Boolean,
    safetyVerified: Boolean
  )

  private def q5State(value: Value): Q5State =
    val s = value("s")
    Q5State(
      boolOf(s("oldActive")),
      boolOf(s("oldReadOnly")),
      boolOf(s("newPending")),
      boolOf(s("newActive")),
      boolOf(s("ack")),
      boolOf(s("firstData")),
      boolOf(s("pendingOld")),
      boolOf(s("oldDeleted")),
      boolOf(s("safetyVerified"))
    )

  private def readStates(relative: String, expectedHash: String): Vector[Value] =
    val bytes = Files.readAllBytes(repositoryPath(relative))
    val hash  = MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
    assertEquals(hash, expectedHash)
    ujson.read(new String(bytes, StandardCharsets.UTF_8))("states").arr.toVector

  private def repositoryPath(relative: String): Path =
    def loop(directory: Path): Option[Path] =
      val candidate = directory.resolve(relative)
      if Files.exists(candidate) then Some(candidate)
      else Option(directory.getParent).flatMap(loop)
    loop(Path.of("").toAbsolutePath).getOrElse(fail(s"missing repository file: $relative"))

  private def boolOf(value: Value): Boolean =
    value match
      case obj: ujson.Obj => obj.value.get("bool").fold(false)(_.bool)
      case other          => other.bool

  private def intOf(value: Value): Int =
    value match
      case obj: ujson.Obj => obj.value.get("#bigint").fold(obj.num.toInt)(_.str.toInt)
      case other          => other.num.toInt
