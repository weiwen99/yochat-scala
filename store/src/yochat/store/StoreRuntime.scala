package yochat.store

import java.sql.SQLException
import java.time.Clock

import cats.effect.std.Semaphore
import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.{CoreError, SessionId}

final private[store] class StoreRuntime(
  val transactor: Transactor[IO],
  val semaphore: Semaphore[IO],
  val keys: StoreKeys,
  val clock: Clock,
  val paths: StorePaths
):

  def run[A](program: ConnectionIO[A]): IO[Either[StorageError, A]] =
    transact(program)

  def transact[A](program: ConnectionIO[A]): IO[Either[StorageError, A]] =
    semaphore.permit.use { _ =>
      transactor.trans.apply(program).attempt.map {
        case Right(value)            => Right(value)
        case Left(abort: StoreAbort) => Left(abort.error)
        case Left(error: SQLException)
            if error.getErrorCode == 19 &&
              Option(error.getMessage).exists(_.contains("UNIQUE constraint failed")) =>
          Left(StorageError.DuplicateSeq)
        case Left(_: SQLException)   => Left(StorageError.StorageFailure)
        case Left(_)                 => Left(StorageError.StorageFailure)
      }
    }

  /** Maintenance operations such as `VACUUM INTO` must not run inside doobie's default transaction. */
  def raw[A](program: ConnectionIO[A]): IO[Either[StorageError, A]] =
    semaphore.permit.use { _ =>
      transactor.rawTrans.apply(program).attempt.map {
        case Right(value)          => Right(value)
        case Left(_: SQLException) => Left(StorageError.StorageFailure)
        case Left(_)               => Left(StorageError.StorageFailure)
      }
    }

  def now: IO[Long] = IO.delay(clock.millis())

private[store] object Sql:

  def meta(key: String): ConnectionIO[Option[Long]] =
    sql"SELECT value FROM meta WHERE key = $key".query[Long].option

  def writeMeta(key: String, value: Long): ConnectionIO[Unit] =
    sql"INSERT INTO meta(key, value) VALUES ($key, $value) ON CONFLICT(key) DO UPDATE SET value = excluded.value".update.run.flatMap { changed =>
      if changed == 1 then FC.unit else abort(StorageError.StorageFailure)
    }

  def lockGeneration: ConnectionIO[Long] =
    meta("lock-generation").flatMap {
      case Some(value) => FC.pure(value)
      case None        => abort(StorageError.MissingHighWater("lock-generation"))
    }

  def checkLock(expected: Long): ConnectionIO[Long] =
    lockGeneration.flatMap { current =>
      if current == expected then FC.pure(current) else abort(StorageError.StaleGeneration)
    }

  def checkHighWater(key: String, rowVersion: Long): ConnectionIO[Unit] =
    meta(key).flatMap {
      case Some(high) if rowVersion < high => abort(StorageError.StaleGeneration)
      case Some(high) if rowVersion > high => abort(StorageError.InvalidStateVersion)
      case Some(_)                         => FC.unit
      case None                            => abort(StorageError.MissingHighWater(key))
    }

  def nextVersion(value: Long): ConnectionIO[Long] =
    if value == Long.MaxValue then abort(StorageError.InvalidStateVersion) else FC.pure(value + 1L)

  def changed(changed: Int): ConnectionIO[Unit] =
    if changed == 1 then FC.unit else abort(StorageError.WriteConflict)

  def abort[A](error: StorageError): ConnectionIO[A] =
    FC.raiseError(new StoreAbort(error))

  def eitherCore[A](value: Either[CoreError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(_)       => abort(StorageError.InvalidEnvelope)

  def bindingKey(conversationId: ByteVector): String =
    s"binding/${conversationId.toHex}"

  def sessionVersionKey(sessionId: SessionId): String =
    s"session-state-version/${sessionId.bytes.toHex}"

  def contactVersionKey(userPub: ByteVector): String =
    s"contact-version/${userPub.toHex}"

  def invitationVersionKey(id: ByteVector): String =
    s"invitation-version/${id.toHex}"
