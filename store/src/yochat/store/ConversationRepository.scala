package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

private[store] object ConversationRepository:

  def create(
    runtime: StoreRuntime,
    id: ByteVector,
    peerUserPub: Ed25519Public,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ConversationRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _       <- checkId(id)
          _       <- Sql.checkLock(expectedLockGeneration)
          changed <-
            sql"INSERT INTO conversations(id, peer_user_pub, created_at, last_applied_seq) VALUES (${id.toArray}, ${peerUserPub.bytes.toArray}, $now, 0)".update.run
          _       <- Sql.changed(changed)
        yield ConversationRecord(id, peerUserPub, now, 0L)
      }
    }

  def load(runtime: StoreRuntime, id: ByteVector): IO[Either[StorageError, ConversationRecord]] =
    runtime.run {
      row(id).flatMap {
        case Some(value) => FC.pure(value)
        case None        => Sql.abort(StorageError.NotFound("conversation"))
      }
    }

  def list(runtime: StoreRuntime): IO[Either[StorageError, List[ConversationRecord]]] =
    runtime.run {
      sql"SELECT id FROM conversations ORDER BY id".query[Array[Byte]].to[List].flatMap { ids =>
        ids.foldLeft(FC.pure(List.empty[ConversationRecord])) { (acc, id) =>
          acc.flatMap(values =>
            row(ByteVector.view(id)).flatMap {
              case Some(record) => FC.pure(values :+ record)
              case None         => Sql.abort(StorageError.NotFound("conversation"))
            }
          )
        }
      }
    }

  def advance(
    runtime: StoreRuntime,
    id: ByteVector,
    expected: Long,
    next: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ConversationRecord]] =
    runtime.run {
      for
        _       <- checkId(id)
        _       <- Sql.checkLock(expectedLockGeneration)
        current <- row(id).flatMap {
                     case Some(value) => FC.pure(value)
                     case None        => Sql.abort(StorageError.NotFound("conversation"))
                   }
        _       <- if current.lastAppliedSeq == expected && next > expected then FC.unit else Sql.abort(StorageError.StaleGeneration)
        changed <- sql"UPDATE conversations SET last_applied_seq = $next WHERE id = ${id.toArray} AND last_applied_seq = $expected".update.run
        _       <- Sql.changed(changed)
      yield ConversationRecord(id, current.peerUserPub, current.createdAt, next)
    }

  private def row(id: ByteVector): ConnectionIO[Option[ConversationRecord]] =
    sql"SELECT id, peer_user_pub, created_at, last_applied_seq FROM conversations WHERE id = ${id.toArray}"
      .query[(Array[Byte], Array[Byte], Long, Long)]
      .option
      .flatMap {
        case None                                    => FC.pure(None)
        case Some((rawId, peer, createdAt, applied)) =>
          Ed25519Public.from(ByteVector.view(peer)) match
            case Right(publicKey) => FC.pure(Some(ConversationRecord(ByteVector.view(rawId), publicKey, createdAt, applied)))
            case Left(_)          => Sql.abort(StorageError.InvalidEnvelope)
      }

  private def checkId(id: ByteVector): ConnectionIO[Unit] =
    if id.size == 32L then FC.unit else Sql.abort(StorageError.InvalidArgument("conversation_id"))

private[store] object MetaRepository:

  def bumpLockGeneration(runtime: StoreRuntime, expected: Long): IO[Either[StorageError, Long]] =
    runtime.run {
      for
        current <- Sql.lockGeneration
        _       <- if current == expected then FC.unit else Sql.abort(StorageError.StaleGeneration)
        next    <- if expected == Long.MaxValue then Sql.abort(StorageError.InvalidStateVersion) else FC.pure(expected + 1L)
        changed <- sql"UPDATE meta SET value = $next WHERE key = 'lock-generation' AND value = $expected".update.run
        _       <- Sql.changed(changed)
      yield next
    }
