package yochat.server

import cats.effect.{IO, Ref}
import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.app.*
import yochat.store.MessageState

class AppEffectExecutorSpec extends FunSuite:

  final private class RecordingTransport(state: Ref[IO, Vector[String]]) extends AppTransport:

    def sendWire(
      toDevice: yochat.core.Ed25519Public,
      messageId: Long,
      generation: Long,
      wire: yochat.core.WireFrame
    ): IO[Unit] =
      state.update(_ :+ s"wire:$messageId:${wire.bytes.toHex}")

    def sendAck(
      sessionId: yochat.core.SessionId,
      toDevice: yochat.core.Ed25519Public,
      frame: yochat.core.WireFrame
    ): IO[Unit] =
      state.update(_ :+ s"ack:$sessionId:${frame.header.seq}")

    def sendGap(
      sessionId: yochat.core.SessionId,
      toDevice: yochat.core.Ed25519Public,
      frame: yochat.core.WireFrame
    ): IO[Unit] =
      state.update(_ :+ s"gap:$sessionId:${frame.header.seq}")

    def sendReply(
      invitationId: ByteVector,
      toDevice: yochat.core.Ed25519Public,
      text: String
    ): IO[Unit] =
      state.update(_ :+ s"reply:${invitationId.toHex}:$text")

    def sendRekeyRequest(
      sessionId: yochat.core.SessionId,
      toDevice: yochat.core.Ed25519Public,
      request: yochat.core.RekeyRequest
    ): IO[Unit] =
      state.update(_ :+ s"rekey-request:$sessionId")

    def sendRekeyAccept(
      sessionId: yochat.core.SessionId,
      toDevice: yochat.core.Ed25519Public,
      accept: yochat.core.RekeyAccept
    ): IO[Unit] =
      state.update(_ :+ s"rekey-accept:$sessionId")

    def sendRekeyConfirm(
      newSessionId: yochat.core.SessionId,
      toDevice: yochat.core.Ed25519Public,
      frame: yochat.core.WireFrame
    ): IO[Unit] =
      state.update(_ :+ s"rekey-confirm:$newSessionId")

    def sendRekeyConfirmAck(
      newSessionId: yochat.core.SessionId,
      toDevice: yochat.core.Ed25519Public,
      frame: yochat.core.WireFrame
    ): IO[Unit] =
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
        _           <- ExecutorTestSupport.initialize(fixture, store)
        sent        <- IO.pure(
                         Application.step(
                           app,
                           AppEvent.UserSend(1L, fixture.conversationId, ExecutorTestSupport.body("hello"), ExecutorTestSupport.nonce(1), 0L)
                         )
                       )
        ref         <- Ref.of[IO, Vector[String]](Vector.empty)
        executor     = new AppEffectExecutor(store, fixture.identity, new RecordingTransport(ref))
        executed    <- executor.executeAll(sent._2)
        _           <- IO.fromEither(executed.left.map(error => new AssertionError(error.code)))
        message     <-
          store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
        record      <- IO.fromEither(message.left.map(error => new AssertionError(error.code)))
        outgoing     = sent._1.outgoing(1L)
        now         <- IO.pure(10L)
        success     <- IO.pure(Application.step(sent._1, AppEvent.PersistOutboundSucceeded(1L, outgoing.generation, now)))
        sentAll     <- executor.executeAll(success._2)
        _           <- IO.fromEither(sentAll.left.map(error => new AssertionError(error.code)))
        acked       <- IO.pure(Application.step(success._1, AppEvent.SendSucceeded(1L, outgoing.generation)))
        ackedAll    <- executor.executeAll(acked._2)
        _           <- IO.fromEither(ackedAll.left.map(error => new AssertionError(error.code)))
        sent        <-
          store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
        sentRec     <- IO.fromEither(sent.left.map(error => new AssertionError(error.code)))
        delivered   <- executor.execute(
                         AppEffect.UpdateMessageState(
                           fixture.conversationId,
                           fixture.sessionId,
                           fixture.identity.user.publicKey,
                           fixture.identity.device.publicKey,
                           1L,
                           MessageTransition.Delivered
                         )
                       )
        _           <- IO.fromEither(delivered.left.map(error => new AssertionError(error.code)))
        after       <-
          store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
        afterRec    <- IO.fromEither(after.left.map(error => new AssertionError(error.code)))
        lateSent    <- executor.execute(
                         AppEffect.UpdateMessageState(
                           fixture.conversationId,
                           fixture.sessionId,
                           fixture.identity.user.publicKey,
                           fixture.identity.device.publicKey,
                           1L,
                           MessageTransition.Sent
                         )
                       )
        _           <- IO.fromEither(lateSent.left.map(error => new AssertionError(error.code)))
        terminal    <-
          store.loadMessage(fixture.conversationId, fixture.sessionId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
        terminalRec <- IO.fromEither(terminal.left.map(error => new AssertionError(error.code)))
        log         <- ref.get
      yield
        assertEquals(record.body, ExecutorTestSupport.body("hello"))
        assertEquals(record.wire, outgoing.wire.bytes)
        assertEquals(sentRec.state, MessageState.Sent)
        assertEquals(afterRec.state, MessageState.Delivered)
        assertEquals(terminalRec.state, MessageState.Delivered)
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

  test("persists a locally-created invitation with its ephemeral private key") {
    ExecutorTestSupport.withStore { store =>
      val fixture    = ExecutorTestSupport.fixture()
      val ephemeral  = ExecutorTestSupport.xpair(7)
      val ratchet    = ExecutorTestSupport.xpair(8)
      val invitation = yochat.core.Invitation
        .create(
          fixture.identity.user,
          fixture.identity.device,
          ephemeral.publicKey,
          ratchet.publicKey,
          "test",
          "@alice",
          10_000L,
          "invite"
        )
        .fold(error => fail(error.toString), identity)
      val app        = AppState.empty.copy(identity = Some(fixture.identity), lock = LockState.Unlocked(0L))
      val event      = Application.step(
        app,
        AppEvent.InvitationCreated(invitation, ephemeral.privateKey, ratchet.privateKey, Some("@bob"), 0L)
      )
      for
        ref      <- Ref.of[IO, Vector[String]](Vector.empty)
        executor  = new AppEffectExecutor(store, fixture.identity, new RecordingTransport(ref))
        executed <- executor.executeAll(event._2)
        _        <- IO.fromEither(executed.left.map(error => new AssertionError(error.code)))
        record   <- store.loadInvitation(invitation.fields.ephPub.bytes)
        loaded   <- IO.fromEither(record.left.map(error => new AssertionError(error.code)))
      yield
        assertEquals(loaded.data.ephemeralPrivate, Some(ephemeral.privateKey))
        assertEquals(loaded.data.ratchetPrivate, Some(ratchet.privateKey))
        assertEquals(loaded.data.expiresAt, 10_000L)
    }
  }

  test("M7: PersistRebound reports Skipped for a terminal old row") {
    ExecutorTestSupport.withStore { store =>
      val fixture = ExecutorTestSupport.fixture()
      val app     = ExecutorTestSupport.appState(fixture)
      val sent    = Application.step(
        app,
        AppEvent.UserSend(1L, fixture.conversationId, ExecutorTestSupport.body("old"), ExecutorTestSupport.nonce(7), 0L)
      )
      val message = sent._1.outgoing(1L)
      val newId   = yochat.core.SessionId
        .from(ByteVector.fill(32)(0x55.toByte))
        .fold(error => fail(error.toString), identity)
      val newState = fixture.state.copy(sessionId = newId)
      val newSession = AppSession(newState, fixture.conversationId, 1L)
      for
        _          <- ExecutorTestSupport.initialize(fixture, store)
        appended   <- store.appendOutbound(
                        fixture.state,
                        1L,
                        0L,
                        fixture.conversationId,
                        fixture.identity.user.publicKey,
                        fixture.identity.device.publicKey,
                        1L,
                        ExecutorTestSupport.body("old"),
                        message.wire.bytes,
                        MessageState.Delivered
                      )
        _          <- IO.fromEither(appended.left.map(error => new AssertionError(error.code)))
        inserted   <- store.insertSession(newState, 0L)
        _          <- IO.fromEither(inserted.left.map(error => new AssertionError(error.code)))
        ref        <- Ref.of[IO, Vector[String]](Vector.empty)
        executor    = new AppEffectExecutor(store, fixture.identity, new RecordingTransport(ref))
        result     <- executor.executeAll(
                        Vector(
                          AppEffect.PersistRebound(
                            message,
                            newSession,
                            fixture.sessionId,
                            1L,
                            -1L
                          )
                        )
                      )
      yield assertEquals(result, Right(Vector(1L)))
    }
  }
