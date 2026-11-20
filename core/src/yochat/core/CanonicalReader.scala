package yochat.core

import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer

import scodec.bits.ByteVector

final private[core] case class CanonicalReader private (bytes: ByteVector, offset: Long):

  def u8: Either[CoreError, (Int, CanonicalReader)] =
    read(1).map { chunk =>
      (chunk(0).toInt & 0xff, copy(offset = offset + 1))
    }

  def u32: Either[CoreError, (Long, CanonicalReader)] =
    read(4).map(chunk => (unsigned(chunk), copy(offset = offset + 4)))

  def u64: Either[CoreError, (Long, CanonicalReader)] =
    read(8).flatMap { chunk =>
      val value = unsigned(chunk)
      if value < 0 then Left(CoreError.InvalidEncoding)
      else Right((value, copy(offset = offset + 8)))
    }

  def fixed(length: Int): Either[CoreError, (ByteVector, CanonicalReader)] =
    read(length).map(chunk => (chunk, copy(offset = offset + length)))

  def bytes(maxLength: Int): Either[CoreError, (ByteVector, CanonicalReader)] =
    u32.flatMap { case (length, afterLength) =>
      if length > maxLength.toLong then Left(CoreError.InvalidEncoding)
      else afterLength.fixed(length.toInt)
    }

  def string(maxBytes: Int): Either[CoreError, (String, CanonicalReader)] =
    bytes(maxBytes).flatMap { case (raw, next) =>
      decodeUtf8(raw).map(value => (value, next))
    }

  def remaining: Long = bytes.size - offset

  def finish: Either[CoreError, Unit] =
    if remaining == 0L then Right(()) else Left(CoreError.InvalidEncoding)

  private def read(length: Int): Either[CoreError, ByteVector] =
    if length < 0 || offset + length.toLong > bytes.size then Left(CoreError.InvalidEncoding)
    else Right(bytes.slice(offset, offset + length))

  private def unsigned(chunk: ByteVector): Long =
    chunk.toArray.foldLeft(0L)((value, byte) => (value << 8) | (byte.toLong & 0xffL))

  private def decodeUtf8(raw: ByteVector): Either[CoreError, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(raw.toArray)).toString)
    catch case _: CharacterCodingException => Left(CoreError.InvalidEncoding)

private[core] object CanonicalReader:

  def apply(bytes: ByteVector): CanonicalReader = new CanonicalReader(bytes, 0L)
