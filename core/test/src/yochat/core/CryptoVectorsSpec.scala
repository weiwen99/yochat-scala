package yochat.core

import scodec.bits.ByteVector

class CryptoVectorsSpec extends munit.FunSuite:

  private def hex(value: String): ByteVector = ByteVector.fromValidHex(value)

  private def key32(hexValue: String): Key32 = Key32.from(hex(hexValue)).fold(error => fail(s"bad key: $error"), identity)

  test("HKDF-SHA256 matches RFC 5869 test case 1 with truncation") {
    val ikm  = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    val salt = hex("000102030405060708090a0b0c")
    val info = hex("f0f1f2f3f4f5f6f7f8f9")
    val expected =
      "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    assertEquals(Crypto.hkdf(ikm, salt, info, 42).map(_.toHex), Right(expected))
  }

  test("HMAC-SHA256 matches RFC 4231 test case 1") {
    val key     = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    val message = Crypto.text("Hi There")
    assertEquals(Crypto.hmacSha256(key, message).bytes.toHex, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
  }

  test("ChaCha20-Poly1305 matches RFC 8439 section 2.8.2") {
    val key       = key32("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    val nonce     = Nonce.from(hex("070000004041424344454647")).fold(error => fail(s"bad nonce: $error"), identity)
    val aad       = hex("50515253c0c1c2c3c4c5c6c7")
    val plaintext = Crypto.text(
      "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
    )
    val expected =
      "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
        "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
        "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691"
    val sealedBytes = Crypto.aeadSeal(key, nonce, aad, plaintext).fold(error => fail(s"seal failed: $error"), identity)
    assertEquals(sealedBytes.toHex, expected)
    assertEquals(Crypto.aeadOpen(key, nonce, aad, sealedBytes), Right(plaintext))
  }

  test("Ed25519 matches RFC 8032 test 1") {
    val privateKey = Ed25519Private
      .from(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
      .fold(error => fail(s"bad private key: $error"), identity)
    val publicKey = Ed25519Public
      .from(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
      .fold(error => fail(s"bad public key: $error"), identity)
    val expected =
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    val signature = Crypto.sign(privateKey, ByteVector.empty).fold(error => fail(s"sign failed: $error"), identity)
    assertEquals(signature.bytes.toHex, expected)
    assert(Crypto.verify(publicKey, ByteVector.empty, signature))
  }

  test("X25519 matches RFC 7748 one-time DH vector") {
    val alicePrivate = X25519Private
      .from(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
      .fold(error => fail(s"bad alice private: $error"), identity)
    val bobPrivate = X25519Private
      .from(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
      .fold(error => fail(s"bad bob private: $error"), identity)
    val alice = X25519KeyPair.fromSeed(alicePrivate).fold(error => fail(s"alice key: $error"), identity)
    val bob   = X25519KeyPair.fromSeed(bobPrivate).fold(error => fail(s"bob key: $error"), identity)

    assertEquals(alice.publicKey.bytes.toHex, "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    assertEquals(bob.publicKey.bytes.toHex, "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    val shared = Crypto.agree(alice.privateKey, bob.publicKey).fold(error => fail(s"agree: $error"), identity)
    assertEquals(shared.bytes.toHex, "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
  }
