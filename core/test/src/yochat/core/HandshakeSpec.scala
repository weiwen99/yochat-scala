package yochat.core

import scodec.bits.ByteVector

class HandshakeSpec extends munit.FunSuite:

  private def hex(value: String): ByteVector = ByteVector.fromValidHex(value)

  private def x25519(seedHex: String): X25519KeyPair =
    val seed = X25519Private.from(hex(seedHex)).fold(error => fail(s"bad seed: $error"), identity)
    X25519KeyPair.fromSeed(seed).fold(error => fail(s"bad x25519 pair: $error"), identity)

  private def ed25519(): Ed25519KeyPair =
    TestKeys.ed25519()

  private def seed(byte: Int): String = f"$byte%02x" * 32

  test("invitation round-trips, verifies, normalizes and binds the signature") {
    val user    = ed25519()
    val device  = ed25519()
    val eph     = x25519(seed(1))
    val ratchet = x25519(seed(2))
    val invitation = Invitation
      .create(user, device, eph.publicKey, ratchet.publicKey, "matrix", "@alice:example.org", 1_000L, "hello")
      .fold(error => fail(s"create: $error"), identity)

    val parsed = Invitation.fromText(invitation.text).fold(error => fail(s"parse: $error"), identity)
    assertEquals(parsed.fields, invitation.fields)
    assertEquals(parsed.invitationHash, invitation.invitationHash)
    assertEquals(parsed.canonical, invitation.canonical)

    val tampered = invitation.text.dropRight(1) + (if invitation.text.endsWith("A") then "B" else "A")
    assert(Invitation.fromText(tampered).isLeft)
  }

  test("invitation normalizes NFC and rejects control characters") {
    val user   = ed25519()
    val device = ed25519()
    val eph    = x25519(seed(3))
    val ratchet = x25519(seed(4))
    val invitation = Invitation
      .create(user, device, eph.publicKey, ratchet.publicKey, "matrix", "@alice:example.org", 1_000L, "e\u0301")
      .fold(error => fail(s"create: $error"), identity)
    assertEquals(invitation.fields.note, "é")
    assert(
      Invitation.create(user, device, eph.publicKey, ratchet.publicKey, "matrix", "@alice:example.org", 1_000L, "bad\nnote").isLeft
    )
  }

  test("reply opens only with the invitation key, verifies, and converges both sides") {
    val aliceUser    = ed25519()
    val aliceDevice  = ed25519()
    val aliceEph     = x25519(seed(10))
    val aliceRatchet = x25519(seed(11))
    val bobUser      = ed25519()
    val bobDevice    = ed25519()
    val bobEph       = x25519(seed(12))
    val bobRatchet0  = x25519(seed(13))
    val bobRatchet1  = x25519(seed(14))

    val invitation = Invitation
      .create(aliceUser, aliceDevice, aliceEph.publicKey, aliceRatchet.publicKey, "matrix", "@alice:example.org", 10_000L, "invite")
      .fold(error => fail(s"invite: $error"), identity)
    val parsedInvitation = Invitation.fromText(invitation.text).fold(error => fail(s"parse invite: $error"), identity)
    val bobReply = Reply
      .create(parsedInvitation, bobUser, bobDevice, bobEph, bobRatchet0, bobRatchet1, "matrix", "@bob:example.org", "reply", TestKeys.nonce(1), 1_000L)
      .fold(error => fail(s"reply: $error"), identity)
    val aliceReply = Reply
      .accept(parsedInvitation, bobReply.text, aliceEph.privateKey, aliceRatchet, 1_000L)
      .fold(error => fail(s"accept: $error"), identity)

    assertEquals(aliceReply.keys.sessionId, bobReply.keys.sessionId)
    assertEquals(aliceReply.keys.rootKey, bobReply.keys.rootKey)
    assertEquals(aliceReply.keys.rekeyBase, bobReply.keys.rekeyBase)
    assertEquals(aliceReply.fields.userPub, bobUser.publicKey)
    assertEquals(aliceReply.fields.devicePub, bobDevice.publicKey)
    assertEquals(aliceReply.state.peerUserPub, bobUser.publicKey)
    assertEquals(bobReply.state.peerUserPub, aliceUser.publicKey)
    assertEquals(aliceReply.state.dhPeer, Some(bobRatchet0.publicKey))
    assertEquals(bobReply.state.dhSelf, bobRatchet1)
    assertEquals(aliceReply.state.dhSelf, aliceRatchet)
    assertEquals(aliceReply.state.rekeyBase, bobReply.state.rekeyBase)
    assertNotEquals(aliceReply.state.rootKey, bobReply.state.rootKey)
  }

  test("reply rejects wrong invitation key, tamper, and expiry") {
    val aliceUser    = ed25519()
    val aliceDevice  = ed25519()
    val aliceEph     = x25519(seed(20))
    val aliceRatchet = x25519(seed(21))
    val bobUser      = ed25519()
    val bobDevice    = ed25519()
    val bobEph       = x25519(seed(22))
    val bobRatchet0  = x25519(seed(23))
    val bobRatchet1  = x25519(seed(24))
    val invitation = Invitation
      .create(aliceUser, aliceDevice, aliceEph.publicKey, aliceRatchet.publicKey, "matrix", "@alice:example.org", 10_000L, "invite")
      .fold(error => fail(s"invite: $error"), identity)
    val bobReply = Reply
      .create(invitation, bobUser, bobDevice, bobEph, bobRatchet0, bobRatchet1, "matrix", "@bob:example.org", "reply", TestKeys.nonce(1), 1_000L)
      .fold(error => fail(s"reply: $error"), identity)

    assert(Reply.accept(invitation, bobReply.text, x25519(seed(25)).privateKey, aliceRatchet, 1_000L).isLeft)
    assert(Reply.accept(invitation, bobReply.text, aliceEph.privateKey, aliceRatchet, 10_001L) == Left(CoreError.Expired))
    val tampered = bobReply.text.dropRight(1) + (if bobReply.text.endsWith("A") then "B" else "A")
    assert(Reply.accept(invitation, tampered, aliceEph.privateKey, aliceRatchet, 1_000L).isLeft)
  }

  test("key roles and reply accounts are checked") {
    val user   = ed25519()
    val device = ed25519()
    val eph    = x25519(seed(30))
    val ratchet = x25519(seed(31))
    assertEquals(
      Invitation.create(user, user, eph.publicKey, ratchet.publicKey, "matrix", "@alice:example.org", 1_000L, "same user"),
      Left(CoreError.InvalidKey)
    )
    assertEquals(
      Invitation.create(user, device, eph.publicKey, eph.publicKey, "matrix", "@alice:example.org", 1_000L, "same eph"),
      Left(CoreError.InvalidKey)
    )

    val invitation = Invitation
      .create(user, device, eph.publicKey, ratchet.publicKey, "matrix", "@alice:example.org", 10_000L, "invite")
      .fold(error => fail(s"invite: $error"), identity)
    val bobUser   = ed25519()
    val bobDevice = ed25519()
    val bobEph    = x25519(seed(32))
    val bobR0     = x25519(seed(33))
    val bobR1     = x25519(seed(34))
    val reply = Reply
      .create(invitation, bobUser, bobDevice, bobEph, bobR0, bobR1, "matrix", "@bob:example.org", "reply", TestKeys.nonce(2), 1_000L)
      .fold(error => fail(s"reply: $error"), identity)
    assertEquals(Reply.matchesAccount(reply, "@bob:example.org"), Right(()))
    assertEquals(Reply.matchesAccount(reply, "@mallory:example.org"), Left(CoreError.InvalidReply))
    assertEquals(
      Reply.create(invitation, bobUser, bobUser, bobEph, bobR0, bobR1, "matrix", "@bob:example.org", "reply", TestKeys.nonce(3), 1_000L),
      Left(CoreError.InvalidKey)
    )
  }

  test("safety number is symmetric, 39 digits, and gives each side a different segment") {
    val alice = ed25519().publicKey
    val bob   = ed25519().publicKey
    val a     = SafetyNumber.derive(alice, bob).fold(error => fail(s"a: $error"), identity)
    val b     = SafetyNumber.derive(bob, alice).fold(error => fail(s"b: $error"), identity)

    assertEquals(a.digits, b.digits)
    assertEquals(a.digits.length, 39)
    assertEquals(a.first.length, 20)
    assertEquals(a.second.length, 19)
    assertEquals(a.first + a.second, a.digits)
    assertEquals(a.localReadsFirst, !b.localReadsFirst)
    assertNotEquals(a.localSegment, b.localSegment)
    assertEquals(a.display, a.localSegment.grouped(5).mkString(" "))
  }
