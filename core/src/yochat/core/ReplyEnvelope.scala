package yochat.core

import scodec.bits.ByteVector

final case class ReplyEnvelope private (ephPub: X25519Public, nonce: Nonce, ciphertext: ByteVector, canonical: ByteVector):

  def text: String = Base64Url.encode(canonical)

object ReplyEnvelope:

  val Version: Int       = 1
  val MaxCiphertext: Int = 1_048_576

  def create(ephPub: X25519Public, nonce: Nonce, ciphertext: ByteVector): ReplyEnvelope =
    val canonical = CanonicalWriter()
      .u8(Version)
      .fixed(ephPub.bytes)
      .fixed(nonce.bytes)
      .bytes(ciphertext)
      .result
    ReplyEnvelope(ephPub, nonce, ciphertext, canonical)

  def fromText(value: String): Either[CoreError, ReplyEnvelope] =
    Base64Url.decode(value).flatMap(decode)

  def decode(value: ByteVector): Either[CoreError, ReplyEnvelope] =
    val reader = CanonicalReader(value)
    for
      (version, r1)    <- reader.u8
      _                <- expectVersion(version)
      (ephRaw, r2)     <- r1.fixed(X25519Public.Length)
      ephPub           <- X25519Public.from(ephRaw)
      (nonceRaw, r3)   <- r2.fixed(Nonce.Length)
      nonce            <- Nonce.from(nonceRaw)
      (ciphertext, r4) <- r3.bytes(MaxCiphertext)
      _                <- r4.finish
    yield ReplyEnvelope(ephPub, nonce, ciphertext, value)

  private def expectVersion(value: Int): Either[CoreError, Unit] =
    if value == Version then Right(()) else Left(CoreError.InvalidVersion(value))
