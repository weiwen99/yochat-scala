package yochat.core

import scodec.bits.ByteVector

/** Pure ADR-0006 data path. The caller persists the returned state before sending or acknowledging the frame. */
object Ratchet:

  private val MessageDomain = Crypto.ascii("yochat-s1-msg")

  def encryptUser(
    state: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    seq: Long,
    nonce: Nonce,
    plaintext: ByteVector
  ): Either[CoreError, (RatchetState, WireFrame)] =
    if !state.safetyVerified then Left(CoreError.InvalidState)
    else encrypt(state, localDevicePub, devicePrivate, seq, nonce, plaintext)

  def markSafetyVerified(state: RatchetState): RatchetState = state.copy(safetyVerified = true)

  private[core] def encrypt(
    state: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    seq: Long,
    nonce: Nonce,
    plaintext: ByteVector
  ): Either[CoreError, (RatchetState, WireFrame)] =
    if state.phase != SessionPhase.Active then Left(CoreError.InvalidState)
    else if seq <= 0L then Left(CoreError.InvalidState)
    else if state.ns == Long.MaxValue then Left(CoreError.InvalidState)
    else
      state.ckSend match
        case None           => Left(CoreError.RekeyRequired)
        case Some(chainKey) =>
          val (nextChain, messageKey) = Crypto.kdfChain(chainKey)
          val header                  = MessageHeader(
            MessageHeader.Version,
            MessageKind.Data,
            state.sessionId,
            localDevicePub,
            state.dhSelf.publicKey,
            state.pn,
            state.ns,
            seq,
            nonce,
            None
          )
          for
            ciphertext <- Crypto.aeadSeal(messageKey, nonce, header.canonical, plaintext)
            signature  <- Crypto.sign(devicePrivate, MessageDomain ++ header.canonical ++ ciphertext)
            frame      <- WireFrame.create(header, ciphertext, signature)
          yield (state.copy(ckSend = Some(nextChain), ns = state.ns + 1L), frame)

  def encryptControl(
    state: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    kind: MessageKind,
    control: ControlExtension,
    nonce: Nonce
  ): Either[CoreError, (RatchetState, WireFrame)] =
    encryptControlFrame(state, localDevicePub, devicePrivate, kind, 0L, Some(control), nonce)

  def encryptDataAck(
    state: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    ackedSeq: Long,
    nonce: Nonce
  ): Either[CoreError, (RatchetState, WireFrame)] =
    encryptControlFrame(state, localDevicePub, devicePrivate, MessageKind.DataAck, ackedSeq, None, nonce)

  def encryptGap(
    state: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    expectedSeq: Long,
    nonce: Nonce
  ): Either[CoreError, (RatchetState, WireFrame)] =
    encryptControlFrame(state, localDevicePub, devicePrivate, MessageKind.Gap, expectedSeq, None, nonce)

  private def encryptControlFrame(
    state: RatchetState,
    localDevicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    kind: MessageKind,
    seq: Long,
    control: Option[ControlExtension],
    nonce: Nonce
  ): Either[CoreError, (RatchetState, WireFrame)] =
    val validShape =
      kind match
        case MessageKind.DataAck | MessageKind.Gap                  => seq > 0L && control.isEmpty
        case MessageKind.RekeyConfirm | MessageKind.RekeyConfirmAck =>
          seq == 0L && control.exists(_.rekeyEpoch >= 0L)
        case MessageKind.Data                                       => false
    val validPhase =
      kind match
        case MessageKind.DataAck | MessageKind.Gap                  => state.phase == SessionPhase.Active
        case MessageKind.RekeyConfirm | MessageKind.RekeyConfirmAck => state.phase != SessionPhase.ReadOnly
        case MessageKind.Data                                       => false
    if !validShape || !validPhase then Left(CoreError.InvalidState)
    else if state.ns == Long.MaxValue then Left(CoreError.InvalidState)
    else
      state.ckSend match
        case None           => Left(CoreError.RekeyRequired)
        case Some(chainKey) =>
          val (nextChain, messageKey) = Crypto.kdfChain(chainKey)
          val header                  = MessageHeader(
            MessageHeader.Version,
            kind,
            state.sessionId,
            localDevicePub,
            state.dhSelf.publicKey,
            state.pn,
            state.ns,
            seq,
            nonce,
            control
          )
          for
            ciphertext <- Crypto.aeadSeal(messageKey, nonce, header.canonical, ByteVector.empty)
            signature  <- Crypto.sign(devicePrivate, MessageDomain ++ header.canonical ++ ciphertext)
            frame      <- WireFrame.create(header, ciphertext, signature)
          yield (state.copy(ckSend = Some(nextChain), ns = state.ns + 1L), frame)

  def decrypt(
    state: RatchetState,
    frame: WireFrame,
    seqSeen: Boolean,
    newDh: Option[X25519KeyPair]
  ): Either[ReceiveError, ReceiveResult] =
    val header = frame.header
    if header.version != MessageHeader.Version then reject(state, CoreError.InvalidVersion(header.version))
    else if header.sessionId != state.sessionId then reject(state, CoreError.UnknownSession)
    else if header.devicePub != state.peerDevicePub then reject(state, CoreError.InvalidSignature)
    else if !Crypto.verify(state.peerDevicePub, MessageDomain ++ header.canonical ++ frame.ciphertext, frame.signature) then
      reject(state, CoreError.InvalidSignature)
    else if !validHeaderShape(header) then reject(state, CoreError.InvalidState)
    else if header.kind == MessageKind.Data && !state.safetyVerified then reject(state, CoreError.InvalidState)
    else if frame.ciphertext.size < WireFrame.MinCiphertext.toLong || frame.ciphertext.size > WireFrame.MaxCiphertext.toLong then
      reject(state, CoreError.InvalidLength("ciphertext", WireFrame.MaxCiphertext, frame.ciphertext.size))
    else if header.kind == MessageKind.Data && seqSeen then reject(state, CoreError.DuplicateSeq)
    else
      val skippedKey = SkippedKey(header.dhPub, header.n)
      state.skipped.get(skippedKey) match
        case Some(messageKey) =>
          Crypto.aeadOpen(messageKey, header.nonce, header.canonical, frame.ciphertext) match
            case Left(error)      => reject(state, error)
            case Right(plaintext) =>
              validateControlPayload(header, plaintext) match
                case Left(error) => reject(state, error)
                case Right(())   =>
                  Right(ReceiveResult(state.copy(skipped = state.skipped - skippedKey, rekeyFailures = 0), plaintext, header))
        case None             =>
          val current = state.dhPeer.contains(header.dhPub)
          if !current && state.retiredDh.contains(header.dhPub) then reject(state, CoreError.StaleRatchet)
          else if current then receiveCurrent(state, frame, countFailure = header.kind == MessageKind.Data)
          else receiveNewRatchet(state, frame, newDh)

  private def receiveCurrent(state: RatchetState, frame: WireFrame, countFailure: Boolean): Either[ReceiveError, ReceiveResult] =
    val header = frame.header
    if header.n < state.nr then reject(state, CoreError.Replay)
    else
      skipKeys(state, header.n) match
        case Left(error)         => reject(state, error)
        case Right(skippedState) =>
          skippedState.ckRecv match
            case None           => reject(state, CoreError.RekeyRequired)
            case Some(chainKey) =>
              val (nextChain, messageKey) = Crypto.kdfChain(chainKey)
              val candidate               = skippedState.copy(ckRecv = Some(nextChain), nr = header.n + 1L)
              Crypto.aeadOpen(messageKey, header.nonce, header.canonical, frame.ciphertext) match
                case Right(plaintext)                                  =>
                  validateControlPayload(header, plaintext) match
                    case Left(error) => reject(state, error)
                    case Right(())   => Right(ReceiveResult(candidate.copy(rekeyFailures = 0), plaintext, header))
                case Left(CoreError.InvalidCiphertext) if countFailure =>
                  val failures = math.min(state.rekeyFailures + 1, S1Limits.MaxRekeyFailures)
                  val error    = if failures >= S1Limits.MaxRekeyFailures then CoreError.RekeyRequired else CoreError.InvalidCiphertext
                  reject(state.copy(rekeyFailures = failures), error)
                case Left(error)                                       => reject(state, error)

  private def receiveNewRatchet(
    state: RatchetState,
    frame: WireFrame,
    newDh: Option[X25519KeyPair]
  ): Either[ReceiveError, ReceiveResult] =
    val header = frame.header
    newDh match
      case None                                               => reject(state, CoreError.RekeyRequired)
      case Some(localDh) if localDh.publicKey == header.dhPub => reject(state, CoreError.InvalidState)
      case Some(localDh)                                      =>
        for
          skippedOld         <- skipKeys(state, header.pn).left.map(error => ReceiveError(state, error))
          receiveDh          <- Crypto.agree(state.dhSelf.privateKey, header.dhPub).left.map(error => ReceiveError(state, error))
          (root1, recvChain) <- Crypto.kdfRoot(state.rootKey, receiveDh).left.map(error => ReceiveError(state, error))
          sendDh             <- Crypto.agree(localDh.privateKey, header.dhPub).left.map(error => ReceiveError(state, error))
          (root2, sendChain) <- Crypto.kdfRoot(root1, sendDh).left.map(error => ReceiveError(state, error))
          advanced            = skippedOld.copy(
                                  rootKey = root2,
                                  dhSelf = localDh,
                                  dhPeer = Some(header.dhPub),
                                  ckSend = Some(sendChain),
                                  ckRecv = Some(recvChain),
                                  pn = state.ns,
                                  ns = 0L,
                                  nr = 0L,
                                  retiredDh = retire(state.retiredDh, state.dhPeer)
                                )
          result             <- receiveCurrent(advanced, frame, countFailure = false).left.map(error => ReceiveError(state, error.error))
        yield result

  private def skipKeys(state: RatchetState, until: Long): Either[CoreError, RatchetState] =
    if until <= state.nr then Right(state)
    else
      val gap = until - state.nr
      if gap > S1Limits.MaxSkipGap.toLong then Left(CoreError.GapTooLarge)
      else if state.skipped.size.toLong + gap > S1Limits.MaxSkipTotal.toLong then Left(CoreError.GapTooLarge)
      else
        state.ckRecv match
          case None           => Left(CoreError.GapTooLarge)
          case Some(chainKey) =>
            state.dhPeer match
              case None         => Left(CoreError.GapTooLarge)
              case Some(peerDh) =>
                val (skipped, nextChain, nextNr) = (0L until gap).foldLeft((state.skipped, chainKey, state.nr)) { case ((keys, chain, n), _) =>
                  val (advancedChain, messageKey) = Crypto.kdfChain(chain)
                  (keys + (SkippedKey(peerDh, n) -> messageKey), advancedChain, n + 1L)
                }
                Right(state.copy(skipped = skipped, ckRecv = Some(nextChain), nr = nextNr))

  private def retire(current: Vector[X25519Public], oldDh: Option[X25519Public]): Vector[X25519Public] =
    oldDh match
      case None        => current
      case Some(value) =>
        val without = current.filterNot(_ == value)
        val next    = without :+ value
        if next.size > S1Limits.MaxRetiredRatchets then next.drop(next.size - S1Limits.MaxRetiredRatchets) else next

  private def validHeaderShape(header: MessageHeader): Boolean =
    header.kind match
      case MessageKind.Data | MessageKind.DataAck | MessageKind.Gap => header.seq > 0L && header.control.isEmpty
      case MessageKind.RekeyConfirm | MessageKind.RekeyConfirmAck   => header.seq == 0L && header.control.nonEmpty

  private def validateControlPayload(header: MessageHeader, plaintext: ByteVector): Either[CoreError, Unit] =
    if header.kind == MessageKind.Data || plaintext.isEmpty then Right(()) else Left(CoreError.InvalidEncoding)

  private def reject(state: RatchetState, error: CoreError): Left[ReceiveError, Nothing] =
    Left(ReceiveError(state, error))
