package yochat.core

import scodec.bits.ByteVector

opaque type Ed25519Public = ByteVector

object Ed25519Public:

  val Length: Int = 32

  def from(value: ByteVector): Either[CoreError, Ed25519Public] = FixedBytes.check("ed25519_public", Length, value)

  private[core] def trust(value: ByteVector): Ed25519Public = value

  extension (value: Ed25519Public) def bytes: ByteVector = value
