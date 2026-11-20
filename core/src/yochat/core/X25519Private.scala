package yochat.core

import scodec.bits.ByteVector

opaque type X25519Private = ByteVector

object X25519Private:

  val Length: Int = 32

  def from(value: ByteVector): Either[CoreError, X25519Private] = FixedBytes.check("x25519_private", Length, value)

  private[core] def trust(value: ByteVector): X25519Private = value

  extension (value: X25519Private) def bytes: ByteVector = value
