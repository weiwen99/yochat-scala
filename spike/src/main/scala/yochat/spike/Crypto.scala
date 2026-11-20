package yochat.spike

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.{KeyFactory, KeyPair, KeyPairGenerator, MessageDigest, PrivateKey, PublicKey, SecureRandom, Signature}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64
import javax.crypto.{Cipher, KeyAgreement, Mac}
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

/** JDK-only crypto for the A-first spike.
  *
  * This is a feasibility harness, not the final scheme. It uses JDK 25 Ed25519,
  * X25519, ChaCha20-Poly1305 and a local RFC 5869 HKDF. The final protocol,
  * including the ratchet, must be designed and reviewed separately.
  */
object Crypto:
  private val Random = new SecureRandom()
  private val Utf8   = StandardCharsets.UTF_8

  def randomBytes(length: Int): Array[Byte] =
    val out = new Array[Byte](length)
    Random.nextBytes(out)
    out

  def sha256(bytes: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(bytes)

  def sha256(parts: Array[Byte]*): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-256")
    parts.foreach(digest.update)
    digest.digest()

  def hmacSha256(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data)

  /** RFC 5869 HKDF-SHA256. An empty salt is treated as 32 zero bytes. */
  def hkdf(ikm: Array[Byte], salt: Array[Byte], info: String, length: Int): Array[Byte] =
    val actualSalt = if salt.isEmpty then new Array[Byte](32) else salt
    val prk        = hmacSha256(actualSalt, ikm)
    val infoBytes  = info.getBytes(Utf8)
    val out        = new ByteArrayOutputStream()
    var previous   = Array.emptyByteArray
    var counter    = 1
    while out.size() < length do
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(new SecretKeySpec(prk, "HmacSHA256"))
      mac.update(previous)
      mac.update(infoBytes)
      mac.update(counter.toByte)
      previous = mac.doFinal()
      out.write(previous)
      counter += 1
    out.toByteArray.take(length)

  def ed25519KeyPair(): KeyPair =
    KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

  def x25519KeyPair(): KeyPair =
    KeyPairGenerator.getInstance("X25519").generateKeyPair()

  def publicBytes(key: PublicKey): Array[Byte] = key.getEncoded
  def privateBytes(key: PrivateKey): Array[Byte] = key.getEncoded

  def ed25519Public(bytes: Array[Byte]): PublicKey =
    KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes))

  def ed25519Private(bytes: Array[Byte]): PrivateKey =
    KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes))

  def x25519Public(bytes: Array[Byte]): PublicKey =
    KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(bytes))

  def x25519Private(bytes: Array[Byte]): PrivateKey =
    KeyFactory.getInstance("X25519").generatePrivate(new PKCS8EncodedKeySpec(bytes))

  def sign(privateKey: PrivateKey, data: Array[Byte]): Array[Byte] =
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(privateKey)
    signer.update(data)
    signer.sign()

  def verify(publicKey: PublicKey, data: Array[Byte], signature: Array[Byte]): Boolean =
    val verifier = Signature.getInstance("Ed25519")
    verifier.initVerify(publicKey)
    verifier.update(data)
    verifier.verify(signature)

  def agree(privateKey: PrivateKey, publicKey: PublicKey): Array[Byte] =
    val agreement = KeyAgreement.getInstance("X25519")
    agreement.init(privateKey)
    agreement.doPhase(publicKey, true)
    agreement.generateSecret()

  def aeadEncrypt(key: Array[Byte], nonce: Array[Byte], aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce))
    cipher.updateAAD(aad)
    cipher.doFinal(plaintext)

  def aeadDecrypt(key: Array[Byte], nonce: Array[Byte], aad: Array[Byte], ciphertext: Array[Byte]): Array[Byte] =
    val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce))
    cipher.updateAAD(aad)
    cipher.doFinal(ciphertext)

  def hex(bytes: Array[Byte]): String =
    bytes.map(byte => f"${byte & 0xff}%02x").mkString

  def base64(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)

  def unbase64(text: String): Array[Byte] =
    Base64.getDecoder.decode(text.trim)

  def seqBytes(seq: Long): Array[Byte] =
    val buffer = java.nio.ByteBuffer.allocate(8)
    buffer.putLong(seq)
    buffer.array()
