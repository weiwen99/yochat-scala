package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

private[store] object InvitationRepository:

  final private case class Row(id: Array[Byte], sealedValue: Array[Byte], version: Long, createdAt: Long)

  def insert(
    runtime: StoreRuntime,
    id: ByteVector,
    data: InvitationData,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, InvitationRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _           <- checkId(id)
          _           <- Sql.checkLock(expectedLockGeneration)
          tombstone   <- Sql.meta(Sql.invitationVersionKey(id))
          _           <- tombstone match
                           case Some(_) => Sql.abort(StorageError.WriteConflict)
                           case None    => FC.unit
          encoded     <- fromEither(StoreDataCodec.encodeInvitation(data))
          nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.invitationKey, nonce, StoreCrypto.invitationAad(id, 1L), encoded))
          changed     <- sql"INSERT INTO invitations(id, sealed, version, created_at) VALUES (${id.toArray}, ${sealedValue}, 1, $now)".update.run
          _           <- Sql.changed(changed)
          _           <- Sql.writeMeta(Sql.invitationVersionKey(id), 1L)
        yield InvitationRecord(id, data, 1L, now)
      }
    }

  def load(runtime: StoreRuntime, id: ByteVector): IO[Either[StorageError, InvitationRecord]] =
    runtime.run {
      loadOptionProgram(runtime, id).flatMap {
        case Some(value) => FC.pure(value)
        case None        => Sql.abort(StorageError.NotFound("invitation"))
      }
    }

  def list(runtime: StoreRuntime): IO[Either[StorageError, List[InvitationRecord]]] =
    runtime.run {
      sql"SELECT id FROM invitations ORDER BY id".query[Array[Byte]].to[List].flatMap { ids =>
        ids.foldLeft(FC.pure(List.empty[InvitationRecord])) { (acc, id) =>
          acc.flatMap(values =>
            loadOptionProgram(runtime, ByteVector.view(id)).flatMap {
              case Some(record) => FC.pure(values :+ record)
              case None         => Sql.abort(StorageError.NotFound("invitation"))
            }
          )
        }
      }
    }

  def update(
    runtime: StoreRuntime,
    id: ByteVector,
    data: InvitationData,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, InvitationRecord]] =
    runtime.run {
      for
        _           <- checkId(id)
        _           <- Sql.checkLock(expectedLockGeneration)
        current     <- loadOptionProgram(runtime, id).flatMap {
                         case Some(value) => FC.pure(value)
                         case None        => Sql.abort(StorageError.NotFound("invitation"))
                       }
        _           <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        next        <- Sql.nextVersion(current.version)
        encoded     <- fromEither(StoreDataCodec.encodeInvitation(data))
        nonce       <- FC.delay(StoreCrypto.randomNonceUnsafe())
        sealedValue <- fromEither(StoreCrypto.seal(runtime.keys.invitationKey, nonce, StoreCrypto.invitationAad(id, next), encoded))
        changed     <-
          sql"UPDATE invitations SET sealed = ${sealedValue}, version = $next WHERE id = ${id.toArray} AND version = ${current.version}".update.run
        _           <- Sql.changed(changed)
        _           <- Sql.writeMeta(Sql.invitationVersionKey(id), next)
      yield InvitationRecord(id, data, next, current.createdAt)
    }

  def delete(
    runtime: StoreRuntime,
    id: ByteVector,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        _       <- checkId(id)
        _       <- Sql.checkLock(expectedLockGeneration)
        current <- loadOptionProgram(runtime, id).flatMap {
                     case Some(value) => FC.pure(value)
                     case None        => Sql.abort(StorageError.NotFound("invitation"))
                   }
        _       <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        deleted <- sql"DELETE FROM invitations WHERE id = ${id.toArray} AND version = ${current.version}".update.run
        _       <- Sql.changed(deleted)
        next    <- Sql.nextVersion(current.version)
        _       <- Sql.writeMeta(Sql.invitationVersionKey(id), next)
      yield ()
    }

  private[store] def loadOptionProgram(runtime: StoreRuntime, id: ByteVector): ConnectionIO[Option[InvitationRecord]] =
    row(id).flatMap {
      case None        => FC.pure(None)
      case Some(value) =>
        for
          _     <- Sql.checkHighWater(Sql.invitationVersionKey(id), value.version)
          plain <- fromEither(
                     StoreCrypto.open(
                       runtime.keys.invitationKey,
                       StoreCrypto.invitationAad(ByteVector.view(value.id), value.version),
                       value.sealedValue
                     )
                   )
          data  <- fromEither(StoreDataCodec.decodeInvitation(plain))
        yield Some(InvitationRecord(id, data, value.version, value.createdAt))
    }

  private def row(id: ByteVector): ConnectionIO[Option[Row]] =
    sql"SELECT id, sealed, version, created_at FROM invitations WHERE id = ${id.toArray}"
      .query[(Array[Byte], Array[Byte], Long, Long)]
      .option
      .map(_.map { case (rawId, sealedValue, version, createdAt) => Row(rawId, sealedValue, version, createdAt) })

  private def checkId(id: ByteVector): ConnectionIO[Unit] =
    if id.size == 32L then FC.unit else Sql.abort(StorageError.InvalidArgument("invitation_id"))

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)
