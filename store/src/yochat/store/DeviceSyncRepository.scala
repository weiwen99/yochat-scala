package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

/** Durable sync root and checkpoint for one companion device. The root is sealed with the local device key. */
private[store] object DeviceSyncRepository:

  def insert(
    runtime: StoreRuntime,
    record: DeviceSyncRecord,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceSyncRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _          <- Sql.checkLock(expectedLockGeneration)
          _          <- validate(record)
          tombstone  <- Sql.meta(Sql.deviceSyncVersionKey(record.peerDevicePub.bytes))
          _          <- tombstone match
                          case Some(_) => Sql.abort(StorageError.StaleGeneration)
                          case None    => FC.unit
          existing   <- row(record.peerDevicePub.bytes)
          _          <- existing match
                          case Some(_) => Sql.abort(StorageError.WriteConflict)
                          case None    => FC.unit
          sealedRoot <- seal(runtime, record, 1L)
          changed    <-
            sql"""INSERT INTO device_sync(
                 |peer_device_pub, sync_root, last_acked_sync_seq, last_batch_seq, last_batch_hash, version, updated_at
                 |) VALUES (
                 |${record.peerDevicePub.bytes.toArray}, $sealedRoot, ${record.lastAckedSyncSeq}, ${record.lastBatchSeq},
                 |${record.lastBatchHash.toArray}, 1, $now
                 |)""".stripMargin.update.run
          _          <- Sql.changed(changed)
          _          <- Sql.writeMeta(Sql.deviceSyncVersionKey(record.peerDevicePub.bytes), 1L)
        yield record.copy(version = 1L, updatedAt = now)
      }
    }

  def load(runtime: StoreRuntime, peerDevicePub: Ed25519Public): IO[Either[StorageError, DeviceSyncRecord]] =
    runtime.run {
      loadProgram(runtime, peerDevicePub.bytes).flatMap {
        case Some(value) => FC.pure(value)
        case None        => Sql.abort(StorageError.NotFound("device-sync"))
      }
    }

  def update(
    runtime: StoreRuntime,
    record: DeviceSyncRecord,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceSyncRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _          <- Sql.checkLock(expectedLockGeneration)
          _          <- validate(record)
          current    <- loadProgram(runtime, record.peerDevicePub.bytes).flatMap {
                          case Some(value) => FC.pure(value)
                          case None        => Sql.abort(StorageError.NotFound("device-sync"))
                        }
          _          <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
          next       <- Sql.nextVersion(current.version)
          sealedRoot <- seal(runtime, record, next)
          changed    <-
            sql"""UPDATE device_sync SET
                 |sync_root = $sealedRoot,
                 |last_acked_sync_seq = ${record.lastAckedSyncSeq},
                 |last_batch_seq = ${record.lastBatchSeq},
                 |last_batch_hash = ${record.lastBatchHash.toArray},
                 |version = $next,
                 |updated_at = $now
                 |WHERE peer_device_pub = ${record.peerDevicePub.bytes.toArray} AND version = ${current.version}
                 |""".stripMargin.update.run
          _          <- Sql.changed(changed)
          _          <- Sql.writeMeta(Sql.deviceSyncVersionKey(record.peerDevicePub.bytes), next)
        yield record.copy(version = next, updatedAt = now)
      }
    }

  def delete(
    runtime: StoreRuntime,
    peerDevicePub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        _       <- Sql.checkLock(expectedLockGeneration)
        current <- loadProgram(runtime, peerDevicePub.bytes).flatMap {
                     case Some(value) => FC.pure(value)
                     case None        => Sql.abort(StorageError.NotFound("device-sync"))
                   }
        _       <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        deleted <-
          sql"DELETE FROM device_sync WHERE peer_device_pub = ${peerDevicePub.bytes.toArray} AND version = ${current.version}".update.run
        _       <- Sql.changed(deleted)
        next    <- Sql.nextVersion(current.version)
        _       <- Sql.writeMeta(Sql.deviceSyncVersionKey(peerDevicePub.bytes), next)
      yield ()
    }

  private def loadProgram(runtime: StoreRuntime, peerDevicePub: ByteVector): ConnectionIO[Option[DeviceSyncRecord]] =
    row(peerDevicePub).flatMap {
      case None        => FC.pure(None)
      case Some(value) =>
        Ed25519Public.from(value.peerDevicePub) match
          case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
          case Right(public) =>
            for
              _    <- Sql.checkHighWater(Sql.deviceSyncVersionKey(public.bytes), value.version)
              root <- open(runtime, value)
            yield Some(
              DeviceSyncRecord(
                peerDevicePub = public,
                syncRoot = root,
                lastAckedSyncSeq = value.lastAckedSyncSeq,
                lastBatchSeq = value.lastBatchSeq,
                lastBatchHash = value.lastBatchHash,
                version = value.version,
                updatedAt = value.updatedAt
              )
            )
    }

  private def row(peerDevicePub: ByteVector): ConnectionIO[Option[SyncRow]] =
    sql"""SELECT peer_device_pub, sync_root, last_acked_sync_seq, last_batch_seq, last_batch_hash, version, updated_at
         |FROM device_sync WHERE peer_device_pub = ${peerDevicePub.toArray}""".stripMargin
      .query[(Array[Byte], Array[Byte], Long, Long, Array[Byte], Long, Long)]
      .option
      .map {
        case None                                                     => None
        case Some((peer, root, acked, batch, hash, version, updated)) =>
          Some(SyncRow(ByteVector.view(peer), ByteVector.view(root), acked, batch, ByteVector.view(hash), version, updated))
      }

  private def seal(runtime: StoreRuntime, record: DeviceSyncRecord, version: Long): ConnectionIO[Array[Byte]] =
    FC.delay(StoreCrypto.randomNonceUnsafe()).flatMap { nonce =>
      fromEither(
        StoreCrypto.seal(
          runtime.keys.deviceKey,
          nonce,
          StoreCrypto.deviceSyncAad(record.peerDevicePub.bytes, version),
          record.syncRoot
        )
      )
    }

  private def open(runtime: StoreRuntime, row: SyncRow): ConnectionIO[ByteVector] =
    Ed25519Public.from(row.peerDevicePub) match
      case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
      case Right(public) =>
        fromEither(
          StoreCrypto.open(
            runtime.keys.deviceKey,
            StoreCrypto.deviceSyncAad(public.bytes, row.version),
            row.syncRoot.toArray
          )
        ).flatMap { root =>
          if root.size == 32L then FC.pure(root) else Sql.abort(StorageError.InvalidEnvelope)
        }

  private def validate(record: DeviceSyncRecord): ConnectionIO[Unit] =
    if record.syncRoot.size != 32L then Sql.abort(StorageError.InvalidArgument("sync_root"))
    else if record.lastAckedSyncSeq < 0L || record.lastBatchSeq < 0L then Sql.abort(StorageError.InvalidArgument("sync_cursor"))
    else if record.lastBatchHash.nonEmpty && record.lastBatchHash.size != 32L then Sql.abort(StorageError.InvalidArgument("batch_hash"))
    else FC.unit

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)

  final private case class SyncRow(
    peerDevicePub: ByteVector,
    syncRoot: ByteVector,
    lastAckedSyncSeq: Long,
    lastBatchSeq: Long,
    lastBatchHash: ByteVector,
    version: Long,
    updatedAt: Long
  )
