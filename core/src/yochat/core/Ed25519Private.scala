package yochat.core

import scodec.bits.ByteVector

opaque type Ed25519Private = ByteVector

object Ed25519Private:

  val Length: Int = 32

  def from(value: ByteVector): Either[CoreError, Ed25519Private] = FixedBytes.check("ed25519_private", Length, value)

  private[core] def trust(value: ByteVector): Ed25519Private = value

  extension (value: Ed25519Private) def bytes: ByteVector = value
