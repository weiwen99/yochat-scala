package yochat.core

/** ADR-0006 rekey protocol. Old-session state becomes read-only after `complete`; the caller persists the pending new session separately. */
object Rekey:

  private val RootInfo      = Crypto.ascii("yochat-s1-rekey-root")
  private val SessionInfo   = Crypto.ascii("yochat-s1-rekey-session")
  private val RekeyBaseInfo = Crypto.ascii("yochat-s1-rekey-base")

  def start(
    state: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    r1: X25519KeyPair,
    id: RekeyId,
    nonce: Nonce,
    nowMs: Long
  ): Either[CoreError, RekeyStart] =
    if state.phase != SessionPhase.Active then Left(CoreError.InvalidState)
    else if state.rekeyAttempts >= S1Limits.MaxRekeyAttempts then Left(CoreError.RekeyFailed)
    else if nowMs < state.rekeyCooldownUntilMs then Left(CoreError.RekeyFailed)
    else
      state.rekey match
        case Some(_) => Left(CoreError.RekeyPending)
        case None    =>
          val next = nextEpoch(state.rekeyEpoch, state.lastRekeyEpoch)
          if next == Long.MaxValue then Left(CoreError.InvalidState)
          else
            for
              request <- RekeyRequest.create(
                           state.sessionId,
                           next,
                           id,
                           r1.publicKey,
                           localDevicePub,
                           devicePrivate,
                           nonce,
                           state.rekeyBase
                         )
              pending  = RekeyState(RekeyRole.Initiator, id, next, RekeyPhase.Requested, Some(r1), None, None)
            yield RekeyStart(
              state.copy(
                rekeyEpoch = next + 1L,
                rekeyAttempts = state.rekeyAttempts + 1,
                rekey = Some(pending)
              ),
              request
            )

  def abort(state: RatchetState, nowMs: Long): Either[CoreError, RatchetState] =
    state.rekey match
      case None    => Left(CoreError.RekeyPending)
      case Some(_) =>
        val cooldownUntil =
          if nowMs > Long.MaxValue - S1Limits.RekeyCooldownMillis then Long.MaxValue
          else nowMs + S1Limits.RekeyCooldownMillis
        Right(state.copy(rekey = None, phase = SessionPhase.Active, rekeyCooldownUntilMs = cooldownUntil))

  def respond(
    state: RatchetState,
    request: RekeyRequest,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    r2: X25519KeyPair,
    r2Next: X25519KeyPair,
    nonce: Nonce
  ): Either[CoreError, RekeyRespond] =
    if request.sessionId != state.sessionId then Left(CoreError.UnknownSession)
    else if request.devicePub != state.peerDevicePub then Left(CoreError.InvalidSignature)
    else
      for
        _      <- request.verify(state.rekeyBase)
        result <- state.rekey match
                    case Some(pending) => duplicateAccept(state, pending, request)
                    case None          =>
                      if request.epoch <= state.lastRekeyEpoch then Left(CoreError.Replay)
                      else
                        for
                          _          <- KeySeparation.distinct(r2.publicKey.bytes, r2Next.publicKey.bytes)
                          _          <- KeySeparation.distinct(r2.publicKey.bytes, request.r1Pub.bytes)
                          _          <- KeySeparation.distinct(r2Next.publicKey.bytes, request.r1Pub.bytes)
                          shared     <- Crypto.agree(r2.privateKey, request.r1Pub)
                          newSession <- deriveResponder(state, request, r2, r2Next, shared)
                          accept     <- RekeyAccept.create(
                                          state.sessionId,
                                          request.epoch,
                                          request.id,
                                          request.r1Pub,
                                          r2.publicKey,
                                          localDevicePub,
                                          devicePrivate,
                                          nonce,
                                          state.rekeyBase
                                        )
                          pending     = RekeyState(
                                          RekeyRole.Responder,
                                          request.id,
                                          request.epoch,
                                          RekeyPhase.Accepted,
                                          Some(r2),
                                          Some(newSession.sessionId),
                                          Some(accept.canonical)
                                        )
                          advanced    = state.copy(
                                          lastRekeyEpoch = request.epoch,
                                          phase = SessionPhase.ReadOnly,
                                          rekeyEpoch = nextEpoch(state.rekeyEpoch, request.epoch),
                                          rekey = Some(pending)
                                        )
                        yield RekeyRespond(
                          advanced,
                          accept,
                          Some(
                            newSession.copy(
                              phase = SessionPhase.Pending,
                              safetyVerified = state.safetyVerified,
                              rekeyAttempts = 0,
                              rekeyCooldownUntilMs = 0L
                            )
                          )
                        )
      yield result

  def complete(state: RatchetState, accept: RekeyAccept): Either[CoreError, RekeyComplete] =
    state.rekey match
      case Some(pending) if pending.role == RekeyRole.Initiator && pending.phase == RekeyPhase.Requested && pending.newSessionId.isEmpty =>
        pending.localRatchetKey match
          case None     => Left(CoreError.InvalidState)
          case Some(r1) =>
            if accept.sessionId != state.sessionId then Left(CoreError.UnknownSession)
            else if accept.devicePub != state.peerDevicePub then Left(CoreError.InvalidSignature)
            else if accept.epoch != pending.epoch || accept.id != pending.id || accept.r1Pub != r1.publicKey then Left(CoreError.RekeyPending)
            else
              for
                _          <- accept.verify(state.rekeyBase)
                shared     <- Crypto.agree(r1.privateKey, accept.r2Pub)
                newSession <- deriveInitiator(state, pending, r1, accept.r2Pub, shared)
                accepted    = state.copy(
                                phase = SessionPhase.ReadOnly,
                                rekey = Some(pending.copy(phase = RekeyPhase.Accepted, newSessionId = Some(newSession.sessionId)))
                              )
              yield RekeyComplete(
                accepted,
                newSession.copy(
                  phase = SessionPhase.Pending,
                  safetyVerified = state.safetyVerified,
                  rekeyAttempts = 0,
                  rekeyCooldownUntilMs = 0L
                )
              )
      case _                                                                                                                             => Left(CoreError.RekeyPending)

  def clearTerminal(state: RatchetState): Either[CoreError, RatchetState] =
    if state.phase != SessionPhase.ReadOnly then Left(CoreError.InvalidState)
    else Right(state.copy(rekey = None))

  def confirm(
    newSession: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    control: ControlExtension,
    nonce: Nonce
  ): Either[CoreError, RekeyConfirmResult] =
    if newSession.phase != SessionPhase.Pending then Left(CoreError.InvalidState)
    else
      Ratchet
        .encryptControl(newSession, localDevicePub, devicePrivate, MessageKind.RekeyConfirm, control, nonce)
        .map { case (state, frame) => RekeyConfirmResult(state, frame) }

  def acceptConfirm(
    newSession: RatchetState,
    frame: WireFrame,
    expected: ControlExtension,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    nonce: Nonce,
    newDh: Option[X25519KeyPair]
  ): Either[CoreError, RekeyConfirmAckResult] =
    if newSession.phase != SessionPhase.Pending && newSession.phase != SessionPhase.Active then Left(CoreError.InvalidState)
    else if frame.header.kind != MessageKind.RekeyConfirm || !frame.header.control.contains(expected) then Left(CoreError.InvalidState)
    else
      Ratchet.decrypt(newSession, frame, false, newDh) match
        case Right(decoded)                          =>
          for ack <- Ratchet.encryptControl(decoded.state, localDevicePub, devicePrivate, MessageKind.RekeyConfirmAck, expected, nonce)
          yield RekeyConfirmAckResult(ack._1.copy(phase = SessionPhase.Active), ack._2)
        case Left(ReceiveError(_, CoreError.Replay)) =>
          for ack <- Ratchet.encryptControl(newSession, localDevicePub, devicePrivate, MessageKind.RekeyConfirmAck, expected, nonce)
          yield RekeyConfirmAckResult(ack._1.copy(phase = SessionPhase.Active), ack._2)
        case Left(error)                             => Left(error.error)

  def acceptAck(
    newSession: RatchetState,
    frame: WireFrame,
    expected: ControlExtension,
    newDh: X25519KeyPair
  ): Either[CoreError, RekeyAckResult] =
    if newSession.phase != SessionPhase.Pending && newSession.phase != SessionPhase.Active then Left(CoreError.InvalidState)
    else if frame.header.kind != MessageKind.RekeyConfirmAck || !frame.header.control.contains(expected) then Left(CoreError.InvalidState)
    else
      Ratchet.decrypt(newSession, frame, false, Some(newDh)) match
        case Right(decoded)                          => Right(RekeyAckResult(decoded.state.copy(phase = SessionPhase.Active)))
        case Left(ReceiveError(_, CoreError.Replay)) => Right(RekeyAckResult(newSession.copy(phase = SessionPhase.Active)))
        case Left(error)                             => Left(error.error)

  private def duplicateAccept(state: RatchetState, pending: RekeyState, request: RekeyRequest): Either[CoreError, RekeyRespond] =
    if pending.role != RekeyRole.Responder || pending.id != request.id || pending.epoch != request.epoch then Left(CoreError.RekeyPending)
    else
      pending.cachedAccept match
        case None        => Left(CoreError.RekeyPending)
        case Some(bytes) =>
          for
            accept <- RekeyAccept.fromBytes(bytes)
            _      <- accept.verify(state.rekeyBase)
          yield RekeyRespond(state, accept, None)

  private def deriveInitiator(
    state: RatchetState,
    pending: RekeyState,
    r1: X25519KeyPair,
    r2Pub: X25519Public,
    shared: Key32
  ): Either[CoreError, RatchetState] =
    for
      keys  <- newSessionKeys(state, pending.epoch, pending.id, r1.publicKey, r2Pub, shared)
      value <- SessionSetup.initiator(keys, state.peerUserPub, state.peerDevicePub, r1, r2Pub)
    yield value

  private def deriveResponder(
    state: RatchetState,
    request: RekeyRequest,
    r2: X25519KeyPair,
    r2Next: X25519KeyPair,
    shared: Key32
  ): Either[CoreError, RatchetState] =
    for
      keys  <- newSessionKeys(state, request.epoch, request.id, request.r1Pub, r2.publicKey, shared)
      value <- SessionSetup.responder(keys, state.peerUserPub, state.peerDevicePub, r2, request.r1Pub, r2Next)
    yield value

  private def newSessionKeys(
    state: RatchetState,
    epoch: Long,
    id: RekeyId,
    r1Pub: X25519Public,
    r2Pub: X25519Public,
    shared: Key32
  ): Either[CoreError, SessionKeys] =
    val epochBytes = CanonicalWriter().u64(epoch).result
    for
      sessionRaw <- SessionId.from(
                      Crypto.sha256(SessionInfo, state.sessionId.bytes, epochBytes, id.bytes, r1Pub.bytes, r2Pub.bytes).bytes
                    )
      salt        = Crypto.sha256(state.rekeyBase.bytes, epochBytes, id.bytes)
      rootKey    <- Crypto.hkdf32(shared.bytes, salt.bytes, RootInfo)
      rekeyBase  <- Crypto.hkdf32(shared.bytes, sessionRaw.bytes, RekeyBaseInfo)
    yield SessionKeys(sessionRaw, rootKey, rekeyBase)

  private def nextEpoch(local: Long, lastAccepted: Long): Long =
    val afterLast = if lastAccepted == Long.MaxValue then Long.MaxValue else lastAccepted + 1L
    math.max(local, afterLast)
