package yochat.core

import scodec.bits.ByteVector

/** Canonical state blob for the encrypted `sessions.state` column. Storage AAD binding is ADR-0007's job. */
object StateCodec:

  val Version: Int = 2

  def encode(state: RatchetState): Either[CoreError, ByteVector] =
    if state.retiredDh.size > S1Limits.MaxRetiredRatchets then
      Left(CoreError.InvalidLength("retired_dh", S1Limits.MaxRetiredRatchets, state.retiredDh.size.toLong))
    else if state.skipped.size > S1Limits.MaxSkipTotal then
      Left(CoreError.InvalidLength("skipped", S1Limits.MaxSkipTotal, state.skipped.size.toLong))
    else if state.rekeyFailures < 0 || state.rekeyFailures > S1Limits.MaxRekeyFailures then Left(CoreError.InvalidState)
    else if state.rekey.exists(_.deadlineMs <= 0L) then Left(CoreError.InvalidState)
    else
      val writer = CanonicalWriter()
        .u8(Version)
        .fixed(state.sessionId.bytes)
        .fixed(state.peerUserPub.bytes)
        .fixed(state.peerDevicePub.bytes)
        .fixed(state.rootKey.bytes)
        .fixed(state.rekeyBase.bytes)
        .u64(state.rekeyEpoch)
        .u64(state.lastRekeyEpoch)
        .fixed(state.dhSelf.privateKey.bytes)
        .fixed(state.dhSelf.publicKey.bytes)
      writeOptionKey(writer, state.dhPeer.map(_.bytes))
      writeOptionKey(writer, state.ckSend.map(_.bytes))
      writeOptionKey(writer, state.ckRecv.map(_.bytes))
      writer
        .u64(state.ns)
        .u64(state.nr)
        .u64(state.pn)
        .u32(state.skipped.size.toLong)
      state.skipped.toVector.sortBy { case (key, _) => (key.dhPub.bytes.toHex, key.n) }.foreach { case (key, messageKey) =>
        writer.fixed(key.dhPub.bytes).u64(key.n).fixed(messageKey.bytes)
      }
      writer.u32(state.retiredDh.size.toLong)
      state.retiredDh.foreach(value => writer.fixed(value.bytes))
      writeRekey(writer, state.rekey)
      writer
        .u32(state.rekeyFailures.toLong)
        .u8(phaseCode(state.phase))
        .u8(if state.safetyVerified then 1 else 0)
        .u32(state.rekeyAttempts.toLong)
        .u64(state.rekeyCooldownUntilMs)
      Right(writer.result)

  def decode(value: ByteVector): Either[CoreError, RatchetState] =
    val reader = CanonicalReader(value)
    for
      (version, r1)        <- reader.u8
      _                    <- if version == 1 || version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (sessionRaw, r2)     <- r1.fixed(SessionId.Length)
      sessionId            <- SessionId.from(sessionRaw)
      (peerUserRaw, r3)    <- r2.fixed(Ed25519Public.Length)
      peerUserPub          <- Ed25519Public.from(peerUserRaw)
      (peerDeviceRaw, r4)  <- r3.fixed(Ed25519Public.Length)
      peerDevicePub        <- Ed25519Public.from(peerDeviceRaw)
      (rootRaw, r5)        <- r4.fixed(Key32.Length)
      rootKey              <- Key32.from(rootRaw)
      (rekeyBaseRaw, r6)   <- r5.fixed(Key32.Length)
      rekeyBase            <- Key32.from(rekeyBaseRaw)
      (rekeyEpoch, r7)     <- r6.u64
      (lastRekeyEpoch, r8) <- r7.u64
      (privRaw, r9)        <- r8.fixed(X25519Private.Length)
      dhPrivate            <- X25519Private.from(privRaw)
      (pubRaw, r10)        <- r9.fixed(X25519Public.Length)
      dhPublic             <- X25519Public.from(pubRaw)
      (dhPeer, r11)        <- readOptionKey(r10, X25519Public.from)
      (ckSend, r12)        <- readOptionKey(r11, Key32.from)
      (ckRecv, r13)        <- readOptionKey(r12, Key32.from)
      (ns, r14)            <- r13.u64
      (nr, r15)            <- r14.u64
      (pn, r16)            <- r15.u64
      (skipped, r17)       <- readSkipped(r16)
      (retired, r18)       <- readRetired(r17)
      (rekey, r19)         <- readRekey(r18, version)
      (failures, r20)      <- r19.u32
      (phaseRaw, r21)      <- r20.u8
      phase                <- phaseFromCode(phaseRaw)
      (safetyRaw, r22)     <- r21.u8
      safetyVerified       <- if safetyRaw == 0 || safetyRaw == 1 then Right(safetyRaw == 1) else Left(CoreError.InvalidEncoding)
      (attempts, r23)      <- r22.u32
      (cooldown, r24)      <- r23.u64
      _                    <- r24.finish
      _                    <- if failures <= S1Limits.MaxRekeyFailures.toLong then Right(()) else Left(CoreError.InvalidState)
      _                    <- if attempts <= S1Limits.MaxRekeyAttempts.toLong then Right(()) else Left(CoreError.InvalidState)
      _                    <- if version == 1 || rekey.forall(_.deadlineMs > 0L) then Right(()) else Left(CoreError.InvalidState)
    yield RatchetState(
      sessionId = sessionId,
      peerUserPub = peerUserPub,
      peerDevicePub = peerDevicePub,
      rootKey = rootKey,
      rekeyBase = rekeyBase,
      rekeyEpoch = rekeyEpoch,
      lastRekeyEpoch = lastRekeyEpoch,
      dhSelf = X25519KeyPair(dhPrivate, dhPublic),
      dhPeer = dhPeer,
      ckSend = ckSend,
      ckRecv = ckRecv,
      ns = ns,
      nr = nr,
      pn = pn,
      skipped = skipped,
      retiredDh = retired,
      rekey = rekey,
      rekeyFailures = failures.toInt,
      phase = phase,
      safetyVerified = safetyVerified,
      rekeyAttempts = attempts.toInt,
      rekeyCooldownUntilMs = cooldown
    )

  private def writeOptionKey(writer: CanonicalWriter, value: Option[ByteVector]): Unit =
    value match
      case None        => writer.u8(0)
      case Some(bytes) => writer.u8(1).fixed(bytes)

  private def readOptionKey[A](
    reader: CanonicalReader,
    from: ByteVector => Either[CoreError, A]
  ): Either[CoreError, (Option[A], CanonicalReader)] =
    readOptionBytes(reader, Key32.Length).flatMap {
      case (None, next)      => Right((None, next))
      case (Some(raw), next) =>
        from(raw).map(value => (Some(value), next))
    }

  private def readOptionBytes(reader: CanonicalReader, length: Int): Either[CoreError, (Option[ByteVector], CanonicalReader)] =
    reader.u8.flatMap {
      case (0, next) => Right((None, next))
      case (1, next) => next.fixed(length).map { case (raw, after) => (Some(raw), after) }
      case _         => Left(CoreError.InvalidEncoding)
    }

  private def writeOptionBytes(writer: CanonicalWriter, value: Option[ByteVector]): Unit =
    value match
      case None        => writer.u8(0)
      case Some(bytes) => writer.u8(1).bytes(bytes)

  private def readOptionBytesPrefixed(reader: CanonicalReader, maxLength: Int): Either[CoreError, (Option[ByteVector], CanonicalReader)] =
    reader.u8.flatMap {
      case (0, next) => Right((None, next))
      case (1, next) => next.bytes(maxLength).map { case (raw, after) => (Some(raw), after) }
      case _         => Left(CoreError.InvalidEncoding)
    }

  private def readSkipped(reader: CanonicalReader): Either[CoreError, (Map[SkippedKey, Key32], CanonicalReader)] =
    reader.u32.flatMap { case (count, afterCount) =>
      if count > S1Limits.MaxSkipTotal.toLong then Left(CoreError.InvalidLength("skipped", S1Limits.MaxSkipTotal, count))
      else
        (0L until count).foldLeft[Either[CoreError, (Map[SkippedKey, Key32], CanonicalReader)]](
          Right((Map.empty, afterCount))
        ) { case (result, _) =>
          result.flatMap { case (keys, current) =>
            for
              (dhRaw, r1)  <- current.fixed(X25519Public.Length)
              dhPub        <- X25519Public.from(dhRaw)
              (n, r2)      <- r1.u64
              (keyRaw, r3) <- r2.fixed(Key32.Length)
              messageKey   <- Key32.from(keyRaw)
              key           = SkippedKey(dhPub, n)
              _            <- if keys.contains(key) then Left(CoreError.InvalidEncoding) else Right(())
            yield (keys + (key -> messageKey), r3)
          }
        }
    }

  private def readRetired(reader: CanonicalReader): Either[CoreError, (Vector[X25519Public], CanonicalReader)] =
    reader.u32.flatMap { case (count, afterCount) =>
      if count > S1Limits.MaxRetiredRatchets.toLong then Left(CoreError.InvalidLength("retired_dh", S1Limits.MaxRetiredRatchets, count))
      else
        (0L until count).foldLeft[Either[CoreError, (Vector[X25519Public], CanonicalReader)]](
          Right((Vector.empty, afterCount))
        ) { case (result, _) =>
          result.flatMap { case (retired, current) =>
            current.fixed(X25519Public.Length).flatMap { case (raw, next) =>
              X25519Public.from(raw).map(value => (retired :+ value, next))
            }
          }
        }
    }

  private def writeRekey(writer: CanonicalWriter, rekey: Option[RekeyState]): Unit =
    rekey match
      case None        => writer.u8(0)
      case Some(value) =>
        writer
          .u8(1)
          .u8(roleCode(value.role))
          .fixed(value.id.bytes)
          .u64(value.epoch)
          .u8(rekeyPhaseCode(value.phase))
        writeOptionKey(writer, value.localRatchetKey.map(key => key.privateKey.bytes ++ key.publicKey.bytes))
        writeOptionKey(writer, value.newSessionId.map(_.bytes))
        writeOptionBytes(writer, value.cachedAccept)
        writer.u64(value.deadlineMs)

  private def readRekey(reader: CanonicalReader, version: Int): Either[CoreError, (Option[RekeyState], CanonicalReader)] =
    reader.u8.flatMap {
      case (0, next) => Right((None, next))
      case (1, next) =>
        for
          (roleRaw, r1)      <- next.u8
          role               <- roleFromCode(roleRaw)
          (idRaw, r2)        <- r1.fixed(RekeyId.Length)
          id                 <- RekeyId.from(idRaw)
          (epoch, r3)        <- r2.u64
          (phaseRaw, r4)     <- r3.u8
          phase              <- rekeyPhaseFromCode(phaseRaw)
          (localRaw, r5)     <- readOptionBytes(r4, X25519Private.Length + X25519Public.Length)
          localKey           <- localRaw match
                                  case None      => Right(None)
                                  case Some(raw) =>
                                    for
                                      privateKey <- X25519Private.from(raw.take(X25519Private.Length))
                                      publicKey  <- X25519Public.from(raw.drop(X25519Private.Length))
                                    yield Some(X25519KeyPair(privateKey, publicKey))
          (newSession, r6)   <- readOptionKey(r5, SessionId.from)
          (cachedAccept, r7) <- readOptionBytesPrefixed(r6, 1_048_576)
          (deadlineMs, r8)   <- if version >= 2 then r7.u64 else Right((0L, r7))
        yield (Some(RekeyState(role, id, epoch, phase, localKey, newSession, cachedAccept, deadlineMs)), r8)
      case _         => Left(CoreError.InvalidEncoding)
    }

  private def phaseCode(phase: SessionPhase): Int =
    phase match
      case SessionPhase.Active   => 1
      case SessionPhase.Pending  => 2
      case SessionPhase.ReadOnly => 3

  private def phaseFromCode(code: Int): Either[CoreError, SessionPhase] =
    code match
      case 1 => Right(SessionPhase.Active)
      case 2 => Right(SessionPhase.Pending)
      case 3 => Right(SessionPhase.ReadOnly)
      case _ => Left(CoreError.InvalidEncoding)

  private def roleCode(role: RekeyRole): Int =
    role match
      case RekeyRole.Initiator => 1
      case RekeyRole.Responder => 2

  private def roleFromCode(code: Int): Either[CoreError, RekeyRole] =
    code match
      case 1 => Right(RekeyRole.Initiator)
      case 2 => Right(RekeyRole.Responder)
      case _ => Left(CoreError.InvalidEncoding)

  private def rekeyPhaseCode(phase: RekeyPhase): Int =
    phase match
      case RekeyPhase.Requested => 1
      case RekeyPhase.Accepted  => 2

  private def rekeyPhaseFromCode(code: Int): Either[CoreError, RekeyPhase] =
    code match
      case 1 => Right(RekeyPhase.Requested)
      case 2 => Right(RekeyPhase.Accepted)
      case _ => Left(CoreError.InvalidEncoding)
