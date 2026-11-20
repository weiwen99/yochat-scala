package yochat.core

import scodec.bits.ByteVector

class VectorsSpec extends munit.FunSuite:

  private def hex(value: String): ByteVector = ByteVector.fromValidHex(value)

  private def key32(value: String): Key32 = Key32.from(hex(value)).fold(error => fail(s"bad key: $error"), identity)

  private def sessionId(value: String): SessionId =
    SessionId.from(hex(value)).fold(error => fail(s"bad session: $error"), identity)

  private def x25519(value: String): X25519KeyPair =
    X25519Private.from(hex(value)).fold(error => fail(s"bad seed: $error"), identity) match
      case seed => X25519KeyPair.fromSeed(seed).fold(error => fail(s"bad pair: $error"), identity)

  private val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
  private val root0  = "00" * 31 + "01"
  private val rb0    = "00" * 31 + "02"
  private val sid    = "00" * 31 + "03"

  test("KDF_RK and KDF_CK match an independent HKDF/HMAC calculation") {
    val okm = Crypto.kdfRoot(key32(root0), key32(shared)).fold(error => fail(s"root: $error"), identity)
    assertEquals(okm._1.bytes.toHex, "927d237c12c0ee9b082099c1792570c44c6ebc17af673acc8149d6044e8b6d0b")
    assertEquals(okm._2.bytes.toHex, "da2dd37165ac35dd8295de11a2bb5ddf041c521349677d8ea2c3c254d39c72dd")
    val chain = Crypto.kdfChain(okm._2)
    assertEquals(chain._1.bytes.toHex, "cd08549e1afd758e7c7dffbfeb03f4c797fdaf68b149f5b08a9f571d7776f492")
    assertEquals(chain._2.bytes.toHex, "1c7421044912c2f4bd4b0e5b7a1cd099fc20e4e50096bc94c3cde45c253643ca")
  }

  test("initial ratchet root and sending chain match the fixed vector") {
    val alice = x25519("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    val bob   = x25519("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    val keys  = SessionKeys(sessionId(sid), key32(root0), key32(rb0))
    val peer  = Ed25519Public.from(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
      .fold(error => fail(s"peer: $error"), identity)
    val state = SessionSetup
      .initiator(keys, peer, peer, alice, bob.publicKey)
      .fold(error => fail(s"setup: $error"), identity)
    assertEquals(state.rootKey.bytes.toHex, "927d237c12c0ee9b082099c1792570c44c6ebc17af673acc8149d6044e8b6d0b")
    assertEquals(state.ckSend.map(_.bytes.toHex), Some("da2dd37165ac35dd8295de11a2bb5ddf041c521349677d8ea2c3c254d39c72dd"))
  }

  test("rekey root, session id, and rekey base match the fixed vector") {
    val alice = x25519("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    val bob   = x25519("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    val next  = x25519("00" * 31 + "77")
    val devicePrivate = Ed25519Private
      .from(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
      .fold(error => fail(s"device private: $error"), identity)
    val devicePublic = Ed25519Public
      .from(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
      .fold(error => fail(s"device public: $error"), identity)
    val keys = SessionKeys(sessionId(sid), key32(root0), key32(rb0))
    val old = SessionSetup
      .initiator(keys, devicePublic, devicePublic, x25519("00" * 31 + "99"), x25519("00" * 31 + "98").publicKey)
      .fold(error => fail(s"old setup: $error"), identity)
      .copy(phase = SessionPhase.Active, safetyVerified = true, rekeyEpoch = 3L)
    val id = RekeyId.from(hex("11" * 16)).fold(error => fail(s"id: $error"), identity)
    val started = Rekey
      .start(old, devicePublic, devicePrivate, alice, id, TestKeys.nonce(1), 0L)
      .fold(error => fail(s"start: $error"), identity)
    val responded = Rekey
      .respond(old, started.request, devicePublic, devicePrivate, bob, next, TestKeys.nonce(2))
      .fold(error => fail(s"respond: $error"), identity)
    val accept = RekeyAccept.fromBytes(responded.accept.canonical).fold(error => fail(s"accept: $error"), identity)
    val completed = Rekey
      .complete(started.state, accept)
      .fold(error => fail(s"complete: $error"), identity)
    assertEquals(completed.newSession.sessionId.bytes.toHex, "8351ad5273cd9c71f72bbcf9f0f94fd6febff36db0d9b37094f13698b3e60182")
    assertEquals(completed.newSession.rekeyBase.bytes.toHex, "ba9f6294883f222a8fa70a7fb9e226c132700ad2357117c9e43b448b7b7a7176")
    assertEquals(completed.newSession.rootKey.bytes.toHex, "eb01edef9575ad8fd713b9170575c2ed9e034862a63dd2f732aa3f391d15379e")
    assertEquals(completed.newSession.ckSend.map(_.bytes.toHex), Some("8f322d99bba1aa85ddf3f5e88575c20bb476f4015b4e3aab36a0e47bd0c9fd8a"))
  }
