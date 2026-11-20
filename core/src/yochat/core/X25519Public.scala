package yochat.core

import scodec.bits.ByteVector

opaque type X25519Public = ByteVector

object X25519Public:

  val Length: Int = 32

  def from(value: ByteVector): Either[CoreError, X25519Public] = FixedBytes.check("x25519_public", Length, value)

  private[core] def trust(value: ByteVector): X25519Public = value

  extension (value: X25519Public) def bytes: ByteVector = value
