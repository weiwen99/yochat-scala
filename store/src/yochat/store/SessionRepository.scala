package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, RatchetState, SessionId, SessionPhase, StateCodec}

private[store] object SessionRepository:

  final private case class Row(
    id: Array[Byte],
    peerUserPub: Array[Byte],
    peerDevicePub: Array[Byte],
    state: Array[Byte],
    version: Long,
    generation: Long,
    updatedAt: Long
  )

  def insert(
    runtime: StoreRuntime,
    state: RatchetState,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, SessionRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          lock      <- Sql.checkLock(expectedLockGeneration)
          tombstone <- Sql.meta(Sql.sessionVersionKey(state.sessionId))
          _         <- tombstone match
                         case Some(_) => Sql.abort(StorageError.StaleGeneration)
                         case None    => FC.unit
          _         <- checkSafety(runtime, state)
          _         <- checkStateShape(state)
          record    <- writeNew(runtime, state, lock, now)
        yield record
      }
    }

  def load(runtime: StoreRuntime, sessionId: SessionId): IO[Either[StorageError, SessionRecord]] =
    runtime.run(loadProgram(runtime, sessionId))

  def writeProgram(runtime: StoreRuntime, state: RatchetState, lock: Long, now: Long): ConnectionIO[SessionRecord] =
    loadOptionProgram(runtime, state.sessionId).flatMap {
      case Some(current) =>
        if current.generation == lock then
          for
            _    <- checkStateShape(state)
            _    <- checkSafety(runtime, state)
            next <- updateProgram(runtime, current, state, lock, now)
          yield next
        else Sql.abort(StorageError.StaleGeneration)
      case None          =>
        for
          tombstone <- Sql.meta(Sql.sessionVersionKey(state.sessionId))
          _         <- tombstone match
                         case Some(_) => Sql.abort(StorageError.StaleGeneration)
                         case None    => FC.unit
          _         <- checkStateShape(state)
          _         <- checkSafety(runtime, state)
          record    <- writeNew(runtime, state, lock, now)
        yield record
    }

  def deletePendingProgram(runtime: StoreRuntime, sessionId: SessionId, lock: Long): ConnectionIO[Unit] =
    loadOptionProgram(runtime, sessionId).flatMap {
      case None          => FC.unit
      case Some(current) =>
        for
          _       <- if current.generation == lock then FC.unit else Sql.abort(StorageError.StaleGeneration)
          _       <- if current.state.phase == SessionPhase.Pending then FC.unit else Sql.abort(StorageError.InvalidArgument("session-phase"))
          pending <- pendingCountProgram(sessionId)
          _       <- if pending == 0L then FC.unit else Sql.abort(StorageError.PendingMessages)
          deleted <- sql"DELETE FROM sessions WHERE id = ${sessionId.bytes.toArray} AND state_version = ${current.version}".update.run
          _       <- Sql.changed(deleted)
          next    <- Sql.nextVersion(current.version)
          _       <- Sql.writeMeta(Sql.sessionVersionKey(sessionId), next)
        yield ()
    }

  def retireProgram(
    runtime: StoreRuntime,
    sessionId: SessionId,
    lock: Long,
    role: CutoverRole,
    evidence: CutoverEvidence
  ): ConnectionIO[Unit] =
    loadProgram(runtime, sessionId).flatMap { current =>
      for
        pending <- pendingCountProgram(sessionId)
        _       <- if pending == 0L then FC.unit else Sql.abort(StorageError.PendingMessages)
        _       <- cutoverReady(role, evidence)
        _       <- checkGeneration(current, lock)
        deleted <- sql"DELETE FROM sessions WHERE id = ${sessionId.bytes.toArray} AND state_version = ${current.version}".update.run
        _       <- Sql.changed(deleted)
        next    <- Sql.nextVersion(current.version)
        _       <- Sql.writeMeta(Sql.sessionVersionKey(sessionId), next)
      yield ()
    }

  def list(runtime: StoreRuntime): IO[Either[StorageError, List[SessionRecord]]] =
    runtime.run {
      sql"SELECT id FROM sessions ORDER BY id".query[Array[Byte]].to[List].flatMap { ids =>
        ids.foldLeft(FC.pure(List.empty[SessionRecord])) { (acc, id) =>
          acc.flatMap(values =>
            SessionId.from(ByteVector.view(id)) match
              case Left(_)        => Sql.abort(StorageError.InvalidEnvelope)
              case Right(session) => loadProgram(runtime, session).map(values :+ _)
          )
        }
      }
    }

  /** Re-seal existing session rows under the current lock generation after an unlock. The lock boundary invalidates old-generation writers, but
    * rows written before the lock still carry the previous generation; without this step the next legitimate write is rejected as stale. One
    * transaction, bounded by the number of sessions.
    */
  def adoptLockGeneration(runtime: StoreRuntime, expectedLockGeneration: Long): IO[Either[StorageError, Unit]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          lock     <- Sql.lockGeneration
          _        <- if lock == expectedLockGeneration then FC.unit else Sql.abort(StorageError.StaleGeneration)
          ids      <- sql"SELECT id FROM sessions ORDER BY id".query[Array[Byte]].to[List]
          sessions <- ids.foldLeft(FC.pure(Vector.empty[SessionRecord])) { (acc, id) =>
                        acc.flatMap(values =>
                          SessionId.from(ByteVector.view(id)) match
                            case Left(_)        => Sql.abort(StorageError.InvalidEnvelope)
                            case Right(session) => loadProgram(runtime, session).map(values :+ _)
                        )
                      }
          _        <- sessions.foldLeft(FC.unit) { (acc, record) =>
                        acc.flatMap(_ => if record.generation <= lock then FC.unit else Sql.abort(StorageError.StaleGeneration))
                      }
          updates  <- sessions.foldLeft(FC.pure(Vector.empty[PreparedUpdate])) { (acc, record) =>
                        acc.flatMap { values =>
                          if record.generation == lock then FC.pure(values)
                          else prepareUpdate(runtime, record, StateCodec.migrateLegacyDeadline(record.state), lock, now).map(values :+ _)
                        }
                      }
          _        <- updates.foldLeft(FC.unit)((acc, update) => acc.flatMap(_ => writeRow(update)))
          _        <- updates.foldLeft(FC.unit)((acc, update) => acc.flatMap(_ => writeHighWater(update)))
        yield ()
      }
    }

  def update(
    runtime: StoreRuntime,
    state: RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, SessionRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          lock    <- Sql.checkLock(expectedLockGeneration)
          current <- loadProgram(runtime, state.sessionId)
          _       <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
          _       <- if current.generation == lock then FC.unit else Sql.abort(StorageError.StaleGeneration)
          _       <- checkSafety(runtime, state)
          next    <- updateProgram(runtime, current, state, lock, now)
        yield next
      }
    }

  def retire(
    runtime: StoreRuntime,
    sessionId: SessionId,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    role: CutoverRole,
    evidence: CutoverEvidence
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        lock    <- Sql.checkLock(expectedLockGeneration)
        current <- loadProgram(runtime, sessionId)
        _       <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        pending <- pendingCountProgram(sessionId)
        _       <- if pending == 0L then FC.unit else Sql.abort(StorageError.PendingMessages)
        _       <- cutoverReady(role, evidence)
        _       <- checkGeneration(current, lock)
        deleted <- sql"DELETE FROM sessions WHERE id = ${sessionId.bytes.toArray} AND state_version = ${current.version}".update.run
        _       <- Sql.changed(deleted)
        next    <- Sql.nextVersion(current.version)
        _       <- Sql.writeMeta(Sql.sessionVersionKey(sessionId), next)
      yield ()
    }

  private[store] def loadProgram(runtime: StoreRuntime, sessionId: SessionId): ConnectionIO[SessionRecord] =
    loadOptionProgram(runtime, sessionId).flatMap {
      case Some(record) => FC.pure(record)
      case None         => Sql.abort(StorageError.NotFound("session"))
    }

  private[store] def loadOptionProgram(runtime: StoreRuntime, sessionId: SessionId): ConnectionIO[Option[SessionRecord]] =
    row(sessionId).flatMap {
      case None          => FC.pure(None)
      case Some(current) =>
        for
          _        <- Sql.checkHighWater(Sql.sessionVersionKey(sessionId), current.version)
          peerUser <- Sql.eitherCore(Ed25519Public.from(ByteVector.view(current.peerUserPub)))
          peerDev  <- Sql.eitherCore(Ed25519Public.from(ByteVector.view(current.peerDevicePub)))
          stateRaw <- fromEither(
                        StoreCrypto.open(
                          runtime.keys.stateKey(sessionId),
                          StoreCrypto.stateAad(
                            sessionId.bytes,
                            ByteVector.view(current.peerUserPub),
                            ByteVector.view(current.peerDevicePub),
                            current.generation,
                            current.version
                          ),
                          current.state
                        )
                      )
          state    <- fromEitherDecode(StateCodec.decode(stateRaw))
          _        <- checkBinding(sessionId, peerUser, peerDev, state)
          _        <- checkSafety(runtime, state)
        yield Some(SessionRecord(state, current.version, current.generation, current.updatedAt))
    }

  private[store] def checkSafety(runtime: StoreRuntime, state: RatchetState): ConnectionIO[Unit] =
    ContactRepository.loadOptionProgram(runtime, state.peerUserPub).flatMap {
      case None                                                                 => Sql.abort(StorageError.InvalidSafetyState)
      case Some(contact) if contact.data.safetyVerified == state.safetyVerified => FC.unit
      case Some(_)                                                              => Sql.abort(StorageError.InvalidSafetyState)
    }

  private[store] def checkStateShape(state: RatchetState): ConnectionIO[Unit] =
    if state.sessionId.bytes.size == SessionId.Length.toLong then FC.unit else Sql.abort(StorageError.InvalidArgument("session_id"))

  final private[store] case class PreparedUpdate(
    current: SessionRecord,
    state: RatchetState,
    nextVersion: Long,
    lock: Long,
    now: Long,
    sealedValue: Array[Byte]
  )

  private[store] def prepareUpdate(
    runtime: StoreRuntime,
    current: SessionRecord,
    state: RatchetState,
    lock: Long,
    now: Long
  ): ConnectionIO[PreparedUpdate] =
    for
      _           <- checkStateShape(state)
      _           <- if state.sessionId == current.state.sessionId then FC.unit else Sql.abort(StorageError.InvalidCiphertext)
      next        <- Sql.nextVersion(current.version)
      encoded     <- fromEitherDecode(StateCodec.encode(state))
      nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
      sealedValue <- fromEither(
                       StoreCrypto.seal(
                         runtime.keys.stateKey(state.sessionId),
                         nonce,
                         StoreCrypto.stateAad(
                           state.sessionId.bytes,
                           state.peerUserPub.bytes,
                           state.peerDevicePub.bytes,
                           lock,
                           next
                         ),
                         encoded
                       )
                     )
    yield PreparedUpdate(current, state, next, lock, now, sealedValue)

  private[store] def writeRow(update: PreparedUpdate): ConnectionIO[Unit] =
    sql"""UPDATE sessions
         |SET state = ${update.sealedValue}, state_version = ${update.nextVersion},
         |    generation = ${update.lock}, updated_at = ${update.now}
         |WHERE id = ${update.state.sessionId.bytes.toArray}
         |  AND state_version = ${update.current.version}
         |  AND generation = ${update.current.generation}""".stripMargin.update.run.flatMap(Sql.changed)

  private[store] def writeHighWater(update: PreparedUpdate): ConnectionIO[Unit] =
    Sql.writeMeta(Sql.sessionVersionKey(update.state.sessionId), update.nextVersion)

  private[store] def updateProgram(
    runtime: StoreRuntime,
    current: SessionRecord,
    state: RatchetState,
    lock: Long,
    now: Long
  ): ConnectionIO[SessionRecord] =
    for
      update <- prepareUpdate(runtime, current, state, lock, now)
      _      <- writeRow(update)
      _      <- writeHighWater(update)
    yield SessionRecord(state, update.nextVersion, lock, now)

  private[store] def pendingCountProgram(sessionId: SessionId): ConnectionIO[Long] =
    sql"""SELECT count(*) FROM messages
         |WHERE session_id = ${sessionId.bytes.toArray}
         |  AND state IN (${MessageState.code(MessageState.Pending)}, ${MessageState.code(MessageState.Sent)})""".stripMargin
      .query[Long]
      .unique

  private def writeNew(runtime: StoreRuntime, state: RatchetState, lock: Long, now: Long): ConnectionIO[SessionRecord] =
    for
      encoded     <- fromEitherDecode(StateCodec.encode(state))
      nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
      sealedValue <- fromEither(
                       StoreCrypto.seal(
                         runtime.keys.stateKey(state.sessionId),
                         nonce,
                         StoreCrypto.stateAad(
                           state.sessionId.bytes,
                           state.peerUserPub.bytes,
                           state.peerDevicePub.bytes,
                           lock,
                           1L
                         ),
                         encoded
                       )
                     )
      changed     <- sql"""INSERT INTO sessions(id, peer_user_pub, peer_device_pub, state, state_version, generation, updated_at)
                          |VALUES (${state.sessionId.bytes.toArray}, ${state.peerUserPub.bytes.toArray}, ${state.peerDevicePub.bytes.toArray},
                          |        ${sealedValue}, 1, $lock, $now)""".stripMargin.update.run
      _           <- Sql.changed(changed)
      _           <- Sql.writeMeta(Sql.sessionVersionKey(state.sessionId), 1L)
    yield SessionRecord(state, 1L, lock, now)

  private def row(sessionId: SessionId): ConnectionIO[Option[Row]] =
    sql"""SELECT id, peer_user_pub, peer_device_pub, state, state_version, generation, updated_at
         |FROM sessions WHERE id = ${sessionId.bytes.toArray}""".stripMargin
      .query[(Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Long, Long)]
      .option
      .map(_.map { case (id, user, device, state, version, generation, updatedAt) =>
        Row(id, user, device, state, version, generation, updatedAt)
      })

  private def checkBinding(
    sessionId: SessionId,
    peerUser: Ed25519Public,
    peerDevice: Ed25519Public,
    state: RatchetState
  ): ConnectionIO[Unit] =
    if state.sessionId != sessionId then Sql.abort(StorageError.InvalidCiphertext)
    else if state.peerUserPub != peerUser || state.peerDevicePub != peerDevice then Sql.abort(StorageError.InvalidCiphertext)
    else FC.unit

  private def checkGeneration(record: SessionRecord, lock: Long): ConnectionIO[Unit] =
    if record.generation == lock then FC.unit else Sql.abort(StorageError.StaleGeneration)

  private def cutoverReady(role: CutoverRole, evidence: CutoverEvidence): ConnectionIO[Unit] =
    role match
      case CutoverRole.Initiator =>
        if evidence.confirmAckReceived then FC.unit else Sql.abort(StorageError.CutoverNotReady)
      case CutoverRole.Responder =>
        if evidence.firstNewSessionDataReceived then FC.unit else Sql.abort(StorageError.CutoverNotReady)

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)

  private def fromEitherDecode[A](value: Either[yochat.core.CoreError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(_)       => Sql.abort(StorageError.InvalidCiphertext)
