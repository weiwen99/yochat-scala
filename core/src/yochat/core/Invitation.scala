package yochat.core

import scodec.bits.ByteVector

final case class Invitation private (fields: InvitationFields, signature: Signature, canonicalFields: ByteVector):

  def invitationHash: Key32 = Invitation.hash(canonicalFields)

  def canonical: ByteVector = canonicalFields ++ signature.bytes

  def text: String = Base64Url.encode(canonical)

  override def toString: String = "Invitation(<redacted>)"

object Invitation:

  private[core] val SignatureDomain = Crypto.ascii("yochat-s1-invite")
  private[core] val HashDomain      = Crypto.ascii("yochat-s1-invite-hash")

  def create(
    user: Ed25519KeyPair,
    device: Ed25519KeyPair,
    ephemeralPub: X25519Public,
    ratchetPub: X25519Public,
    channelLabel: String,
    channelAccount: String,
    expiresAt: Long,
    note: String
  ): Either[CoreError, Invitation] =
    for
      _       <- KeySeparation.distinct(user.publicKey.bytes, device.publicKey.bytes)
      _       <- KeySeparation.distinct(ephemeralPub.bytes, ratchetPub.bytes)
      label   <- Text.normalize(channelLabel, "channel_label")
      account <- Text.normalize(channelAccount, "channel_account")
      clean   <- Text.normalize(note, "note")
      fields   = InvitationFields(user.publicKey, device.publicKey, label, account, expiresAt, ephemeralPub, ratchetPub, clean)
      bytes   <- InvitationFields.encode(fields)
      sig     <- Crypto.sign(user.privateKey, Invitation.SignatureDomain ++ bytes)
    yield Invitation(fields, sig, bytes)

  def fromText(value: String): Either[CoreError, Invitation] =
    Base64Url.decode(value).flatMap(fromCanonical)

  def fromCanonical(canonical: ByteVector): Either[CoreError, Invitation] =
    for
      _         <- if canonical.size < Signature.Length then Left(CoreError.InvalidInvitation) else Right(())
      fieldBytes = canonical.dropRight(Signature.Length)
      sigBytes   = canonical.takeRight(Signature.Length)
      signature <- Signature.from(sigBytes)
      fields    <- InvitationFields.decode(fieldBytes)
      _         <- if Crypto.verify(fields.userPub, Invitation.SignatureDomain ++ fieldBytes, signature) then Right(())
                   else Left(CoreError.InvalidSignature)
    yield Invitation(fields, signature, fieldBytes)

  private[core] def hash(canonicalFields: ByteVector): Key32 =
    Crypto.sha256(HashDomain, canonicalFields)
