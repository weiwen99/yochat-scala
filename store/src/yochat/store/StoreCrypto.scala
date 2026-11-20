package yochat.store

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.{CoreError, Crypto, Key32, Nonce}

/** Application-layer envelopes and the ADR-0007 AAD domains. All fields are fixed length. */
private[store] object StoreCrypto:

  private val EnvelopeMagic: ByteVector = Crypto.ascii("YCE1")
  private val Random                    = new SecureRandom()

  val NonceLength: Int = 12
  val TagLength: Int   = 16

  def randomNonce: IO[Nonce] =
    IO.delay(randomNonceUnsafe())

  def randomNonceUnsafe(): Nonce =
    val bytes = new Array[Byte](NonceLength)
    Random.nextBytes(bytes)
    Nonce.from(ByteVector.view(bytes)).fold(_ => throw new IllegalStateException("nonce length"), identity)

  def seal(key: Key32, nonce: Nonce, aad: ByteVector, plaintext: ByteVector): Either[StorageError, Array[Byte]] =
    Crypto.aeadSeal(key, nonce, aad, plaintext) match
      case Left(_)       => Left(StorageError.StorageFailure)
      case Right(cipher) => Right((EnvelopeMagic ++ nonce.bytes ++ cipher).toArray)

  def open(key: Key32, aad: ByteVector, envelope: Array[Byte]): Either[StorageError, ByteVector] =
    val value = ByteVector.view(envelope)
    if value.size < (EnvelopeMagic.size + NonceLength + TagLength).toLong then Left(StorageError.InvalidEnvelope)
    else if !value.take(EnvelopeMagic.size).equals(EnvelopeMagic) then Left(StorageError.InvalidEnvelope)
    else
      for
        nonce <- Nonce.from(value.slice(EnvelopeMagic.size, EnvelopeMagic.size + NonceLength)).left.map(_ => StorageError.InvalidEnvelope)
        plain <- Crypto.aeadOpen(key, nonce, aad, value.drop(EnvelopeMagic.size + NonceLength)) match
                   case Right(bytes)                      => Right(bytes)
                   case Left(CoreError.InvalidCiphertext) => Left(StorageError.InvalidCiphertext)
                   case Left(_)                           => Left(StorageError.InvalidEnvelope)
      yield plain

  def u64(value: Long): ByteVector =
    ByteVector.view(ByteBuffer.allocate(8).putLong(value).array())

  def aad(domain: String, fields: ByteVector*): ByteVector =
    val bytes = new ByteArrayOutputStream()
    bytes.write(Crypto.ascii(domain).toArray)
    bytes.write(0)
    fields.foreach(field => bytes.write(field.toArray))
    ByteVector.view(bytes.toByteArray)

  def stateAad(
    sessionId: ByteVector,
    peerUserPub: ByteVector,
    peerDevicePub: ByteVector,
    generation: Long,
    version: Long
  ): ByteVector =
    aad(
      "yochat-store/session-state/v1",
      sessionId,
      peerUserPub,
      peerDevicePub,
      u64(generation),
      u64(version)
    )

  def messageAad(
    conversationId: ByteVector,
    sessionId: ByteVector,
    authorPub: ByteVector,
    devicePub: ByteVector,
    seq: Long,
    wireHash: ByteVector
  ): ByteVector =
    aad(
      "yochat-store/message-body/v1",
      conversationId,
      sessionId,
      authorPub,
      devicePub,
      u64(seq),
      wireHash
    )

  def contactAad(userPub: ByteVector, version: Long): ByteVector =
    aad("yochat-store/contact/v1", userPub, u64(version))

  def invitationAad(invitationId: ByteVector, version: Long): ByteVector =
    aad("yochat-store/invitation/v1", invitationId, u64(version))

  def wireHash(wire: ByteVector): ByteVector = Crypto.sha256(wire).bytes
