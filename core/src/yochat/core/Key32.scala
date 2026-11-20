package yochat.core

import scodec.bits.ByteVector

/** A 32-byte symmetric value: root key, chain key, message key, hash, or DH output. */
opaque type Key32 = ByteVector

object Key32:

  val Length: Int = 32

  def from(value: ByteVector): Either[CoreError, Key32] = FixedBytes.check("key32", Length, value)

  private[core] def trust(value: ByteVector): Key32 = value

  extension (value: Key32) def bytes: ByteVector = value
