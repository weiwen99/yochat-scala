package yochat.core

import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer
import java.text.Normalizer

import scodec.bits.ByteVector

/** User-visible strings are NFC normalized and rejected when they are non-UTF-8 or contain control characters. */
private[core] object Text:

  val MaxFieldBytes: Int = 4096

  def normalize(value: String, field: String): Either[CoreError, String] =
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    if normalized.codePoints().anyMatch(codePoint => Character.isISOControl(codePoint)) then Left(CoreError.InvalidEncoding)
    else
      encode(normalized).flatMap { raw =>
        if raw.size > MaxFieldBytes.toLong then Left(CoreError.InvalidLength(field, MaxFieldBytes, raw.size))
        else Right(normalized)
      }

  def encode(value: String): Either[CoreError, ByteVector] =
    val encoder = StandardCharsets.UTF_8
      .newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try
      val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
      val raw    = new Array[Byte](buffer.remaining())
      buffer.get(raw)
      Right(ByteVector.view(raw))
    catch case _: CharacterCodingException => Left(CoreError.InvalidEncoding)

  def canonical(value: String): Either[CoreError, ByteVector] = encode(value)

  def decode(raw: ByteVector): Either[CoreError, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(raw.toArray)).toString)
    catch case _: CharacterCodingException => Left(CoreError.InvalidEncoding)
