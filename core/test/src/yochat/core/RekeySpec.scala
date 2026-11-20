package yochat.core

import scodec.bits.ByteVector

class RekeySpec extends munit.FunSuite:

  private final case class Fixture(a: RatchetState, b: RatchetState, aDevice: Ed25519KeyPair, bDevice: Ed25519KeyPair)

  private def hex(value: String): ByteVector = ByteVector.fromValidHex(value)

  private def x25519(seedHex: String): X25519KeyPair =
    val seed = X25519Private.from(hex(seedHex)).fold(error => fail(s"bad seed: $error"), identity)
    X25519KeyPair.fromSeed(seed).fold(error => fail(s"bad pair: $error"), identity)

  private def ed25519(): Ed25519KeyPair =
    TestKeys.ed25519()

  private def seed(byte: Int): String = f"$byte%02x" * 32

  private def nonce(byte: Int): Nonce =
    Nonce.from(ByteVector.fill(Nonce.Length)(byte.toByte)).fold(error => fail(s"bad nonce: $error"), identity)

  private def createdAt(now: Long): Fixture =
    val aliceUser    = ed25519()
    val aliceDevice  = ed25519()
    val aliceEph     = x25519(seed(1))
    val aliceRatchet = x25519(seed(2))
    val bobUser      = ed25519()
    val bobDevice    = ed25519()
    val bobEph       = x25519(seed(3))
    val bobRatchet0  = x25519(seed(4))
    val bobRatchet1  = x25519(seed(5))
    val invitation = Invitation
      .create(aliceUser, aliceDevice, aliceEph.publicKey, aliceRatchet.publicKey, "matrix", "@alice:example.org", 10_000L, "invite")
      .fold(error => fail(s"invite: $error"), identity)
    val bobReply = Reply
      .create(invitation, bobUser, bobDevice, bobEph, bobRatchet0, bobRatchet1, "matrix", "@bob:example.org", "reply", TestKeys.nonce(1), now)
      .fold(error => fail(s"reply: $error"), identity)
    val aliceReply = Reply
      .accept(invitation, bobReply.text, aliceEph.privateKey, aliceRatchet, now)
      .fold(error => fail(s"accept: $error"), identity)
    Fixture(Ratchet.markSafetyVerified(aliceReply.state), Ratchet.markSafetyVerified(bobReply.state), aliceDevice, bobDevice)

  private def pending(state: RatchetState): RekeyState =
    state.rekey.fold(fail("missing pending rekey"))(identity)

  private def send(state: RatchetState, device: Ed25519KeyPair, seq: Long, nonceByte: Int, plaintext: String): (RatchetState, WireFrame) =
    Ratchet
      .encryptUser(state, device.publicKey, device.privateKey, seq, nonce(nonceByte), Crypto.text(plaintext))
      .fold(error => fail(s"send: $error"), identity)

  private def receive(state: RatchetState, frame: WireFrame, newDh: Option[X25519KeyPair]): ReceiveResult =
    val parsed = WireFrame.parse(frame.bytes).fold(error => fail(s"parse: $error"), identity)
    Ratchet.decrypt(state, parsed, false, newDh).fold(error => fail(s"receive: ${error.error}"), identity)

  test("request, accept, confirm, and ack converge on the new session") {
    val f      = createdAt(1_000L)
    val id     = RekeyId.trust(ByteVector.fill(RekeyId.Length)(9.toByte))
    val r1     = x25519(seed(10))
    val r2     = x25519(seed(11))
    val r2Next = x25519(seed(12))

    val started = Rekey
      .start(f.a, f.aDevice.publicKey, f.aDevice.privateKey, r1, id, nonce(1), 0L)
      .fold(error => fail(s"start: $error"), identity)
    val request = RekeyRequest.fromBytes(started.request.canonical).fold(error => fail(s"parse request: $error"), identity)
    request.verify(f.a.rekeyBase).fold(error => fail(s"verify request: $error"), identity)

    val responded = Rekey
      .respond(f.b, request, f.bDevice.publicKey, f.bDevice.privateKey, r2, r2Next, nonce(2))
      .fold(error => fail(s"respond: $error"), identity)
    val accept = RekeyAccept.fromBytes(responded.accept.canonical).fold(error => fail(s"parse accept: $error"), identity)
    accept.verify(f.b.rekeyBase).fold(error => fail(s"verify accept: $error"), identity)

    val completed = Rekey
      .complete(started.state, accept)
      .fold(error => fail(s"complete: $error"), identity)
    val bNew = responded.newSession.fold(fail("missing responder session"))(identity)
    assertEquals(completed.newSession.sessionId, bNew.sessionId)
    assertEquals(completed.newSession.rekeyBase, bNew.rekeyBase)
    assertEquals(completed.state.phase, SessionPhase.ReadOnly)
    assertEquals(completed.newSession.phase, SessionPhase.Pending)
    assertEquals(pending(started.state).phase, RekeyPhase.Requested)
    assertEquals(
      Ratchet.encryptUser(
        completed.newSession,
        f.aDevice.publicKey,
        f.aDevice.privateKey,
        1L,
        nonce(5),
        Crypto.text("pending")
      ),
      Left(CoreError.InvalidState)
    )
    assertEquals(pending(responded.state).phase, RekeyPhase.Accepted)

    val control = ControlExtension(pending(started.state).epoch, id)
    val confirmed = Rekey
      .confirm(completed.newSession, f.aDevice.publicKey, f.aDevice.privateKey, control, nonce(3))
      .fold(error => fail(s"confirm: $error"), identity)
    val acked = Rekey
      .acceptConfirm(bNew, confirmed.frame, control, f.bDevice.publicKey, f.bDevice.privateKey, nonce(4), None)
      .fold(error => fail(s"accept confirm: $error"), identity)
    val finalState = Rekey
      .acceptAck(confirmed.state, acked.frame, control, x25519(seed(13)))
      .fold(error => fail(s"accept ack: $error"), identity)

    assertEquals(finalState.state.sessionId, bNew.sessionId)
    assertEquals(finalState.state.rekeyBase, bNew.rekeyBase)
    assertEquals(finalState.state.phase, SessionPhase.Active)
    assertEquals(acked.state.sessionId, bNew.sessionId)
    assertEquals(acked.state.phase, SessionPhase.Active)

    assertEquals(Rekey.complete(completed.state, accept), Left(CoreError.RekeyPending))
    val sameRetry = Rekey
      .acceptConfirm(acked.state, confirmed.frame, control, f.bDevice.publicKey, f.bDevice.privateKey, nonce(6), None)
      .fold(error => fail(s"same retry: $error"), identity)
    assertEquals(sameRetry.state.phase, SessionPhase.Active)
    val newConfirm = Rekey
      .confirm(confirmed.state, f.aDevice.publicKey, f.aDevice.privateKey, control, nonce(7))
      .fold(error => fail(s"new confirm: $error"), identity)
    val newRetry = Rekey
      .acceptConfirm(acked.state, newConfirm.frame, control, f.bDevice.publicKey, f.bDevice.privateKey, nonce(8), None)
      .fold(error => fail(s"new-n retry: $error"), identity)
    assertEquals(newRetry.state.phase, SessionPhase.Active)
    val (dataState, dataFrame) = send(acked.state, f.bDevice, 1L, 9, "data-before-ack")
    val advancedA = receive(confirmed.state, dataFrame, newDh = Some(x25519(seed(60))))
    val retryConfirm = Rekey
      .confirm(advancedA.state, f.aDevice.publicKey, f.aDevice.privateKey, control, nonce(10))
      .fold(error => fail(s"retry after data: $error"), identity)
    val retryAfterData = Rekey
      .acceptConfirm(dataState, retryConfirm.frame, control, f.bDevice.publicKey, f.bDevice.privateKey, nonce(11), Some(x25519(seed(61))))
      .fold(error => fail(s"retry after data accept: $error"), identity)
    assertEquals(retryAfterData.state.phase, SessionPhase.Active)
    assertEquals(Rekey.clearTerminal(completed.state).map(_.rekey), Right(None))
    assertEquals(Rekey.clearTerminal(acked.state), Left(CoreError.InvalidState))
  }

  test("duplicate request resends the cached accept and does not reset state") {
    val f      = createdAt(1_000L)
    val id     = TestKeys.rekeyId(1)
    val started = Rekey
      .start(f.a, f.aDevice.publicKey, f.aDevice.privateKey, x25519(seed(20)), id, nonce(10), 0L)
      .fold(error => fail(s"start: $error"), identity)
    val request = started.request
    val first = Rekey
      .respond(f.b, request, f.bDevice.publicKey, f.bDevice.privateKey, x25519(seed(21)), x25519(seed(22)), nonce(11))
      .fold(error => fail(s"respond: $error"), identity)
    val duplicate = Rekey
      .respond(first.state, request, f.bDevice.publicKey, f.bDevice.privateKey, x25519(seed(23)), x25519(seed(24)), nonce(12))
      .fold(error => fail(s"duplicate: $error"), identity)
    assertEquals(duplicate.state, first.state)
    assertEquals(duplicate.accept.canonical, first.accept.canonical)
    assertEquals(duplicate.newSession, None)
  }

  test("start is pending after a request, and malformed accept is rejected") {
    val f      = createdAt(1_000L)
    val id     = TestKeys.rekeyId(1)
    val started = Rekey
      .start(f.a, f.aDevice.publicKey, f.aDevice.privateKey, x25519(seed(30)), id, nonce(20), 0L)
      .fold(error => fail(s"start: $error"), identity)
    assertEquals(
      Rekey.start(started.state, f.aDevice.publicKey, f.aDevice.privateKey, x25519(seed(31)), TestKeys.rekeyId(1), nonce(21), 0L),
      Left(CoreError.RekeyPending)
    )
    val responded = Rekey
      .respond(f.b, started.request, f.bDevice.publicKey, f.bDevice.privateKey, x25519(seed(32)), x25519(seed(33)), nonce(22))
      .fold(error => fail(s"respond: $error"), identity)
    val tampered = responded.accept.canonical.update(0, (responded.accept.canonical(0) ^ 1).toByte)
    assert(RekeyAccept.fromBytes(tampered).isLeft)
  }

  test("abort starts a cooldown and the attempt cap is enforced") {
    val f = createdAt(1_000L)
    val first = Rekey
      .start(f.a, f.aDevice.publicKey, f.aDevice.privateKey, x25519(seed(50)), TestKeys.rekeyId(2), nonce(40), 0L)
      .fold(error => fail(s"first start: $error"), identity)
    val aborted = Rekey.abort(first.state, 1_000L).fold(error => fail(s"abort: $error"), identity)
    assertEquals(aborted.phase, SessionPhase.Active)
    assertEquals(aborted.rekey, None)
    assertEquals(aborted.rekeyCooldownUntilMs, 61_000L)
    assertEquals(
      Rekey.start(aborted, f.aDevice.publicKey, f.aDevice.privateKey, x25519(seed(51)), TestKeys.rekeyId(3), nonce(41), 2_000L),
      Left(CoreError.RekeyFailed)
    )
    val second = Rekey
      .start(aborted, f.aDevice.publicKey, f.aDevice.privateKey, x25519(seed(52)), TestKeys.rekeyId(4), nonce(42), 61_000L)
      .fold(error => fail(s"second start: $error"), identity)
    val third = Rekey
      .start(
        Rekey.abort(second.state, 62_000L).fold(error => fail(s"second abort: $error"), identity),
        f.aDevice.publicKey,
        f.aDevice.privateKey,
        x25519(seed(53)),
        TestKeys.rekeyId(5),
        nonce(43),
        122_000L
      )
      .fold(error => fail(s"third start: $error"), identity)
    assertEquals(third.state.rekeyAttempts, 3)
    assertEquals(
      Rekey.start(
        Rekey.abort(third.state, 63_000L).fold(error => fail(s"third abort: $error"), identity),
        f.aDevice.publicKey,
        f.aDevice.privateKey,
        x25519(seed(54)),
        TestKeys.rekeyId(6),
        nonce(44),
        200_000L
      ),
      Left(CoreError.RekeyFailed)
    )
  }

  test("request and accept canonical round-trip preserves bytes") {
    val f      = createdAt(1_000L)
    val started = Rekey
      .start(f.a, f.aDevice.publicKey, f.aDevice.privateKey, x25519(seed(40)), TestKeys.rekeyId(1), nonce(30), 0L)
      .fold(error => fail(s"start: $error"), identity)
    assertEquals(RekeyRequest.fromBytes(started.request.canonical).map(_.canonical), Right(started.request.canonical))
    val responded = Rekey
      .respond(f.b, started.request, f.bDevice.publicKey, f.bDevice.privateKey, x25519(seed(41)), x25519(seed(42)), nonce(31))
      .fold(error => fail(s"respond: $error"), identity)
    assertEquals(RekeyAccept.fromBytes(responded.accept.canonical).map(_.canonical), Right(responded.accept.canonical))
  }
