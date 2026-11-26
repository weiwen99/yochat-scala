package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.s2.{LogicalKey, OrderStamp}

/** Durable GBNV metadata. Kept beside `messages` so old S1 rows can be ordered with the legacy `lamport = seq` fallback. */
private[store] object MessageOrderRepository:

  def upsert(
    runtime: StoreRuntime,
    key: LogicalKey,
    stamp: OrderStamp,
    createdAt: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        _ <- Sql.checkLock(expectedLockGeneration)
        _ <- write(key, stamp, createdAt)
      yield ()
    }

  def list(runtime: StoreRuntime, conversationId: ByteVector): IO[Either[StorageError, List[(LogicalKey, OrderStamp)]]] =
    runtime.run {
      sql"""SELECT logical_key, lamport, deps FROM message_order
           |WHERE conversation_id = ${conversationId.toArray}
           |ORDER BY lamport, author_device, author_seq""".stripMargin
        .query[(Array[Byte], Long, Array[Byte])]
        .to[List]
        .flatMap(_.foldLeft(FC.pure(List.empty[(LogicalKey, OrderStamp)])) { (acc, row) =>
          acc.flatMap { values =>
            val (keyRaw, lamport, depsRaw) = row
            (LogicalKey.fromBytes(ByteVector.view(keyRaw)), OrderStamp.fromBytes(ByteVector.view(depsRaw))) match
              case (Right(key), Right(deps)) => FC.pure(values :+ (key, OrderStamp(lamport, deps.deps)))
              case _                         => Sql.abort(StorageError.InvalidEnvelope)
          }
        })
    }

  def listAll(runtime: StoreRuntime): IO[Either[StorageError, Map[LogicalKey, OrderStamp]]] =
    runtime.run {
      sql"SELECT logical_key, lamport, deps FROM message_order".query[(Array[Byte], Long, Array[Byte])].to[List].flatMap {
        _.foldLeft(FC.pure(Map.empty[LogicalKey, OrderStamp])) { (acc, row) =>
          acc.flatMap { values =>
            val (keyRaw, lamport, depsRaw) = row
            (LogicalKey.fromBytes(ByteVector.view(keyRaw)), OrderStamp.fromBytes(ByteVector.view(depsRaw))) match
              case (Right(key), Right(deps)) => FC.pure(values.updated(key, OrderStamp(lamport, deps.deps)))
              case _                         => Sql.abort(StorageError.InvalidEnvelope)
          }
        }
      }
    }

  private[store] def write(key: LogicalKey, stamp: OrderStamp, createdAt: Long): ConnectionIO[Unit] =
    if key.conversationId.size != 32L then Sql.abort(StorageError.InvalidArgument("conversation_id"))
    else if stamp.lamport < 0L || createdAt < 0L then Sql.abort(StorageError.InvalidArgument("order"))
    else
      sql"""INSERT INTO message_order(
           |conversation_id, author_device, author_seq, logical_key, lamport, deps, created_at
           |) VALUES (
           |${key.conversationId.toArray}, ${key.authorDevice.bytes.toArray}, ${key.authorSeq},
           |${key.bytes.toArray}, ${stamp.lamport}, ${OrderStamp.encode(stamp.lamport, stamp.deps).toArray}, $createdAt
           |)
           |ON CONFLICT(logical_key) DO UPDATE SET lamport = excluded.lamport, deps = excluded.deps
           |""".stripMargin.update.run.flatMap(Sql.changed)
