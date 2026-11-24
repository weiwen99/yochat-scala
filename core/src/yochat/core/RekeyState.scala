package yochat.core

import scodec.bits.ByteVector

enum RekeyRole:
  case Initiator, Responder

enum RekeyPhase:
  case Requested, Accepted

/** Session-local rekey bookkeeping. The pending new session lives in its own `sessions` row, not inside this record. */
final case class RekeyState(
  role: RekeyRole,
  id: RekeyId,
  epoch: Long,
  phase: RekeyPhase,
  localRatchetKey: Option[X25519KeyPair],
  newSessionId: Option[SessionId],
  cachedAccept: Option[ByteVector],
  deadlineMs: Long = 0L,
  ackReceived: Boolean = false
):

  override def toString: String = s"RekeyState(role=$role, phase=$phase, epoch=$epoch)"
