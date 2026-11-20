package yochat.core

import scodec.bits.ByteVector

final case class InvitationFields(
  userPub: Ed25519Public,
  devicePub: Ed25519Public,
  channelLabel: String,
  channelAccount: String,
  expiresAt: Long,
  ephPub: X25519Public,
  ratchetPub: X25519Public,
  note: String
):

  override def toString: String = "InvitationFields(<redacted>)"

private[core] object InvitationFields:

  val Version: Int = 1

  def encode(fields: InvitationFields): Either[CoreError, ByteVector] =
    for
      label   <- Text.canonical(fields.channelLabel)
      account <- Text.canonical(fields.channelAccount)
      note    <- Text.canonical(fields.note)
      _       <- if fields.expiresAt < 0L then Left(CoreError.InvalidInvitation) else Right(())
    yield CanonicalWriter()
      .u8(Version)
      .fixed(fields.userPub.bytes)
      .fixed(fields.devicePub.bytes)
      .bytes(label)
      .bytes(account)
      .u64(fields.expiresAt)
      .fixed(fields.ephPub.bytes)
      .fixed(fields.ratchetPub.bytes)
      .bytes(note)
      .result

  def decode(value: ByteVector): Either[CoreError, InvitationFields] =
    val reader = CanonicalReader(value)
    for
      (version, r1)    <- reader.u8
      _                <- expectVersion(version)
      (userRaw, r2)    <- r1.fixed(Ed25519Public.Length)
      userPub          <- Ed25519Public.from(userRaw)
      (deviceRaw, r3)  <- r2.fixed(Ed25519Public.Length)
      devicePub        <- Ed25519Public.from(deviceRaw)
      (label, r4)      <- readText(r3, "channel_label")
      (account, r5)    <- readText(r4, "channel_account")
      (expiresAt, r6)  <- r5.u64
      (ephRaw, r7)     <- r6.fixed(X25519Public.Length)
      ephPub           <- X25519Public.from(ephRaw)
      (ratchetRaw, r8) <- r7.fixed(X25519Public.Length)
      ratchetPub       <- X25519Public.from(ratchetRaw)
      (note, r9)       <- readText(r8, "note")
      _                <- r9.finish
    yield InvitationFields(userPub, devicePub, label, account, expiresAt, ephPub, ratchetPub, note)

  private def expectVersion(value: Int): Either[CoreError, Unit] =
    if value == Version then Right(()) else Left(CoreError.InvalidVersion(value))

  private def readText(reader: CanonicalReader, field: String): Either[CoreError, (String, CanonicalReader)] =
    reader.string(Text.MaxFieldBytes).flatMap { case (value, next) =>
      Text.normalize(value, field).flatMap { normalized =>
        if normalized == value then Right((normalized, next)) else Left(CoreError.InvalidEncoding)
      }
    }
