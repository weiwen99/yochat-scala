package yochat.core

import scodec.bits.ByteVector

opaque type Signature = ByteVector

object Signature:

  val Length: Int = 64

  def from(value: ByteVector): Either[CoreError, Signature] = FixedBytes.check("signature", Length, value)

  private[core] def trust(value: ByteVector): Signature = value

  extension (value: Signature) def bytes: ByteVector = value
