package yochat.core

import scodec.bits.ByteVector

class StateCodecSpec extends munit.FunSuite:

  private def hex(value: String): ByteVector = ByteVector.fromValidHex(value)

  private def x25519(seedHex: String): X25519KeyPair =
    val seed = X25519Private.from(hex(seedHex)).fold(error => fail(s"bad seed: $error"), identity)
    X25519KeyPair.fromSeed(seed).fold(error => fail(s"bad pair: $error"), identity)

  private def ed25519(): Ed25519KeyPair =
    TestKeys.ed25519()

  private def seed(byte: Int): String = f"$byte%02x" * 32

  private def sample(): RatchetState =
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
    Reply
      .accept(invitation, bobReply.text, aliceEph.privateKey, aliceRatchet, 1_000L)
      .fold(error => fail(s"accept: $error"), identity)
      .state

  test("state round-trips with skipped, retired, rekey, and counters") {
    val state = sample().copy(
      skipped = Map(SkippedKey(x25519(seed(6)).publicKey, 7L) -> TestKeys.key32(0x44)),
      retiredDh = Vector(x25519(seed(7)).publicKey, x25519(seed(8)).publicKey),
      rekey = Some(
        RekeyState(
          RekeyRole.Initiator,
          TestKeys.rekeyId(1),
          3L,
          RekeyPhase.Requested,
          Some(x25519(seed(9))),
          None,
          Some(ByteVector.fill(20)(7.toByte)),
          42_000L,
          ackReceived = true
        )
      ),
      rekeyFailures = 2,
      safetyVerified = true,
      rekeyAttempts = 2,
      rekeyCooldownUntilMs = 1_234L
    )
    val encoded = StateCodec.encode(state).fold(error => fail(s"encode: $error"), identity)
    val decoded = StateCodec.decode(encoded).fold(error => fail(s"decode: $error"), identity)
    assertEquals(decoded, state)
    assertEquals(StateCodec.encode(decoded), Right(encoded))
  }

  test("state decoder rejects truncation, wrong version, and trailing bytes") {
    val state = sample()
    val encoded = StateCodec.encode(state).fold(error => fail(s"encode: $error"), identity)
    assert(StateCodec.decode(encoded.dropRight(1)).isLeft)
    assert(StateCodec.decode(encoded.drop(1)).isLeft)
    assert(StateCodec.decode(encoded :+ 0.toByte).isLeft)
    assert(StateCodec.decode(ByteVector(4) ++ encoded.drop(1)).isLeft)
  }

  test("state encoder rejects oversized skipped and retired collections") {
    val state = sample()
    val tooManyRetired = state.copy(
      retiredDh = (1 to (S1Limits.MaxRetiredRatchets + 1)).toVector.map(index => x25519(seed(index)).publicKey)
    )
    val tooManySkipped = state.copy(
      skipped = (0 until (S1Limits.MaxSkipTotal + 1)).map(index => SkippedKey(x25519(seed(11)).publicKey, index.toLong) -> TestKeys.key32(0x44)).toMap
    )
    assert(StateCodec.encode(tooManyRetired).isLeft)
    assert(StateCodec.encode(tooManySkipped).isLeft)
  }

  test("v3 rejects a rekey state without a positive deadline") {
    val state = sample()
    val rekey = RekeyState(
      RekeyRole.Initiator,
      TestKeys.rekeyId(1),
      3L,
      RekeyPhase.Requested,
      Some(x25519(seed(9))),
      None,
      None,
      0L
    )
    assert(StateCodec.encode(state.copy(rekey = Some(rekey))).isLeft)
  }

  test("legacy zero deadlines migrate to an already-expired value") {
    val state = sample()
    val rekey = RekeyState(RekeyRole.Initiator, TestKeys.rekeyId(1), 3L, RekeyPhase.Accepted, None, None, None, 0L)
    assertEquals(StateCodec.migrateLegacyDeadline(state.copy(rekey = Some(rekey))).rekey.map(_.deadlineMs), Some(1L))
  }
