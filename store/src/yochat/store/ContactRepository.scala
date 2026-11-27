package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, SessionId}

private[store] object ContactRepository:

  final private case class Row(
    userPub: Array[Byte],
    sealedValue: Array[Byte],
    version: Long,
    createdAt: Long
  )

  def insert(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    data: ContactData,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ContactRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _           <- Sql.checkLock(expectedLockGeneration)
          tombstone   <- Sql.meta(Sql.contactVersionKey(userPub.bytes))
          _           <- tombstone match
                           case Some(_) => Sql.abort(StorageError.StaleGeneration)
                           case None    => FC.unit
          encoded     <- fromEither(StoreDataCodec.encodeContact(data))
          nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, 1L), encoded))
          changed     <-
            sql"INSERT INTO contacts(user_pub, sealed, version, created_at) VALUES (${userPub.bytes.toArray}, ${sealedValue}, 1, $now)".update.run
          _           <- Sql.changed(changed)
          _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), 1L)
        yield ContactRecord(userPub, data, 1L, now)
      }
    }

  def load(runtime: StoreRuntime, userPub: Ed25519Public): IO[Either[StorageError, ContactRecord]] =
    runtime.run {
      loadOptionProgram(runtime, userPub).flatMap {
        case Some(value) if value.isActive => FC.pure(value)
        case _                             => Sql.abort(StorageError.NotFound("contact"))
      }
    }

  /** Sync restore needs tombstones too: a `deleted` row must still be merged into `active` when a new invitation re-establishes the
    * relationship, and callers must see its version.
    */
  def loadForSync(runtime: StoreRuntime, userPub: Ed25519Public): IO[Either[StorageError, Option[ContactRecord]]] =
    runtime.run(loadOptionProgram(runtime, userPub))

  /** Sync restore must see tombstones so a deletion propagates to the user's other devices; the active-only `list` is what the UI and session
    * admission use.
    */
  def listAll(runtime: StoreRuntime): IO[Either[StorageError, List[ContactRecord]]] =
    runtime.run {
      sql"SELECT user_pub FROM contacts ORDER BY user_pub".query[Array[Byte]].to[List].flatMap { keys =>
        keys.foldLeft(FC.pure(List.empty[ContactRecord])) { (acc, key) =>
          acc.flatMap(values =>
            Ed25519Public.from(ByteVector.view(key)) match
              case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
              case Right(public) =>
                loadOptionProgram(runtime, public).flatMap {
                  case Some(record) => FC.pure(values :+ record)
                  case None         => Sql.abort(StorageError.NotFound("contact"))
                }
          )
        }
      }
    }

  def list(runtime: StoreRuntime): IO[Either[StorageError, List[ContactRecord]]] =
    runtime.run {
      sql"SELECT user_pub FROM contacts ORDER BY user_pub".query[Array[Byte]].to[List].flatMap { keys =>
        keys.foldLeft(FC.pure(List.empty[ContactRecord])) { (acc, key) =>
          acc.flatMap(values =>
            Ed25519Public.from(ByteVector.view(key)) match
              case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
              case Right(public) =>
                loadOptionProgram(runtime, public).flatMap {
                  case Some(record) if record.isActive => FC.pure(values :+ record)
                  case Some(_)                         => FC.pure(values)
                  case None                            => Sql.abort(StorageError.NotFound("contact"))
                }
          )
        }
      }
    }

  def update(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    data: ContactData,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ContactRecord]] =
    runtime.run {
      for
        _           <- Sql.checkLock(expectedLockGeneration)
        current     <- loadOptionProgram(runtime, userPub).flatMap {
                         case Some(value) if value.isActive => FC.pure(value)
                         case _                             => Sql.abort(StorageError.NotFound("contact"))
                       }
        _           <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        next        <- Sql.nextVersion(current.version)
        merged       = merge(Some(current), data)
        encoded     <- fromEither(StoreDataCodec.encodeContact(merged))
        nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
        sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, next), encoded))
        changed     <-
          sql"UPDATE contacts SET sealed = ${sealedValue}, version = $next WHERE user_pub = ${userPub.bytes.toArray} AND version = ${current.version}".update.run
        _           <- Sql.changed(changed)
        _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), next)
      yield ContactRecord(userPub, merged, next, current.createdAt)
    }

  /** Tombstone instead of `DELETE`: the row stays so a rollback of the sealed payload or of the meta high-water cannot resurrect the
    * relationship. `deleteHistory` is handled by `RelationshipRepository`.
    */
  def delete(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run(deleteProgram(runtime, userPub, expectedVersion, expectedLockGeneration))

  private[store] def deleteProgram(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): ConnectionIO[Unit] =
    for
      _           <- Sql.checkLock(expectedLockGeneration)
      current     <- loadOptionProgram(runtime, userPub).flatMap {
                       case Some(value) if value.isActive => FC.pure(value)
                       case _                             => Sql.abort(StorageError.NotFound("contact"))
                     }
      _           <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
      next        <- Sql.nextVersion(current.version)
      encoded     <- fromEither(StoreDataCodec.encodeContact(deletedData))
      nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
      sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, next), encoded))
      changed     <-
        sql"UPDATE contacts SET sealed = ${sealedValue}, version = $next WHERE user_pub = ${userPub.bytes.toArray} AND version = ${current.version}".update.run
      _           <- Sql.changed(changed)
      _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), next)
    yield ()

  private val deletedData: ContactData =
    ContactData(
      channelLabel = "",
      channelAccount = "",
      peerChannelAccount = None,
      alias = None,
      note = None,
      safetyVerified = false,
      confirmationState = ContactConfirmation.Pending,
      state = ContactState.Deleted,
      recreated = false
    )

  def setSafetyVerified(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    verified: Boolean,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ContactRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          lock        <- Sql.checkLock(expectedLockGeneration)
          contact     <- loadOptionProgram(runtime, userPub).flatMap {
                           case Some(value) if value.isActive => FC.pure(value)
                           case _                             => Sql.abort(StorageError.NotFound("contact"))
                         }
          _           <- if contact.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
          ids         <- sessionIds(userPub)
          sessions    <- ids.foldLeft(FC.pure(Vector.empty[SessionRecord])) { (acc, id) =>
                           acc.flatMap(values => SessionRepository.loadProgram(runtime, id).map(record => values :+ record))
                         }
          _           <- sessions.foldLeft(FC.unit) { (acc, record) =>
                           acc.flatMap(_ => if record.generation == lock then FC.unit else Sql.abort(StorageError.StaleGeneration))
                         }
          prepared    <- sessions.foldLeft(FC.pure(Vector.empty[SessionRepository.PreparedUpdate])) { (acc, record) =>
                           acc.flatMap(values =>
                             SessionRepository
                               .prepareUpdate(runtime, record, record.state.copy(safetyVerified = verified), lock, now)
                               .map(update => values :+ update)
                           )
                         }
          next        <- Sql.nextVersion(contact.version)
          data         = contact.data.copy(safetyVerified = verified, recreated = if verified then false else contact.data.recreated)
          encoded     <- fromEither(StoreDataCodec.encodeContact(data))
          nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, next), encoded))
          changed     <-
            sql"UPDATE contacts SET sealed = ${sealedValue}, version = $next WHERE user_pub = ${userPub.bytes.toArray} AND version = ${contact.version}".update.run
          _           <- Sql.changed(changed)
          _           <- prepared.foldLeft(FC.unit)((acc, update) => acc.flatMap(_ => SessionRepository.writeRow(update)))
          _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), next)
          _           <- prepared.foldLeft(FC.unit)((acc, update) => acc.flatMap(_ => SessionRepository.writeHighWater(update)))
        yield ContactRecord(userPub, data, next, contact.createdAt)
      }
    }

  def upsertProgram(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    data: ContactData,
    expectedLockGeneration: Long,
    now: Long
  ): ConnectionIO[ContactRecord] =
    Sql.checkLock(expectedLockGeneration).flatMap(_ => loadOptionProgram(runtime, userPub)).flatMap {
      case Some(current) =>
        val merged = merge(Some(current), data)
        for
          next        <- Sql.nextVersion(current.version)
          encoded     <- fromEither(StoreDataCodec.encodeContact(merged))
          nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, next), encoded))
          changed     <-
            sql"UPDATE contacts SET sealed = ${sealedValue}, version = $next WHERE user_pub = ${userPub.bytes.toArray} AND version = ${current.version}".update.run
          _           <- Sql.changed(changed)
          _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), next)
        yield ContactRecord(userPub, merged, next, current.createdAt)
      case None          =>
        val fresh = merge(None, data)
        for
          tombstone   <- Sql.meta(Sql.contactVersionKey(userPub.bytes))
          _           <- tombstone match
                           case Some(_) => Sql.abort(StorageError.StaleGeneration)
                           case None    => FC.unit
          encoded     <- fromEither(StoreDataCodec.encodeContact(fresh))
          nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, 1L), encoded))
          changed     <-
            sql"INSERT INTO contacts(user_pub, sealed, version, created_at) VALUES (${userPub.bytes.toArray}, ${sealedValue}, 1, $now)".update.run
          _           <- Sql.changed(changed)
          _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), 1L)
        yield ContactRecord(userPub, fresh, 1L, now)
    }

  /** Applies the primary device's contact state verbatim (active or deleted). `upsertProgram` deliberately forces `active`; sync must be able
    * to propagate a tombstone instead.
    */
  def upsertFromSyncProgram(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    data: ContactData,
    expectedLockGeneration: Long,
    now: Long
  ): ConnectionIO[ContactRecord] =
    Sql.checkLock(expectedLockGeneration).flatMap(_ => loadOptionProgram(runtime, userPub)).flatMap {
      case Some(current) =>
        for
          next        <- Sql.nextVersion(current.version)
          encoded     <- fromEither(StoreDataCodec.encodeContact(data))
          nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, next), encoded))
          changed     <-
            sql"UPDATE contacts SET sealed = ${sealedValue}, version = $next WHERE user_pub = ${userPub.bytes.toArray} AND version = ${current.version}".update.run
          _           <- Sql.changed(changed)
          _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), next)
        yield ContactRecord(userPub, data, next, current.createdAt)
      case None          =>
        for
          tombstone   <- Sql.meta(Sql.contactVersionKey(userPub.bytes))
          _           <- tombstone match
                           case Some(_) => Sql.abort(StorageError.StaleGeneration)
                           case None    => FC.unit
          encoded     <- fromEither(StoreDataCodec.encodeContact(data))
          nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, 1L), encoded))
          changed     <-
            sql"INSERT INTO contacts(user_pub, sealed, version, created_at) VALUES (${userPub.bytes.toArray}, ${sealedValue}, 1, $now)".update.run
          _           <- Sql.changed(changed)
          _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), 1L)
        yield ContactRecord(userPub, data, 1L, now)
    }

  /** Reviving a tombstone always yields a fresh, unverified relationship; otherwise `recreated` survives routine edits until the user verifies
    * the safety code.
    */
  private def merge(current: Option[ContactRecord], incoming: ContactData): ContactData =
    current match
      case Some(record) if !record.isActive =>
        incoming.copy(state = ContactState.Active, safetyVerified = false, recreated = true)
      case Some(record)                     =>
        incoming.copy(state = ContactState.Active, recreated = record.data.recreated && !incoming.safetyVerified)
      case None                             =>
        incoming.copy(state = ContactState.Active, recreated = false)

  private[store] def loadOptionProgram(runtime: StoreRuntime, userPub: Ed25519Public): ConnectionIO[Option[ContactRecord]] =
    row(userPub).flatMap {
      case None        => FC.pure(None)
      case Some(value) =>
        for
          _     <- Sql.checkHighWater(Sql.contactVersionKey(userPub.bytes), value.version)
          plain <- fromEither(
                     StoreCrypto.open(
                       runtime.keys.contactKey,
                       StoreCrypto.contactAad(ByteVector.view(value.userPub), value.version),
                       value.sealedValue
                     )
                   )
          data  <- fromEither(StoreDataCodec.decodeContact(plain))
        yield Some(ContactRecord(userPub, data, value.version, value.createdAt))
    }

  private def sessionIds(userPub: Ed25519Public): ConnectionIO[Vector[SessionId]] =
    sql"SELECT id FROM sessions WHERE peer_user_pub = ${userPub.bytes.toArray} ORDER BY id"
      .query[Array[Byte]]
      .to[List]
      .flatMap { values =>
        values.foldLeft(FC.pure(Vector.empty[SessionId])) { (acc, raw) =>
          acc.flatMap(sessions =>
            SessionId.from(ByteVector.view(raw)) match
              case Right(id) => FC.pure(sessions :+ id)
              case Left(_)   => Sql.abort(StorageError.InvalidEnvelope)
          )
        }
      }

  private def row(userPub: Ed25519Public): ConnectionIO[Option[Row]] =
    sql"SELECT user_pub, sealed, version, created_at FROM contacts WHERE user_pub = ${userPub.bytes.toArray}"
      .query[(Array[Byte], Array[Byte], Long, Long)]
      .option
      .map(_.map { case (publicKey, sealedValue, version, createdAt) => Row(publicKey, sealedValue, version, createdAt) })

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)
