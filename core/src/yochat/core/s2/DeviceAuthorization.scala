package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature}

/** User-key-signed grant for one read-only companion. The full join manifest is covered so the cutoff cannot be swapped later. */
final case class DeviceAuthorization(
  userPub: Ed25519Public,
  primaryDevicePub: Ed25519Public,
  companionDevicePub: Ed25519Public,
  joinEpoch: Long,
  joinManifest: JoinManifest,
  joinCursorSyncSeq: Long,
  syncRootHash: ByteVector,
  issuedAt: Long,
  signature: Signature
):

  def joinManifestHash: ByteVector = joinManifest.hash.bytes

  def verify: Boolean =
    Crypto.verify(
      userPub,
      DeviceAuthorization.signingInput(
        userPub,
        primaryDevicePub,
        companionDevicePub,
        joinEpoch,
        joinManifest,
        joinCursorSyncSeq,
        syncRootHash,
        issuedAt
      ),
      signature
    )

  override def toString: String = s"DeviceAuthorization(joinEpoch=$joinEpoch, manifest=${joinManifest.entries.size})"

object DeviceAuthorization:

  val Version: Int   = 1
  private val Domain = Crypto.ascii("yochat-s2-device-authorization")

  def create(
    userPrivate: Ed25519Private,
    userPub: Ed25519Public,
    primaryDevicePub: Ed25519Public,
    companionDevicePub: Ed25519Public,
    joinEpoch: Long,
    joinManifest: JoinManifest,
    joinCursorSyncSeq: Long,
    syncRootHash: ByteVector,
    issuedAt: Long
  ): Either[CoreError, DeviceAuthorization] =
    validate(joinEpoch, joinCursorSyncSeq, syncRootHash, issuedAt).flatMap { _ =>
      Crypto
        .sign(
          userPrivate,
          signingInput(
            userPub,
            primaryDevicePub,
            companionDevicePub,
            joinEpoch,
            joinManifest,
            joinCursorSyncSeq,
            syncRootHash,
            issuedAt
          )
        )
        .map(signature =>
          DeviceAuthorization(
            userPub,
            primaryDevicePub,
            companionDevicePub,
            joinEpoch,
            joinManifest,
            joinCursorSyncSeq,
            syncRootHash,
            issuedAt,
            signature
          )
        )
    }

  def signingInput(
    userPub: Ed25519Public,
    primaryDevicePub: Ed25519Public,
    companionDevicePub: Ed25519Public,
    joinEpoch: Long,
    joinManifest: JoinManifest,
    joinCursorSyncSeq: Long,
    syncRootHash: ByteVector,
    issuedAt: Long
  ): ByteVector =
    Domain ++ CanonicalWriter()
      .u8(Version)
      .fixed(userPub.bytes)
      .fixed(primaryDevicePub.bytes)
      .fixed(companionDevicePub.bytes)
      .u64(joinEpoch)
      .bytes(joinManifest.canonical)
      .u64(joinCursorSyncSeq)
      .fixed(syncRootHash)
      .u64(issuedAt)
      .result

  private def validate(
    joinEpoch: Long,
    joinCursorSyncSeq: Long,
    syncRootHash: ByteVector,
    issuedAt: Long
  ): Either[CoreError, Unit] =
    if joinEpoch < 0L || joinCursorSyncSeq < 0L || issuedAt < 0L then Left(CoreError.InvalidEncoding)
    else if syncRootHash.size != 32L then Left(CoreError.InvalidLength("sync_root_hash", 32, syncRootHash.size))
    else Right(())
