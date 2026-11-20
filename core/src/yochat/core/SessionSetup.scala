package yochat.core

/** ADR-0006 section 3.5 initial ratchet. The responder precomputes its first sending key (`B_ratchet1`) before the first reply is sent. */
object SessionSetup:

  def initiator(
    keys: SessionKeys,
    peerUserPub: Ed25519Public,
    peerDevicePub: Ed25519Public,
    localRatchet: X25519KeyPair,
    peerRatchet: X25519Public
  ): Either[CoreError, RatchetState] =
    for
      dh             <- Crypto.agree(localRatchet.privateKey, peerRatchet)
      (root, sendCk) <- Crypto.kdfRoot(keys.rootKey, dh)
    yield RatchetState(
      sessionId = keys.sessionId,
      peerUserPub = peerUserPub,
      peerDevicePub = peerDevicePub,
      rootKey = root,
      rekeyBase = keys.rekeyBase,
      rekeyEpoch = 0L,
      lastRekeyEpoch = 0L,
      dhSelf = localRatchet,
      dhPeer = Some(peerRatchet),
      ckSend = Some(sendCk),
      ckRecv = None,
      ns = 0L,
      nr = 0L,
      pn = 0L,
      skipped = Map.empty,
      retiredDh = Vector.empty,
      rekey = None,
      rekeyFailures = 0,
      phase = SessionPhase.Active,
      safetyVerified = false,
      rekeyAttempts = 0,
      rekeyCooldownUntilMs = 0L
    )

  def responder(
    keys: SessionKeys,
    peerUserPub: Ed25519Public,
    peerDevicePub: Ed25519Public,
    localRatchet0: X25519KeyPair,
    peerRatchet0: X25519Public,
    localRatchet1: X25519KeyPair
  ): Either[CoreError, RatchetState] =
    for
      dh0             <- Crypto.agree(localRatchet0.privateKey, peerRatchet0)
      (root1, recvCk) <- Crypto.kdfRoot(keys.rootKey, dh0)
      dh1             <- Crypto.agree(localRatchet1.privateKey, peerRatchet0)
      (root2, sendCk) <- Crypto.kdfRoot(root1, dh1)
    yield RatchetState(
      sessionId = keys.sessionId,
      peerUserPub = peerUserPub,
      peerDevicePub = peerDevicePub,
      rootKey = root2,
      rekeyBase = keys.rekeyBase,
      rekeyEpoch = 0L,
      lastRekeyEpoch = 0L,
      dhSelf = localRatchet1,
      dhPeer = Some(peerRatchet0),
      ckSend = Some(sendCk),
      ckRecv = Some(recvCk),
      ns = 0L,
      nr = 0L,
      pn = 0L,
      skipped = Map.empty,
      retiredDh = Vector.empty,
      rekey = None,
      rekeyFailures = 0,
      phase = SessionPhase.Active,
      safetyVerified = false,
      rekeyAttempts = 0,
      rekeyCooldownUntilMs = 0L
    )
