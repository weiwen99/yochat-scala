package yochat.core

import scodec.bits.ByteVector

class HandshakeVectorSpec extends munit.FunSuite:

  private def hex(value: String): ByteVector = ByteVector.fromValidHex(value)

  private def ed(privateSeed: String, publicKey: String): Ed25519KeyPair =
    for
      privateKey <- Ed25519Private.from(hex(privateSeed))
      public     <- Ed25519Public.from(hex(publicKey))
    yield Ed25519KeyPair(privateKey, public)
  .fold(error => fail(s"ed25519 vector: $error"), identity)

  private def x(seed: String): X25519KeyPair =
    X25519Private.from(hex(seed)).fold(error => fail(s"x25519 seed: $error"), identity) match
      case value => X25519KeyPair.fromSeed(value).fold(error => fail(s"x25519 pair: $error"), identity)

  private def fixed(byte: Int): String = f"$byte%02x" * 32

  test("fixed invitation/reply transcript yields fixed session material") {
    val aliceUser    = ed("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val aliceDevice  = ed("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb", "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    val bobUser      = ed("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7", "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
    val bobDevice    = ed("833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42", "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf")
    val aliceEph     = x(fixed(1))
    val aliceRatchet = x(fixed(2))
    val bobEph       = x(fixed(3))
    val bobRatchet0  = x(fixed(4))
    val bobRatchet1  = x(fixed(5))
    val invitation = Invitation
      .create(aliceUser, aliceDevice, aliceEph.publicKey, aliceRatchet.publicKey, "matrix", "@alice:example.org", 10_000L, "invite")
      .fold(error => fail(s"invite: $error"), identity)
    val reply = Reply
      .create(invitation, bobUser, bobDevice, bobEph, bobRatchet0, bobRatchet1, "matrix", "@bob:example.org", "reply", TestKeys.nonce(0x55), 1_000L)
      .fold(error => fail(s"reply: $error"), identity)
    val accepted = Reply
      .accept(invitation, reply.text, aliceEph.privateKey, aliceRatchet, 1_000L)
      .fold(error => fail(s"accept: $error"), identity)

    assertEquals(
      (
        invitation.invitationHash.bytes.toHex,
        accepted.keys.sessionId.bytes.toHex,
        accepted.keys.rootKey.bytes.toHex,
        accepted.keys.rekeyBase.bytes.toHex
      ),
      (
        "916ed2fe4ed283c9d5be5887a676d5c91e121f6fea9f118d13bc55c0ae5412c6",
        "32698642f52273c2ab4eae8ac88f3c82f4ca1793a64a9ffd231cf38cfd8f4efd",
        "c6d16e3525203fdd12dab52d4bcaae2b5724e20aff4843f8cf5034d18dc17eae",
        "b3de3d816c133979fe402dca520a80750d3df115766fb215841166635d270948"
      )
    )
    assertEquals(accepted.state.peerUserPub.bytes.toHex, bobUser.publicKey.bytes.toHex)
    assertEquals(accepted.state.peerDevicePub.bytes.toHex, bobDevice.publicKey.bytes.toHex)
  }
