package yochat.core

import scodec.bits.ByteVector

final case class ReceiveResult(state: RatchetState, plaintext: ByteVector, header: MessageHeader):

  override def toString: String = s"ReceiveResult(kind=${header.kind}, phase=${state.phase})"
