package yochat.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.annotation.tailrec

import scodec.bits.ByteVector

/** S1 primitives and the two KDFs fixed by ADR-0006. This object owns no session state. */
object Crypto:

  private val Utf8 = StandardCharsets.UTF_8

  private[core] val InfoRk        = ascii("yochat-s1-rk")
  private[core] val InfoRekeyBase = ascii("yochat-s1-rekey-base")
  private[core] val InfoReply     = ascii("yochat-s1-reply")
  private[core] val InfoRoot      = ascii("yochat-s1-root")

  def text(value: String): ByteVector = ByteVector.view(value.getBytes(Utf8))

  def ascii(value: String): ByteVector = ByteVector.view(value.getBytes(StandardCharsets.US_ASCII))

  def sha256(parts: ByteVector*): Key32 = Jce.sha256(parts*)

  def hmacSha256(key: ByteVector, data: ByteVector): Key32 = Jce.hmacSha256(key, data)

  /** RFC 5869 HKDF-SHA256. Empty salt means HashLen zero bytes. */
  def hkdf(ikm: ByteVector, salt: ByteVector, info: ByteVector, length: Int): Either[CoreError, ByteVector] =
    if length < 0 || length > 255 * 32 then Left(CoreError.InvalidLength("hkdf_output", 255 * 32, length.toLong))
    else
      val actualSalt = if salt.isEmpty then ByteVector.fill(32)(0.toByte) else salt
      val prk        = Jce.hmacSha256(actualSalt, ikm)
      val out        = new ByteArrayOutputStream(length)
      val blocks     = (length + 31) / 32

      @tailrec
      def expand(counter: Int, previous: ByteVector, remaining: Int): Unit =
        if counter <= blocks then
          val block = Jce.hmacSha256(prk.bytes, previous ++ info ++ ByteVector(counter.toByte)).bytes
          val write = math.min(block.size, remaining.toLong).toInt
          out.write(block.toArray, 0, write)
          expand(counter + 1, block, remaining - write)

      expand(1, ByteVector.empty, length)
      Right(ByteVector.view(out.toByteArray))

  def hkdf32(ikm: ByteVector, salt: ByteVector, info: ByteVector): Either[CoreError, Key32] =
    hkdf(ikm, salt, info, 32).flatMap(Key32.from)

  def hkdf64(ikm: ByteVector, salt: ByteVector, info: ByteVector): Either[CoreError, (Key32, Key32)] =
    hkdf(ikm, salt, info, 64).flatMap { bytes =>
      for
        first  <- Key32.from(bytes.take(32))
        second <- Key32.from(bytes.drop(32))
      yield (first, second)
    }

  /** ADR-0006 KDF_RK. Returns (new root, new chain). */
  def kdfRoot(rootKey: Key32, dhOutput: Key32): Either[CoreError, (Key32, Key32)] =
    hkdf64(dhOutput.bytes, rootKey.bytes, InfoRk)

  /** ADR-0006 KDF_CK. Returns (next chain key, message key). */
  def kdfChain(chainKey: Key32): (Key32, Key32) =
    val next    = Jce.hmacSha256(chainKey.bytes, ByteVector(0x02.toByte))
    val message = Jce.hmacSha256(chainKey.bytes, ByteVector(0x01.toByte))
    (next, message)

  def aeadSeal(key: Key32, nonce: Nonce, aad: ByteVector, plaintext: ByteVector): Either[CoreError, ByteVector] =
    Jce.aeadSeal(key, nonce, aad, plaintext)

  def aeadOpen(key: Key32, nonce: Nonce, aad: ByteVector, ciphertext: ByteVector): Either[CoreError, ByteVector] =
    Jce.aeadOpen(key, nonce, aad, ciphertext)

  def sign(privateKey: Ed25519Private, data: ByteVector): Either[CoreError, Signature] = Jce.sign(privateKey, data)

  def verify(publicKey: Ed25519Public, data: ByteVector, signature: Signature): Boolean =
    Jce.verify(publicKey, data, signature)

  def agree(privateKey: X25519Private, publicKey: X25519Public): Either[CoreError, Key32] =
    Jce.agree(privateKey, publicKey)

  def constantTimeEqual(left: ByteVector, right: ByteVector): Boolean = MessageDigest.isEqual(left.toArray, right.toArray)
