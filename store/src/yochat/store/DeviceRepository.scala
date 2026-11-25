package yochat.store

import java.nio.charset.StandardCharsets

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

/** Device records are local authorization facts. The name and signed join manifest are sealed; public keys, versions and revocation
  * timestamps remain plaintext metadata. The high-water key prevents a deleted/rolled-back row from being silently reinserted.
  */
private[store] object DeviceRepository:

  private val MaxNameBytes: Int = 256

  final private case class Row(
    devicePub: Array[Byte],
    userPub: Array[Byte],
    role: Int,
    name: Array[Byte],
    authorization: Array[Byte],
    version: Long,
    createdAt: Long,
    revokedAt: Option[Long],
    joinEpoch: Long,
    joinManifest: Array[Byte],
    joinManifestHash: Array[Byte],
    joinCursorSyncSeq: Long,
    pairingNonce: Option[Array[Byte]],
    requestHash: Option[Array[Byte]]
  )

  def insert(
    runtime: StoreRuntime,
    record: DeviceRecord,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _           <- Sql.checkLock(expectedLockGeneration)
          _           <- validateRecord(record)
          tombstone   <- Sql.meta(Sql.deviceVersionKey(record.devicePub.bytes))
          _           <- tombstone match
                           case Some(_) => Sql.abort(StorageError.StaleGeneration)
                           case None    => FC.unit
          _           <- row(record.devicePub.bytes).flatMap {
                           case Some(_) => Sql.abort(StorageError.WriteConflict)
                           case None    => FC.unit
                         }
          sealedName  <- seal(runtime, record, ByteVector.view(record.name.getBytes(StandardCharsets.UTF_8)))
          sealedManifest <- seal(runtime, record, record.joinManifest)
          changed     <-
            sql"""INSERT INTO devices(
                 |device_pub, user_pub, role, name, authorization, version, created_at, revoked_at,
                 |join_epoch, join_manifest, join_manifest_hash, join_cursor_sync_seq, pairing_nonce, request_hash
                 |) VALUES (
                 |${record.devicePub.bytes.toArray}, ${record.userPub.bytes.toArray}, ${record.role.code}, $sealedName,
                 |${record.authorization.toArray}, 1, $now, ${record.revokedAt},
                 |${record.joinEpoch}, $sealedManifest, ${record.joinManifestHash.toArray}, ${record.joinCursorSyncSeq},
                 |${record.pairingNonce.map(_.toArray)}, ${record.requestHash.map(_.toArray)}
                 |)""".stripMargin.update.run
          _           <- Sql.changed(changed)
          _           <- Sql.writeMeta(Sql.deviceVersionKey(record.devicePub.bytes), 1L)
        yield record.copy(version = 1L, createdAt = now)
      }
    }

  def load(runtime: StoreRuntime, devicePub: Ed25519Public): IO[Either[StorageError, DeviceRecord]] =
    runtime.run {
      loadProgram(runtime, devicePub.bytes).flatMap {
        case Some(value) => FC.pure(value)
        case None        => Sql.abort(StorageError.NotFound("device"))
      }
    }

  def list(runtime: StoreRuntime): IO[Either[StorageError, List[DeviceRecord]]] =
    runtime.run {
      sql"SELECT device_pub FROM devices ORDER BY device_pub".query[Array[Byte]].to[List].flatMap { keys =>
        keys.foldLeft(FC.pure(List.empty[DeviceRecord])) { (acc, key) =>
          acc.flatMap { values =>
            Ed25519Public.from(ByteVector.view(key)) match
              case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
              case Right(public) =>
                loadProgram(runtime, public.bytes).flatMap {
                  case Some(record) => FC.pure(values :+ record)
                  case None         => Sql.abort(StorageError.NotFound("device"))
                }
          }
        }
      }
    }

  def revoke(
    runtime: StoreRuntime,
    devicePub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceRecord]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          _       <- Sql.checkLock(expectedLockGeneration)
          current <- loadProgram(runtime, devicePub.bytes).flatMap {
                       case Some(value) => FC.pure(value)
                       case None        => Sql.abort(StorageError.NotFound("device"))
                     }
          _       <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
          next    <- Sql.nextVersion(current.version)
          changed <-
            sql"UPDATE devices SET revoked_at = $now, version = $next WHERE device_pub = ${devicePub.bytes.toArray} AND version = ${current.version}".update.run
          _       <- Sql.changed(changed)
          _       <- Sql.writeMeta(Sql.deviceVersionKey(devicePub.bytes), next)
        yield current.copy(version = next, revokedAt = Some(now))
      }
    }

  private def loadProgram(runtime: StoreRuntime, devicePub: ByteVector): ConnectionIO[Option[DeviceRecord]] =
    row(devicePub).flatMap {
      case None       => FC.pure(None)
      case Some(row) =>
        Ed25519Public.from(ByteVector.view(row.devicePub)) match
          case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
          case Right(public) =>
            for
              _        <- Sql.checkHighWater(Sql.deviceVersionKey(public.bytes), row.version)
              role     <- fromEither(DeviceRole.fromCode(row.role))
              plainName <- open(runtime, row, ByteVector.view(row.name))
              plainManifest <- open(runtime, row, ByteVector.view(row.joinManifest))
              _        <- if plainName.size <= MaxNameBytes.toLong then FC.unit else Sql.abort(StorageError.InvalidEnvelope)
              _        <- if plainManifest.size == 0L || plainManifest.size > 1_048_576L then Sql.abort(StorageError.InvalidEnvelope)
                          else FC.unit
              name     <- FC.delay(new String(plainName.toArray, StandardCharsets.UTF_8))
              user     <- Ed25519Public.from(ByteVector.view(row.userPub)) match
                            case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
                            case Right(value)  => FC.pure(value)
              _        <- if row.joinManifestHash.length == 32 then FC.unit else Sql.abort(StorageError.InvalidEnvelope)
              _        <- if row.authorization.nonEmpty then FC.unit else Sql.abort(StorageError.InvalidEnvelope)
            yield Some(
              DeviceRecord(
                devicePub = public,
                userPub = user,
                role = role,
                name = name,
                authorization = ByteVector.view(row.authorization),
                version = row.version,
                createdAt = row.createdAt,
                revokedAt = row.revokedAt,
                joinEpoch = row.joinEpoch,
                joinManifest = plainManifest,
                joinManifestHash = ByteVector.view(row.joinManifestHash),
                joinCursorSyncSeq = row.joinCursorSyncSeq,
                pairingNonce = row.pairingNonce.map(ByteVector.view),
                requestHash = row.requestHash.map(ByteVector.view)
              )
            )
    }

  private def row(devicePub: ByteVector): ConnectionIO[Option[Row]] =
    sql"""SELECT device_pub, user_pub, role, name, authorization, version, created_at, revoked_at,
         |join_epoch, join_manifest, join_manifest_hash, join_cursor_sync_seq, pairing_nonce, request_hash
         |FROM devices WHERE device_pub = ${devicePub.toArray}""".stripMargin
      .query[
        (
          Array[Byte],
          Array[Byte],
          Int,
          Array[Byte],
          Array[Byte],
          Long,
          Long,
          Option[Long],
          Long,
          Array[Byte],
          Array[Byte],
          Long,
          Option[Array[Byte]],
          Option[Array[Byte]]
        )
      ]
      .option
      .map {
        case None                                                                                                              => None
        case Some((device, user, role, name, auth, version, created, revoked, epoch, manifest, manifestHash, cursor, nonce, req)) =>
          Some(Row(device, user, role, name, auth, version, created, revoked, epoch, manifest, manifestHash, cursor, nonce, req))
      }

  private def validateRecord(record: DeviceRecord): ConnectionIO[Unit] =
    val rawName = ByteVector.view(record.name.getBytes(StandardCharsets.UTF_8))
    if rawName.isEmpty || rawName.size > MaxNameBytes.toLong then Sql.abort(StorageError.InvalidArgument("device_name"))
    else if record.authorization.isEmpty then Sql.abort(StorageError.InvalidArgument("device_authorization"))
    else if record.joinManifestHash.size != 32L then Sql.abort(StorageError.InvalidArgument("join_manifest_hash"))
    else if record.joinCursorSyncSeq < 0L then Sql.abort(StorageError.InvalidArgument("join_cursor"))
    else if record.devicePub.bytes == record.userPub.bytes then Sql.abort(StorageError.InvalidArgument("device_user"))
    else FC.unit

  private def seal(runtime: StoreRuntime, record: DeviceRecord, plaintext: ByteVector): ConnectionIO[Array[Byte]] =
    FC.delay(StoreCrypto.randomNonceUnsafe()).flatMap { nonce =>
      fromEither(
        StoreCrypto.seal(
          runtime.keys.deviceKey,
          nonce,
          StoreCrypto.deviceAad(record.devicePub.bytes, record.userPub.bytes, 1L),
          plaintext
        )
      )
    }

  private def open(runtime: StoreRuntime, row: Row, envelope: ByteVector): ConnectionIO[ByteVector] =
    Ed25519Public.from(ByteVector.view(row.devicePub)) match
      case Left(_)       => Sql.abort(StorageError.InvalidEnvelope)
      case Right(device) =>
        Ed25519Public.from(ByteVector.view(row.userPub)) match
          case Left(_)      => Sql.abort(StorageError.InvalidEnvelope)
          case Right(user)  =>
            fromEither(
              StoreCrypto.open(
                runtime.keys.deviceKey,
                StoreCrypto.deviceAad(device.bytes, user.bytes, row.version),
                envelope.toArray
              )
            )

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)
