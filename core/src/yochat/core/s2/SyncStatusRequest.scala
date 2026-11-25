package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature}

/** Primary asks the companion for its signed checkpoint after a restart/reconnect. */
final case class SyncStatusRequest(
  authHash: ByteVector,
  nonce: ByteVector,
  signature: Signature
):

  def verify(primaryDevicePub: Ed25519Public): Boolean =
    Crypto.verify(primaryDevicePub, SyncStatusRequest.signingInput(authHash, nonce), signature)

  override def toString: String = "SyncStatusRequest(<redacted>)"

object SyncStatusRequest:

  val Version: Int = 1
  private val Domain = Crypto.ascii("yochat-s2-sync-status-request")

  def create(
    primaryDevicePrivate: Ed25519Private,
    authHash: ByteVector,
    nonce: ByteVector
  ): Either[CoreError, SyncStatusRequest] =
    validate(authHash, nonce).flatMap { _ =>
      Crypto
        .sign(primaryDevicePrivate, signingInput(authHash, nonce))
        .map(signature => SyncStatusRequest(authHash, nonce, signature))
    }

  def signingInput(authHash: ByteVector, nonce: ByteVector): ByteVector =
    Domain ++ CanonicalWriter()
      .u8(Version)
      .fixed(authHash)
      .fixed(nonce)
      .result

  private def validate(authHash: ByteVector, nonce: ByteVector): Either[CoreError, Unit] =
    if authHash.size != 32L then Left(CoreError.InvalidLength("auth_hash", 32, authHash.size))
    else if nonce.size != 16L then Left(CoreError.InvalidLength("sync_status_nonce", 16, nonce.size))
    else Right(())
