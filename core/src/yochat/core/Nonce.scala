package yochat.core

import scodec.bits.ByteVector

opaque type Nonce = ByteVector

object Nonce:

  val Length: Int = 12

  def from(value: ByteVector): Either[CoreError, Nonce] = FixedBytes.check("nonce", Length, value)

  private[core] def trust(value: ByteVector): Nonce = value

  extension (value: Nonce) def bytes: ByteVector = value
