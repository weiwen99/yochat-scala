package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature}

/** Companion checkpoint. The whole tuple is signed together so a cursor cannot be paired with another batch hash. */
final case class SyncStatus(
  authHash: ByteVector,
  lastBatchSeq: Long,
  lastBatchHash: ByteVector,
  lastAckedSyncSeq: Long,
  signature: Signature
):

  def verify(companionDevicePub: Ed25519Public): Boolean =
    Crypto.verify(
      companionDevicePub,
      SyncStatus.signingInput(authHash, lastBatchSeq, lastBatchHash, lastAckedSyncSeq),
      signature
    )

  override def toString: String =
    s"SyncStatus(lastBatchSeq=$lastBatchSeq, lastAckedSyncSeq=$lastAckedSyncSeq)"

object SyncStatus:

  val Version: Int = 1
  private val Domain = Crypto.ascii("yochat-s2-sync-status")

  def create(
    companionDevicePrivate: Ed25519Private,
    authHash: ByteVector,
    lastBatchSeq: Long,
    lastBatchHash: ByteVector,
    lastAckedSyncSeq: Long
  ): Either[CoreError, SyncStatus] =
    validate(authHash, lastBatchSeq, lastBatchHash, lastAckedSyncSeq).flatMap { _ =>
      Crypto
        .sign(companionDevicePrivate, signingInput(authHash, lastBatchSeq, lastBatchHash, lastAckedSyncSeq))
        .map(signature =>
          SyncStatus(authHash, lastBatchSeq, lastBatchHash, lastAckedSyncSeq, signature)
        )
    }

  def signingInput(
    authHash: ByteVector,
    lastBatchSeq: Long,
    lastBatchHash: ByteVector,
    lastAckedSyncSeq: Long
  ): ByteVector =
    Domain ++ CanonicalWriter()
      .u8(Version)
      .fixed(authHash)
      .u64(lastBatchSeq)
      .fixed(lastBatchHash)
      .u64(lastAckedSyncSeq)
      .result

  private def validate(
    authHash: ByteVector,
    lastBatchSeq: Long,
    lastBatchHash: ByteVector,
    lastAckedSyncSeq: Long
  ): Either[CoreError, Unit] =
    if authHash.size != 32L then Left(CoreError.InvalidLength("auth_hash", 32, authHash.size))
    else if lastBatchHash.size != 32L then Left(CoreError.InvalidLength("last_batch_hash", 32, lastBatchHash.size))
    else if lastBatchSeq < 0L || lastAckedSyncSeq < 0L then Left(CoreError.InvalidEncoding)
    else Right(())
