package yochat.core

import java.security.KeyPairGenerator

import scodec.bits.ByteVector

/** Test-only randomness/key generation. Production key and nonce generation lives in the effectful identity layer. */
object TestKeys:

  private val EdPublicPrefix  = ByteVector.fromValidHex("302a300506032b6570032100")
  private val EdPrivatePrefix = ByteVector.fromValidHex("302e020100300506032b657004220420")

  def ed25519(): Ed25519KeyPair =
    val generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicKey = Ed25519Public
      .from(ByteVector.view(generated.getPublic.getEncoded).drop(EdPublicPrefix.size))
      .fold(error => throw new IllegalStateException(s"generated Ed25519 public key: $error"), identity)
    val privateKey = Ed25519Private
      .from(ByteVector.view(generated.getPrivate.getEncoded).drop(EdPrivatePrefix.size))
      .fold(error => throw new IllegalStateException(s"generated Ed25519 private key: $error"), identity)
    Ed25519KeyPair(privateKey, publicKey)

  def nonce(byte: Int): Nonce = Nonce.trust(ByteVector.fill(Nonce.Length)(byte.toByte))

  def key32(byte: Int): Key32 = Key32.trust(ByteVector.fill(Key32.Length)(byte.toByte))

  def rekeyId(byte: Int): RekeyId = RekeyId.trust(ByteVector.fill(RekeyId.Length)(byte.toByte))
