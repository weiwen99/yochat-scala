package yochat.core

import scodec.bits.ByteVector

/** Length validation shared by the opaque byte types. The name is a field label, never a secret. */
private[core] object FixedBytes:

  def check(field: String, expected: Int, value: ByteVector): Either[CoreError, ByteVector] =
    if value.size == expected.toLong then Right(value)
    else Left(CoreError.InvalidLength(field, expected, value.size))
