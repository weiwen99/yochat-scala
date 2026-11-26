package yochat.server

import java.nio.charset.StandardCharsets

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.{Crypto, Key32}
import yochat.core.s2.DeviceAuthorization
import yochat.store.{DeviceRecord, DeviceRole, DeviceSyncRecord, Store}

/** Shared A2 persistence for CLI and browser pairing. A2 stores its own device authorization and the sync root; neither value is derived from
  * A1's user private key.
  */
private[server] object DevicePairingStore:

  def persist(
    store: Store,
    authCode: String,
    auth: DeviceAuthorization,
    root: Key32,
    name: String,
    requestHash: Option[ByteVector]
  ): IO[Either[String, Unit]] =
    val record = DeviceRecord(
      devicePub = store.identity.devicePublic,
      userPub = auth.userPub,
      role = DeviceRole.Companion,
      name = name,
      authorization = ByteVector.view(authCode.getBytes(StandardCharsets.UTF_8)),
      version = 0L,
      createdAt = 0L,
      revokedAt = None,
      joinEpoch = auth.joinEpoch,
      joinManifest = auth.joinManifest.canonical,
      joinManifestHash = auth.joinManifestHash,
      joinCursorSyncSeq = auth.joinCursorSyncSeq,
      pairingNonce = None,
      requestHash = requestHash
    )
    val sync   = DeviceSyncRecord(
      peerDevicePub = auth.primaryDevicePub,
      syncRoot = root.bytes,
      lastAckedSyncSeq = auth.joinCursorSyncSeq,
      lastBatchSeq = 0L,
      lastBatchHash = ByteVector.fill(32)(0),
      version = 0L,
      updatedAt = 0L
    )
    if Crypto.sha256(root.bytes).bytes != auth.syncRootHash then IO.pure(Left("sync-root"))
    else
      store.lockGeneration.flatMap {
        case Left(error) => IO.pure(Left(error.code))
        case Right(lock) =>
          store.insertDevice(record, lock).flatMap {
            case Left(error) => IO.pure(Left(error.code))
            case Right(_)    => store.insertDeviceSync(sync, lock).map(_.map(_ => ()).left.map(_.code))
          }
      }
