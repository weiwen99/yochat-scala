package yochat.core

import scodec.bits.ByteVector

opaque type SessionId = ByteVector

object SessionId:

  val Length: Int = 32

  def from(value: ByteVector): Either[CoreError, SessionId] = FixedBytes.check("session_id", Length, value)

  private[core] def trust(value: ByteVector): SessionId = value

  extension (value: SessionId) def bytes: ByteVector = value
