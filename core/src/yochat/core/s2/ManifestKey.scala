package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CoreError, Ed25519Public}

/** Authenticated identity of one per-author message stream at enrollment time. */
final case class ManifestKey(
  conversationId: ByteVector,
  authorPub: Ed25519Public,
  devicePub: Ed25519Public
):

  override def toString: String = "ManifestKey(<redacted>)"

object ManifestKey:

  def create(
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public
  ): Either[CoreError, ManifestKey] =
    if conversationId.size == 32L then Right(ManifestKey(conversationId, authorPub, devicePub))
    else Left(CoreError.InvalidLength("conversation_id", 32, conversationId.size))

  def ordering(value: ManifestKey): (String, String, String) =
    (value.conversationId.toHex, value.authorPub.bytes.toHex, value.devicePub.bytes.toHex)
