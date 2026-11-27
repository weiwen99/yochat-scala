package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

/** Persistent blocked peers. A row means "this user is blocked"; unblocking bumps the row's meta high-water and deletes the row, so a
  * rolled-back row fails closed instead of silently unblocking the peer.
  */
private[store] object BlockedPeerRepository:

  def block(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Boolean]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _      <- Sql.checkLock(expectedLockGeneration)
          result <- blockProgram(userPub, now)
        yield result
      }
    }

  private[store] def blockProgram(userPub: Ed25519Public, now: Long): ConnectionIO[Boolean] =
    for
      current <- row(userPub)
      result  <- current match
                   case Some(value) =>
                     for _ <- Sql.checkHighWater(Sql.blockedVersionKey(userPub.bytes), value.version)
                     yield false
                   case None        =>
                     for
                       high    <- Sql.meta(Sql.blockedVersionKey(userPub.bytes))
                       version <- high match
                                    case Some(value) if value == Long.MaxValue => Sql.abort(StorageError.InvalidStateVersion)
                                    case Some(value)                           => FC.pure(value + 1L)
                                    case None                                  => FC.pure(1L)
                       changed <-
                         sql"""INSERT INTO blocked_peers(user_pub, version, created_at, updated_at)
                              |VALUES (${userPub.bytes.toArray}, $version, $now, $now)""".stripMargin.update.run
                       _       <- Sql.changed(changed)
                       _       <- Sql.writeMeta(Sql.blockedVersionKey(userPub.bytes), version)
                     yield true
    yield result

  def unblock(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Boolean]] =
    runtime.run {
      for
        _      <- Sql.checkLock(expectedLockGeneration)
        result <- unblockProgram(userPub)
      yield result
    }

  /** Removes both the row and its high-water key. Leaving the key behind would make the next open treat the tombstone as a deleted row and fail
    * closed; deleting both is the declared unblock, and a coordinated rollback of both is the documented undetectable case.
    */
  private[store] def unblockProgram(userPub: Ed25519Public): ConnectionIO[Boolean] =
    for
      current <- row(userPub)
      result  <- current match
                   case None        => FC.pure(false)
                   case Some(value) =>
                     for
                       _       <- Sql.checkHighWater(Sql.blockedVersionKey(userPub.bytes), value.version)
                       changed <-
                         sql"DELETE FROM blocked_peers WHERE user_pub = ${userPub.bytes.toArray} AND version = ${value.version}".update.run
                       _       <- Sql.changed(changed)
                       _       <- sql"DELETE FROM meta WHERE key = ${Sql.blockedVersionKey(userPub.bytes)}".update.run.map(_ => ())
                     yield true
    yield result

  /** Opens every blocked row so a corrupted or rolled-back row fails the instance open instead of degrading to "not blocked".
    */
  def loadAll: ConnectionIO[Set[Ed25519Public]] =
    for
      values  <- rows
      blocked <- values.foldLeft(FC.pure(Set.empty[Ed25519Public])) { (acc, value) =>
                   acc.flatMap { current =>
                     for
                       key <- Ed25519Public.from(value.userPub).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
                       _   <- Sql.checkHighWater(Sql.blockedVersionKey(key.bytes), value.version)
                     yield current + key
                   }
                 }
      keys    <- sql"SELECT key FROM meta WHERE key LIKE 'blocked-version/%'".query[String].to[List]
      _       <- keys.foldLeft(FC.unit) { (acc, key) =>
                   acc.flatMap(_ =>
                     ByteVector.fromHex(key.stripPrefix("blocked-version/")) match
                       case None        => Sql.abort(StorageError.InvalidEnvelope)
                       case Some(bytes) =>
                         if blocked.exists(_.bytes == bytes) then FC.unit
                         else Sql.abort(StorageError.StaleGeneration)
                   )
                 }
    yield blocked

  def list(runtime: StoreRuntime): IO[Either[StorageError, List[BlockedPeerRecord]]] =
    runtime.run {
      rows.flatMap { values =>
        values.foldLeft(FC.pure(List.empty[BlockedPeerRecord])) { (acc, value) =>
          acc.flatMap { records =>
            for
              key <- Ed25519Public.from(value.userPub).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
              _   <- Sql.checkHighWater(Sql.blockedVersionKey(key.bytes), value.version)
            yield records :+ BlockedPeerRecord(key, value.version, value.createdAt, value.updatedAt)
          }
        }
      }
    }

  private def rows: ConnectionIO[List[Row]] =
    sql"""SELECT user_pub, version, created_at, updated_at FROM blocked_peers ORDER BY user_pub"""
      .query[(Array[Byte], Long, Long, Long)]
      .to[List]
      .map(_.map { case (userPub, version, createdAt, updatedAt) =>
        Row(ByteVector.view(userPub), version, createdAt, updatedAt)
      })

  private def row(userPub: Ed25519Public): ConnectionIO[Option[Row]] =
    sql"""SELECT user_pub, version, created_at, updated_at FROM blocked_peers WHERE user_pub = ${userPub.bytes.toArray}"""
      .query[(Array[Byte], Long, Long, Long)]
      .option
      .map(_.map { case (raw, version, createdAt, updatedAt) =>
        Row(ByteVector.view(raw), version, createdAt, updatedAt)
      })

  final private case class Row(userPub: ByteVector, version: Long, createdAt: Long, updatedAt: Long)
