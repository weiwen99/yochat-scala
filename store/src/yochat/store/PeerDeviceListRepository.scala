package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.s2.PeerDeviceList
import yochat.core.Ed25519Public

/** Contact-visible signed device-list snapshot. One row per contact user; B1 has a single user-key signer. */
private[store] object PeerDeviceListRepository:

  def upsert(
    runtime: StoreRuntime,
    list: PeerDeviceList,
    receivedAt: Long
  ): IO[Either[StorageError, PeerDeviceList]] =
    runtime.run {
      for
        _             <- if list.verify then FC.unit else Sql.abort(StorageError.InvalidEnvelope)
        _             <- if receivedAt < 0L then Sql.abort(StorageError.InvalidArgument("received_at")) else FC.unit
        current       <- row(list.userPub.bytes)
        _             <- current match
                           case None                                                           => FC.unit
                           case Some((version, _, _)) if version > list.version                => Sql.abort(StorageError.StaleGeneration)
                           case Some((version, existingPayload, _)) if version == list.version =>
                             if ByteVector.view(existingPayload).equals(list.bytes) then FC.unit
                             else Sql.abort(StorageError.WriteConflict)
                           case Some((previousVersion, previousPayload, _))                    =>
                             open(runtime, list.userPub, previousVersion, previousPayload).flatMap { raw =>
                               PeerDeviceList.fromBytes(raw) match
                                 case Left(_)      => Sql.abort(StorageError.InvalidEnvelope)
                                 case Right(prior) =>
                                   if prior.revoked.keySet.subsetOf(list.revoked.keySet) then FC.unit
                                   else Sql.abort(StorageError.InvalidArgument("revocation-rollback"))
                             }
        sealedPayload <- seal(runtime, list)
        _             <- sql"""INSERT INTO peer_device_lists(user_pub, version, payload, received_at)
                       |VALUES (${list.userPub.bytes.toArray}, ${list.version}, $sealedPayload, $receivedAt)
                       |ON CONFLICT(user_pub) DO UPDATE SET
                       |version = excluded.version,
                       |payload = excluded.payload,
                       |received_at = excluded.received_at
                       |""".stripMargin.update.run.flatMap(Sql.changed)
      yield list
    }

  def load(runtime: StoreRuntime, userPub: Ed25519Public): IO[Either[StorageError, PeerDeviceList]] =
    runtime.run {
      row(userPub.bytes).flatMap {
        case None                        => Sql.abort(StorageError.NotFound("peer-device-list"))
        case Some((version, payload, _)) =>
          open(runtime, userPub, version, payload).flatMap { bytes =>
            PeerDeviceList.fromBytes(bytes) match
              case Right(list) => FC.pure(list)
              case Left(_)     => Sql.abort(StorageError.InvalidEnvelope)
          }
      }
    }

  private def row(userPub: ByteVector): ConnectionIO[Option[(Long, Array[Byte], Long)]] =
    sql"SELECT version, payload, received_at FROM peer_device_lists WHERE user_pub = ${userPub.toArray}"
      .query[(Long, Array[Byte], Long)]
      .option

  private def seal(runtime: StoreRuntime, list: PeerDeviceList): ConnectionIO[Array[Byte]] =
    FC.delay(StoreCrypto.randomNonceUnsafe()).flatMap { nonce =>
      fromEither(
        StoreCrypto.seal(
          runtime.keys.peerDeviceListKey,
          nonce,
          StoreCrypto.peerDeviceListAad(list.userPub.bytes, list.version),
          list.bytes
        )
      )
    }

  private def open(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    version: Long,
    payload: Array[Byte]
  ): ConnectionIO[ByteVector] =
    fromEither(
      StoreCrypto.open(
        runtime.keys.peerDeviceListKey,
        StoreCrypto.peerDeviceListAad(userPub.bytes, version),
        payload
      )
    )

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)
