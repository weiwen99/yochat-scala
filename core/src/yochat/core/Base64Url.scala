package yochat.core

import java.util.Base64

import scodec.bits.ByteVector

private[core] object Base64Url:

  private val MaxEncodedChars: Int = 1_500_000

  def encode(value: ByteVector): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(value.toArray)

  def decode(value: String): Either[CoreError, ByteVector] =
    val trimmed = value.trim
    if trimmed.isEmpty || trimmed.length > MaxEncodedChars then Left(CoreError.InvalidEncoding)
    else
      try
        val raw = ByteVector.view(Base64.getUrlDecoder.decode(trimmed))
        if encode(raw) == trimmed then Right(raw) else Left(CoreError.InvalidEncoding)
      catch case _: IllegalArgumentException => Left(CoreError.InvalidEncoding)
