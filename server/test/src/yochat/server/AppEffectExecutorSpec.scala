package yochat.server

import cats.effect.{IO, Ref}
import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.app.*
import yochat.store.MessageState

class AppEffectExecutorSpec extends FunSuite:

  final private class RecordingTransport(state: Ref[IO, Vector[String]]) extends AppTransport:

    def sendWire(messageId: Long, wire: yochat.core.WireFrame): IO[Unit] =
      state.update(_ :+ s"wire:$messageId:${wire.bytes.toHex}")

    def sendReply(invitationId: ByteVector, text: String): IO[Unit] =
      state.update(_ :+ s"reply:${invitationId.toHex}:$text")

    def sendRekeyRequest(sessionId: yochat.core.SessionId, request: yochat.core.RekeyRequest): IO[Unit] =
      state.update(_ :+ s"rekey-request:$sessionId")

    def sendRekeyAccept(sessionId: yochat.core.SessionId, accept: yochat.core.RekeyAccept): IO[Unit] =
      state.update(_ :+ s"rekey-accept:$sessionId")

    def sendRekeyConfirm(newSessionId: yochat.core.SessionId, frame: yochat.core.WireFrame): IO[Unit] =
      state.update(_ :+ s"rekey-confirm:$newSessionId")

    def sendRekeyConfirmAck(newSessionId: yochat.core.SessionId, frame: yochat.core.WireFrame): IO[Unit] =
      state.update(_ :+ s"rekey-ack:$newSessionId")

    def startTimer(timer: TimerId, deadlineMs: Long): IO[Unit] =
      state.update(_ :+ s"timer:${timer.kind}:${timer.messageId}:$deadlineMs")

    def cancelTimer(timer: TimerId): IO[Unit] =
      state.update(_ :+ s"cancel:${timer.kind}:${timer.messageId}")

    def notify(notification: Notification): IO[Unit] =
      state.update(_ :+ s"notify:$notification")

  test("executes PersistOutbound and then sends the exact stored wire") {
    ExecutorTestSupport.withStore { store =>
      val fixture = ExecutorTestSupport.fixture()
      val app     = ExecutorTestSupport.appState(fixture)
      for
        _        <- ExecutorTestSupport.initialize(fixture, store)
        sent     <- IO.pure(
                      Application.step(
                        app,
                        AppEvent.UserSend(1L, fixture.conversationId, ExecutorTestSupport.body("hello"), ExecutorTestSupport.nonce(1), 0L)
                      )
                    )
        ref      <- Ref.of[IO, Vector[String]](Vector.empty)
        executor  = new AppEffectExecutor(store, fixture.identity, new RecordingTransport(ref))
        executed <- executor.executeAll(sent._2)
        _        <- IO.fromEither(executed.left.map(error => new AssertionError(error.code)))
        message  <-
          store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
        record   <- IO.fromEither(message.left.map(error => new AssertionError(error.code)))
        outgoing  = sent._1.outgoing(1L)
        now      <- IO.pure(10L)
        success  <- IO.pure(Application.step(sent._1, AppEvent.PersistOutboundSucceeded(1L, outgoing.generation, now)))
        sentAll  <- executor.executeAll(success._2)
        _        <- IO.fromEither(sentAll.left.map(error => new AssertionError(error.code)))
        acked    <- IO.pure(Application.step(success._1, AppEvent.SendSucceeded(1L, outgoing.generation)))
        ackedAll <- executor.executeAll(acked._2)
        _        <- IO.fromEither(ackedAll.left.map(error => new AssertionError(error.code)))
        sent     <-
          store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
        sentRec  <- IO.fromEither(sent.left.map(error => new AssertionError(error.code)))
        log      <- ref.get
      yield
        assertEquals(record.body, ExecutorTestSupport.body("hello"))
        assertEquals(record.wire, outgoing.wire.bytes)
        assertEquals(sentRec.state, MessageState.Sent)
        assert(log.exists(line => line.startsWith("wire:1:")))
        assert(log.exists(_.startsWith("timer:Delivery:1:")))
    }
  }

  test("executes PersistInbound through appendInbound with a received message") {
    ExecutorTestSupport.withStore { store =>
      val fixture = ExecutorTestSupport.fixture()
      val message = BufferedMessage(
        id = 2L,
        authorPub = fixture.peerUser,
        devicePub = fixture.peerDevice,
        seq = 1L,
        body = ExecutorTestSupport.body("inbound"),
        wire = yochat.core.WireFrame.parse(ExecutorTestSupport.wire(fixture, 1L)).fold(error => fail(error.toString), identity)
      )
      val effect  = AppEffect.PersistInbound(message, AppSession(fixture.state, fixture.conversationId, 1L))
      for
        _        <- ExecutorTestSupport.initialize(fixture, store)
        ref      <- Ref.of[IO, Vector[String]](Vector.empty)
        executor  = new AppEffectExecutor(store, fixture.identity, new RecordingTransport(ref))
        executed <- executor.execute(effect)
        _        <- IO.fromEither(executed.left.map(error => new AssertionError(error.code)))
        loaded   <- store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.peerUser, fixture.peerDevice, 1L)
        record   <- IO.fromEither(loaded.left.map(error => new AssertionError(error.code)))
      yield
        assertEquals(record.body, ExecutorTestSupport.body("inbound"))
        assertEquals(record.state, MessageState.Received)
    }
  }

  test("executes DeleteSession with the initiator-ACK cutover proof") {
    ExecutorTestSupport.withStore { store =>
      val fixture = ExecutorTestSupport.fixture()
      val app     = ExecutorTestSupport.appState(fixture)
      for
        _         <- ExecutorTestSupport.initialize(fixture, store)
        sent      <- IO.pure(
                       Application.step(
                         app,
                         AppEvent.UserSend(2L, fixture.conversationId, ExecutorTestSupport.body("delete"), ExecutorTestSupport.nonce(2), 0L)
                       )
                     )
        ref       <- Ref.of[IO, Vector[String]](Vector.empty)
        executor   = new AppEffectExecutor(store, fixture.identity, new RecordingTransport(ref))
        persisted <- executor.executeAll(sent._2)
        _         <- IO.fromEither(persisted.left.map(error => new AssertionError(error.code)))
        loaded    <-
          store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
        message   <- IO.fromEither(loaded.left.map(error => new AssertionError(error.code)))
        _         <- store.setMessageState(message.id, MessageState.Pending, MessageState.Sent, 0L)
        deleted   <- executor.execute(AppEffect.DeleteSession(fixture.sessionId, SessionRetirement.InitiatorAck))
        _         <- IO.fromEither(deleted.left.map(error => new AssertionError(error.code)))
        gone      <- store.loadSession(fixture.sessionId)
      yield assertEquals(gone.left.map(_.code), Left("NotFound"))
    }
  }

  test("persists a locally-created invitation with its ephemeral private key") {
    ExecutorTestSupport.withStore { store =>
      val fixture    = ExecutorTestSupport.fixture()
      val ephemeral  = ExecutorTestSupport.xpair(7)
      val invitation = yochat.core.Invitation
        .create(
          fixture.identity.user,
          fixture.identity.device,
          ephemeral.publicKey,
          ExecutorTestSupport.xpair(8).publicKey,
          "test",
          "@alice",
          10_000L,
          "invite"
        )
        .fold(error => fail(error.toString), identity)
      val app        = AppState.empty.copy(identity = Some(fixture.identity), lock = LockState.Unlocked(0L))
      val event      = Application.step(app, AppEvent.InvitationCreated(invitation, ephemeral.privateKey, Some("@bob"), 0L))
      for
        ref      <- Ref.of[IO, Vector[String]](Vector.empty)
        executor  = new AppEffectExecutor(store, fixture.identity, new RecordingTransport(ref))
        executed <- executor.executeAll(event._2)
        _        <- IO.fromEither(executed.left.map(error => new AssertionError(error.code)))
        record   <- store.loadInvitation(invitation.fields.ephPub.bytes)
        loaded   <- IO.fromEither(record.left.map(error => new AssertionError(error.code)))
      yield
        assertEquals(loaded.data.ephemeralPrivate, ephemeral.privateKey)
        assertEquals(loaded.data.expiresAt, 10_000L)
    }
  }
