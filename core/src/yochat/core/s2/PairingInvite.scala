package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature, X25519Public}

/** One-time primary-device invitation. The code is a bearer secret carried out of band; the signature binds the user and primary device. */
final case class PairingInvite(
  userPub: Ed25519Public,
  primaryDevicePub: Ed25519Public,
  ephemeralPub: X25519Public,
  nonce: ByteVector,
  expiresAt: Long,
  signature: Signature
):

  def verify: Boolean =
    Crypto.verify(
      userPub,
      PairingInvite.signingInput(userPub, primaryDevicePub, ephemeralPub, nonce, expiresAt),
      signature
    )

  override def toString: String = s"PairingInvite(expiresAt=$expiresAt)"

object PairingInvite:

  val Version: Int = 1
  private val Domain = Crypto.ascii("yochat-s2-pairing-invite")

  def create(
    userPrivate: Ed25519Private,
    userPub: Ed25519Public,
    primaryDevicePub: Ed25519Public,
    ephemeralPub: X25519Public,
    nonce: ByteVector,
    expiresAt: Long
  ): Either[CoreError, PairingInvite] =
    validate(nonce, expiresAt).flatMap { _ =>
      Crypto
        .sign(userPrivate, signingInput(userPub, primaryDevicePub, ephemeralPub, nonce, expiresAt))
        .map(signature => PairingInvite(userPub, primaryDevicePub, ephemeralPub, nonce, expiresAt, signature))
    }

  def signingInput(
    userPub: Ed25519Public,
    primaryDevicePub: Ed25519Public,
    ephemeralPub: X25519Public,
    nonce: ByteVector,
    expiresAt: Long
  ): ByteVector =
    Domain ++ CanonicalWriter()
      .u8(Version)
      .fixed(userPub.bytes)
      .fixed(primaryDevicePub.bytes)
      .fixed(ephemeralPub.bytes)
      .fixed(nonce)
      .u64(expiresAt)
      .result

  private def validate(nonce: ByteVector, expiresAt: Long): Either[CoreError, Unit] =
    if nonce.size != 16L then Left(CoreError.InvalidLength("pairing_nonce", 16, nonce.size))
    else if expiresAt < 0L then Left(CoreError.InvalidEncoding)
    else Right(())
