package yochat.core

import scodec.bits.ByteVector

class RatchetSpec extends munit.FunSuite:

  private val MessageDomain = Crypto.ascii("yochat-s1-msg")

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

  private def fixture(): Fixture =
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
      .create(invitation, bobUser, bobDevice, bobEph, bobRatchet0, bobRatchet1, "matrix", "@bob:example.org", "reply", TestKeys.nonce(1), 1_000L)
      .fold(error => fail(s"reply: $error"), identity)
    val aliceReply = Reply
      .accept(invitation, bobReply.text, aliceEph.privateKey, aliceRatchet, 1_000L)
      .fold(error => fail(s"accept: $error"), identity)
    Fixture(Ratchet.markSafetyVerified(aliceReply.state), Ratchet.markSafetyVerified(bobReply.state), aliceDevice, bobDevice)

  private def send(
    state: RatchetState,
    device: Ed25519KeyPair,
    seq: Long,
    nonceByte: Int,
    plaintext: String
  ): (RatchetState, WireFrame) =
    Ratchet
      .encryptUser(state, device.publicKey, device.privateKey, seq, nonce(nonceByte), Crypto.text(plaintext))
      .fold(error => fail(s"encrypt: $error"), identity)

  private def parse(frame: WireFrame): WireFrame =
    WireFrame.parse(frame.bytes).fold(error => fail(s"parse: $error"), identity)

  private def receive(
    state: RatchetState,
    frame: WireFrame,
    seqSeen: Boolean = false,
    newDh: Option[X25519KeyPair] = None
  ): ReceiveResult =
    Ratchet.decrypt(state, parse(frame), seqSeen, newDh).fold(error => fail(s"decrypt: ${error.error}"), identity)

  private def receiveError(
    state: RatchetState,
    frame: WireFrame,
    seqSeen: Boolean = false,
    newDh: Option[X25519KeyPair] = None
  ): ReceiveError =
    Ratchet.decrypt(state, parse(frame), seqSeen, newDh).fold(identity, _ => fail("expected rejection"))

  test("A to B, B to A, then A to B converges after both DH ratchets") {
    val f          = fixture()
    val (a1, f1)   = send(f.a, f.aDevice, 1L, 1, "hello")
    val b1         = receive(f.b, f1)
    assertEquals(b1.plaintext, Crypto.text("hello"))

    val (b2, f2)   = send(b1.state, f.bDevice, 1L, 2, "world")
    val a2         = receive(a1, f2, newDh = Some(x25519(seed(31))))
    assertEquals(a2.plaintext, Crypto.text("world"))

    val (_, f3)    = send(a2.state, f.aDevice, 2L, 3, "again")
    val b3         = receive(b2, f3, newDh = Some(x25519(seed(32))))
    assertEquals(b3.plaintext, Crypto.text("again"))
    assertEquals(b3.state.nr, 1L)
  }

  test("B can send first; A performs the DH ratchet on receipt") {
    val f        = fixture()
    val (b1, f1) = send(f.b, f.bDevice, 1L, 4, "b-first")
    val a1       = receive(f.a, f1, newDh = Some(x25519(seed(33))))
    assertEquals(a1.plaintext, Crypto.text("b-first"))
    assertEquals(a1.state.nr, 1L)
    assertEquals(Ratchet.decrypt(f.a, parse(f1), false, None), Left(ReceiveError(f.a, CoreError.RekeyRequired)))

    val (_, f2)  = send(a1.state, f.aDevice, 1L, 5, "reply")
    val b2       = receive(b1, f2, newDh = Some(x25519(seed(34))))
    assertEquals(b2.plaintext, Crypto.text("reply"))
  }

  test("out-of-order delivery uses skipped keys and does not advance the chain twice") {
    val f         = fixture()
    val (a1, f1)  = send(f.a, f.aDevice, 1L, 10, "one")
    val (a2, f2)  = send(a1, f.aDevice, 2L, 11, "two")
    val (_, f3)   = send(a2, f.aDevice, 3L, 12, "three")

    val b3 = receive(f.b, f3)
    assertEquals(b3.plaintext, Crypto.text("three"))
    assertEquals(b3.state.skipped.size, 2)
    assertEquals(b3.state.nr, 3L)

    val b1 = receive(b3.state, f1)
    assertEquals(b1.plaintext, Crypto.text("one"))
    assertEquals(b1.state.skipped.size, 1)
    assertEquals(b1.state.nr, 3L)

    val b2 = receive(b1.state, f2)
    assertEquals(b2.plaintext, Crypto.text("two"))
    assertEquals(b2.state.skipped.size, 0)
    assertEquals(b2.state.nr, 3L)
  }

  test("duplicate seq and replay are rejected without AEAD failure counting") {
    val f        = fixture()
    val (_, f1)  = send(f.a, f.aDevice, 1L, 20, "once")
    val b1       = receive(f.b, f1)
    assertEquals(receiveError(b1.state, f1, seqSeen = true).error, CoreError.DuplicateSeq)
    val replay = receiveError(b1.state, f1)
    assertEquals(replay.error, CoreError.Replay)
    assertEquals(replay.state.rekeyFailures, 0)
  }

  test("signature tamper is rejected before AEAD") {
    val f       = fixture()
    val (_, f1) = send(f.a, f.aDevice, 1L, 21, "plain")
    val tampered = f1.copy(ciphertext = f1.ciphertext.update(0, (f1.ciphertext(0) ^ 1).toByte))
    assertEquals(receiveError(f.b, tampered).error, CoreError.InvalidSignature)
  }

  test("current-chain AEAD failures count to RekeyRequired and stop at three") {
    val f       = fixture()
    val (_, f1) = send(f.a, f.aDevice, 1L, 22, "plain")
    val badCt   = f1.ciphertext.update(0, (f1.ciphertext(0) ^ 1).toByte)
    val sig = Crypto
      .sign(f.aDevice.privateKey, MessageDomain ++ f1.header.canonical ++ badCt)
      .fold(error => fail(s"sign: $error"), identity)
    val badFrame = WireFrame.create(f1.header, badCt, sig).fold(error => fail(s"frame: $error"), identity)

    val first  = receiveError(f.b, badFrame)
    val second = receiveError(first.state, badFrame)
    val third  = receiveError(second.state, badFrame)
    assertEquals(first.error, CoreError.InvalidCiphertext)
    assertEquals(second.error, CoreError.InvalidCiphertext)
    assertEquals(third.error, CoreError.RekeyRequired)
    assertEquals(first.state.rekeyFailures, 1)
    assertEquals(second.state.rekeyFailures, 2)
    assertEquals(third.state.rekeyFailures, 3)
    val fourth = receiveError(third.state, badFrame)
    assertEquals(fourth.error, CoreError.RekeyRequired)
    assertEquals(fourth.state.rekeyFailures, S1Limits.MaxRekeyFailures)
    assert(StateCodec.encode(fourth.state).isRight)
  }

  test("a gap larger than MAX_SKIP_GAP is rejected without state advance") {
    val f = fixture()
    val (_, frames) = (1 to 66).foldLeft((f.a, List.empty[WireFrame])) { case ((state, collected), index) =>
      val (next, frame) = send(state, f.aDevice, index.toLong, index, s"m$index")
      (next, frame :: collected)
    }
    val latest = frames.head
    val result = receiveError(f.b, latest)
    assertEquals(result.error, CoreError.GapTooLarge)
    assertEquals(result.state, f.b)
  }

  test("old DH without a skipped key returns StaleRatchet after a ratchet advance") {
    val f        = fixture()
    val (a1, f1) = send(f.a, f.aDevice, 1L, 30, "seen")
    val b1       = receive(f.b, f1)

    val (_, fb)  = send(b1.state, f.bDevice, 1L, 32, "b-first")
    val a2r      = receive(a1, fb, newDh = Some(x25519(seed(35))))
    val (_, fa)  = send(a2r.state, f.aDevice, 2L, 33, "new-chain")
    val b2       = receive(b1.state, fa, newDh = Some(x25519(seed(36))))
    assertEquals(b2.plaintext, Crypto.text("new-chain"))

    val stale = receiveError(b2.state, f1)
    assertEquals(stale.error, CoreError.StaleRatchet)
    assertEquals(stale.state.rekeyFailures, 0)
  }

  test("unverified receivers reject data bodies but still accept control frames") {
    val f = fixture()
    val (_, frame) = Ratchet
      .encrypt(f.a, f.aDevice.publicKey, f.aDevice.privateKey, 1L, nonce(42), Crypto.text("body"))
      .fold(error => fail(s"encrypt: $error"), identity)
    assertEquals(receiveError(f.b.copy(safetyVerified = false), frame).error, CoreError.InvalidState)
  }

  test("a state codec round-trip can continue the ratchet") {
    val f        = fixture()
    val (a1, f1) = send(f.a, f.aDevice, 1L, 43, "one")
    val b1       = receive(f.b, f1)
    val encoded  = StateCodec.encode(b1.state).fold(error => fail(s"encode: $error"), identity)
    val restored = StateCodec.decode(encoded).fold(error => fail(s"decode: $error"), identity)
    val (_, f2)  = send(a1, f.aDevice, 2L, 44, "two")
    val b2       = receive(restored, f2)
    assertEquals(b2.plaintext, Crypto.text("two"))
  }

  test("safety verification is required before a user body is encrypted") {
    val f = fixture()
    val result = Ratchet.encryptUser(
      f.a.copy(safetyVerified = false),
      f.aDevice.publicKey,
      f.aDevice.privateKey,
      1L,
      nonce(41),
      Crypto.text("body")
    )
    assertEquals(result, Left(CoreError.InvalidState))
  }

  test("secret-bearing state and receive results have redacted toString") {
    val f        = fixture()
    val (_, f1)  = send(f.a, f.aDevice, 1L, 45, "secret-body")
    val b1       = receive(f.b, f1)
    assert(!f.a.toString.contains(f.a.rootKey.bytes.toHex))
    assert(!b1.toString.contains("secret-body"))
    assert(!b1.toString.contains(f.b.rootKey.bytes.toHex))
    val error = receiveError(b1.state, f1, seqSeen = true)
    assert(!error.toString.contains(f.b.rootKey.bytes.toHex))
  }

  test("frame round-trip preserves the exact bytes") {
    val f       = fixture()
    val (_, f1) = send(f.a, f.aDevice, 1L, 40, "round-trip")
    assertEquals(parse(f1).bytes, f1.bytes)
  }
