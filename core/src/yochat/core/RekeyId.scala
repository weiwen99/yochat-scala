package yochat.core

import scodec.bits.ByteVector

opaque type RekeyId = ByteVector

object RekeyId:

  val Length: Int = 16

  def from(value: ByteVector): Either[CoreError, RekeyId] = FixedBytes.check("rekey_id", Length, value)

  private[core] def trust(value: ByteVector): RekeyId = value

  extension (value: RekeyId) def bytes: ByteVector = value
