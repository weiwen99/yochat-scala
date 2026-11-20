package yochat.core

import scodec.bits.ByteVector

final case class WireFrame(header: MessageHeader, ciphertext: ByteVector, signature: Signature):

  def bytes: ByteVector = header.canonical ++ ciphertext ++ signature.bytes

object WireFrame:

  val MinCiphertext: Int = 16
  val MaxCiphertext: Int = 1_048_576

  def create(header: MessageHeader, ciphertext: ByteVector, signature: Signature): Either[CoreError, WireFrame] =
    if ciphertext.size < MinCiphertext || ciphertext.size > MaxCiphertext then
      Left(CoreError.InvalidLength("ciphertext", MaxCiphertext, ciphertext.size))
    else Right(WireFrame(header, ciphertext, signature))

  def parse(value: ByteVector): Either[CoreError, WireFrame] =
    for
      (header, reader)   <- MessageHeader.read(CanonicalReader(value))
      remaining           = reader.remaining
      _                  <- if remaining < (Signature.Length + MinCiphertext).toLong then Left(CoreError.InvalidEncoding) else Right(())
      ciphertextLength    = remaining - Signature.Length
      _                  <- if ciphertextLength > MaxCiphertext.toLong then Left(CoreError.InvalidLength("ciphertext", MaxCiphertext, ciphertextLength))
                            else Right(())
      (ciphertext, r2)   <- reader.fixed(ciphertextLength.toInt)
      (signatureRaw, r3) <- r2.fixed(Signature.Length)
      signature          <- Signature.from(signatureRaw)
      _                  <- r3.finish
    yield WireFrame(header, ciphertext, signature)
