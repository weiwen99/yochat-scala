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
        case Some(value) => FC.pure(value)
        case None        => Sql.abort(StorageError.NotFound("contact"))
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
                         case Some(value) => FC.pure(value)
                         case None        => Sql.abort(StorageError.NotFound("contact"))
                       }
        _           <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        next        <- Sql.nextVersion(current.version)
        encoded     <- fromEither(StoreDataCodec.encodeContact(data))
        nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
        sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.contactKey, nonce, StoreCrypto.contactAad(userPub.bytes, next), encoded))
        changed     <-
          sql"UPDATE contacts SET sealed = ${sealedValue}, version = $next WHERE user_pub = ${userPub.bytes.toArray} AND version = ${current.version}".update.run
        _           <- Sql.changed(changed)
        _           <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), next)
      yield ContactRecord(userPub, data, next, current.createdAt)
    }

  def delete(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        _       <- Sql.checkLock(expectedLockGeneration)
        current <- loadOptionProgram(runtime, userPub).flatMap {
                     case Some(value) => FC.pure(value)
                     case None        => Sql.abort(StorageError.NotFound("contact"))
                   }
        _       <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        deleted <- sql"DELETE FROM contacts WHERE user_pub = ${userPub.bytes.toArray} AND version = ${current.version}".update.run
        _       <- Sql.changed(deleted)
        next    <- Sql.nextVersion(current.version)
        _       <- Sql.writeMeta(Sql.contactVersionKey(userPub.bytes), next)
      yield ()
    }

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
                           case Some(value) => FC.pure(value)
                           case None        => Sql.abort(StorageError.NotFound("contact"))
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
          data         = contact.data.copy(safetyVerified = verified)
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
