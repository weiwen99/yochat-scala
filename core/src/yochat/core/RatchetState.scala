package yochat.core

/** All mutable-by-time state is represented by a new immutable value. Persist the candidate only after the whole operation succeeds. */
final case class RatchetState(
  sessionId: SessionId,
  peerUserPub: Ed25519Public,
  peerDevicePub: Ed25519Public,
  rootKey: Key32,
  rekeyBase: Key32,
  rekeyEpoch: Long,
  lastRekeyEpoch: Long,
  dhSelf: X25519KeyPair,
  dhPeer: Option[X25519Public],
  ckSend: Option[Key32],
  ckRecv: Option[Key32],
  ns: Long,
  nr: Long,
  pn: Long,
  skipped: Map[SkippedKey, Key32],
  retiredDh: Vector[X25519Public],
  rekey: Option[RekeyState],
  rekeyFailures: Int,
  phase: SessionPhase,
  safetyVerified: Boolean,
  rekeyAttempts: Int,
  rekeyCooldownUntilMs: Long
):

  override def toString: String = s"RatchetState(phase=$phase, rekey=${rekey.isDefined}, failures=$rekeyFailures)"
