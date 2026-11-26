package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature, X25519Public}

/** Companion-to-primary enrollment request. Proof is made by the device key that will be authorized. */
final case class PairingRequest(
  pairingNonce: ByteVector,
  companionDevicePub: Ed25519Public,
  companionEphemeralPub: X25519Public,
  encryptedName: ByteVector,
  proof: Signature
):

  def verify: Boolean =
    Crypto.verify(
      companionDevicePub,
      PairingRequest.signingInput(
        pairingNonce,
        companionDevicePub,
        companionEphemeralPub,
        encryptedName
      ),
      proof
    )

  def requestHash: ByteVector = Crypto.sha256(canonical).bytes

  def canonical: ByteVector =
    PairingRequest.canonical(pairingNonce, companionDevicePub, companionEphemeralPub, encryptedName)

  override def toString: String = s"PairingRequest(encryptedNameBytes=${encryptedName.size})"

object PairingRequest:

  val Version: Int      = 1
  val MaxNameBytes: Int = 512
  private val Domain    = Crypto.ascii("yochat-s2-pairing-request")

  def create(
    companionDevicePrivate: Ed25519Private,
    pairingNonce: ByteVector,
    companionDevicePub: Ed25519Public,
    companionEphemeralPub: X25519Public,
    encryptedName: ByteVector
  ): Either[CoreError, PairingRequest] =
    validate(pairingNonce, encryptedName).flatMap { _ =>
      Crypto
        .sign(
          companionDevicePrivate,
          signingInput(pairingNonce, companionDevicePub, companionEphemeralPub, encryptedName)
        )
        .map(proof => PairingRequest(pairingNonce, companionDevicePub, companionEphemeralPub, encryptedName, proof))
    }

  def signingInput(
    pairingNonce: ByteVector,
    companionDevicePub: Ed25519Public,
    companionEphemeralPub: X25519Public,
    encryptedName: ByteVector
  ): ByteVector =
    Domain ++ canonical(pairingNonce, companionDevicePub, companionEphemeralPub, encryptedName)

  def canonical(
    pairingNonce: ByteVector,
    companionDevicePub: Ed25519Public,
    companionEphemeralPub: X25519Public,
    encryptedName: ByteVector
  ): ByteVector =
    CanonicalWriter()
      .u8(Version)
      .fixed(pairingNonce)
      .fixed(companionDevicePub.bytes)
      .fixed(companionEphemeralPub.bytes)
      .bytes(encryptedName)
      .result

  private def validate(pairingNonce: ByteVector, encryptedName: ByteVector): Either[CoreError, Unit] =
    if pairingNonce.size != 16L then Left(CoreError.InvalidLength("pairing_nonce", 16, pairingNonce.size))
    else if encryptedName.isEmpty || encryptedName.size > MaxNameBytes.toLong then Left(CoreError.InvalidEncoding)
    else Right(())
