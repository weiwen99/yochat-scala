package yochat.store

import java.io.ByteArrayOutputStream
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer
import java.text.Normalizer

import scodec.bits.ByteVector

import yochat.core.X25519Private

enum ContactConfirmation:
  case Pending, Confirmed, AccountMismatch

/** `Deleted` keeps the row as a tombstone; the relationship is gone but the row version still guards against rollback. `Recreated` marks a
  * relationship that was rebuilt from a new invitation after a deletion.
  */
enum ContactState:
  case Active, Deleted

enum InvitationState:
  case Pending, Accepted, Declined, Expired, Consumed, PendingReply

final case class ContactData(
  channelLabel: String,
  channelAccount: String,
  peerChannelAccount: Option[String],
  alias: Option[String],
  note: Option[String],
  safetyVerified: Boolean,
  confirmationState: ContactConfirmation,
  state: ContactState = ContactState.Active,
  recreated: Boolean = false
):

  override def toString: String = s"ContactData(confirmation=$confirmationState, safetyVerified=$safetyVerified, state=$state)"

final case class InvitationData(
  ephemeralPrivate: Option[X25519Private],
  ratchetPrivate: Option[X25519Private],
  peerAccount: Option[String],
  state: InvitationState,
  confirmationState: ContactConfirmation,
  payload: ByteVector,
  expiresAt: Long,
  replyText: Option[String] = None,
  replyAttempts: Int = 0
):

  override def toString: String = s"InvitationData(state=$state, confirmation=$confirmationState)"

private[store] object StoreDataCodec:

  private val ContactVersion: Int     = 2
  private val InvitationVersion: Int  = 1
  private val InvitationVersion2: Int = 2
  private val InvitationVersion3: Int = 3
  private val MaxFieldBytes: Int      = 4096
  private val MaxPayloadBytes: Int    = 1_048_576

  def encodeContact(value: ContactData): Either[StorageError, ByteVector] =
    for
      label   <- writeString(value.channelLabel, "channel_label")
      account <- writeString(value.channelAccount, "channel_account")
      peer    <- writeOptionString(value.peerChannelAccount, "peer_channel_account")
      alias   <- writeOptionString(value.alias, "alias")
      note    <- writeOptionString(value.note, "note")
    yield
      val writer = CodecWriter()
        .u8(ContactVersion)
        .bytes(label)
        .bytes(account)
      writeOptionBytes(writer, peer)
      writeOptionBytes(writer, alias)
      writeOptionBytes(writer, note)
      writer
        .u8(if value.safetyVerified then 1 else 0)
        .u8(confirmationCode(value.confirmationState))
        .u8(contactStateCode(value.state))
        .u8(if value.recreated then 1 else 0)
        .result

  def decodeContact(value: ByteVector): Either[StorageError, ContactData] =
    val reader = CodecReader(value)
    for
      (version, r1)      <- reader.u8
      _                  <- if version == 1 || version == ContactVersion then Right(()) else Left(StorageError.InvalidEnvelope)
      (label, r2)        <- r1.string(MaxFieldBytes)
      (account, r3)      <- r2.string(MaxFieldBytes)
      (peer, r4)         <- readOptionString(r3)
      (alias, r5)        <- readOptionString(r4)
      (note, r6)         <- readOptionString(r5)
      (safety, r7)       <- r6.u8
      safetyVerified     <- if safety == 0 || safety == 1 then Right(safety == 1) else Left(StorageError.InvalidEnvelope)
      (confirmation, r8) <- r7.u8
      confirmationState  <- confirmationFromCode(confirmation)
      decoded            <-
        if version == 1 then r8.finish.map(_ => ContactData(label, account, peer, alias, note, safetyVerified, confirmationState))
        else
          for
            (stateCode, r9)      <- r8.u8
            state                <- contactStateFromCode(stateCode)
            (recreatedCode, r10) <- r9.u8
            recreated            <- if recreatedCode == 0 || recreatedCode == 1 then Right(recreatedCode == 1) else Left(StorageError.InvalidEnvelope)
            _                    <- r10.finish
          yield ContactData(label, account, peer, alias, note, safetyVerified, confirmationState, state, recreated)
    yield decoded

  def encodeInvitation(value: InvitationData): Either[StorageError, ByteVector] =
    if value.payload.size > MaxPayloadBytes.toLong then Left(StorageError.InvalidArgument("payload"))
    else if value.expiresAt < 0L then Left(StorageError.InvalidArgument("expires_at"))
    else if value.replyAttempts < 0 || value.replyAttempts > 1_000 then Left(StorageError.InvalidArgument("reply_attempts"))
    else
      for
        peer  <- writeOptionString(value.peerAccount, "peer_account")
        reply <- writeOptionString(value.replyText, "reply_text")
      yield
        val writer = CodecWriter()
          .u8(InvitationVersion3)
        writeOptionFixed(writer, value.ephemeralPrivate.map(_.bytes))
        writeOptionFixed(writer, value.ratchetPrivate.map(_.bytes))
        writeOptionBytes(writer, peer)
        writer
          .u8(invitationCode(value.state))
          .u8(confirmationCode(value.confirmationState))
          .bytes(value.payload)
          .u64(value.expiresAt)
        writeOptionBytes(writer, reply)
        writer.u32(value.replyAttempts.toLong).result

  def decodeInvitation(value: ByteVector): Either[StorageError, InvitationData] =
    val reader = CodecReader(value)
    for
      (version, r1) <- reader.u8
      decoded       <- if version == InvitationVersion then decodeLegacyInvitation(r1) else decodeCurrentInvitation(version, r1)
    yield decoded

  private def decodeCurrentInvitation(version: Int, reader: CodecReader): Either[StorageError, InvitationData] =
    for
      _                  <- if version == InvitationVersion2 || version == InvitationVersion3 then Right(()) else Left(StorageError.InvalidEnvelope)
      (ephemeral, r2)    <- readOptionFixed(reader, X25519Private.from)
      (ratchet, r3)      <- readOptionFixed(r2, X25519Private.from)
      (peer, r4)         <- readOptionString(r3)
      (stateCode, r5)    <- r4.u8
      state              <- invitationFromCode(stateCode)
      (confirmation, r6) <- r5.u8
      confirmationState  <- confirmationFromCode(confirmation)
      (payload, r7)      <- r6.bytes(MaxPayloadBytes)
      (expiresAt, r8)    <- r7.u64
      decoded            <-
        if version == InvitationVersion3 then
          for
            (reply, r9)     <- readOptionString(r8)
            (attempts, r10) <- r9.u32
            _               <- r10.finish
            value           <- if attempts <= 1_000L then Right(attempts.toInt) else Left(StorageError.InvalidEnvelope)
          yield InvitationData(ephemeral, ratchet, peer, state, confirmationState, payload, expiresAt, reply, value)
        else r8.finish.map(_ => InvitationData(ephemeral, ratchet, peer, state, confirmationState, payload, expiresAt))
    yield decoded

  private def decodeLegacyInvitation(reader: CodecReader): Either[StorageError, InvitationData] =
    for
      (ephemeralRaw, r1) <- reader.bytes(32)
      ephemeral          <- X25519Private.from(ephemeralRaw).left.map(_ => StorageError.InvalidEnvelope)
      (peer, r2)         <- readOptionString(r1)
      (stateCode, r3)    <- r2.u8
      state              <- invitationFromCode(stateCode)
      (confirmation, r4) <- r3.u8
      confirmationState  <- confirmationFromCode(confirmation)
      (payload, r5)      <- r4.bytes(MaxPayloadBytes)
      (expiresAt, r6)    <- r5.u64
      _                  <- r6.finish
    yield InvitationData(Some(ephemeral), None, peer, state, confirmationState, payload, expiresAt)

  private def writeString(value: String, field: String): Either[StorageError, ByteVector] =
    normalize(value, field).flatMap { normalized =>
      val raw = ByteVector.view(normalized.getBytes(StandardCharsets.UTF_8))
      if raw.size <= MaxFieldBytes.toLong then Right(raw) else Left(StorageError.InvalidArgument(field))
    }

  private def writeOptionString(value: Option[String], field: String): Either[StorageError, Option[ByteVector]] =
    value match
      case None       => Right(None)
      case Some(text) => writeString(text, field).map(Some(_))

  private def writeOptionBytes(writer: CodecWriter, value: Option[ByteVector]): Unit =
    value match
      case None        => writer.u8(0)
      case Some(bytes) => writer.u8(1).bytes(bytes)

  private def writeOptionFixed(writer: CodecWriter, value: Option[ByteVector]): Unit =
    value match
      case None        => writer.u8(0)
      case Some(bytes) => writer.u8(1).fixed(bytes)

  private def readOptionFixed[A](
    reader: CodecReader,
    parse: ByteVector => Either[yochat.core.CoreError, A]
  ): Either[StorageError, (Option[A], CodecReader)] =
    reader.u8.flatMap {
      case (0, next) => Right((None, next))
      case (1, next) =>
        next.fixed(32).flatMap { case (raw, after) =>
          parse(raw).left.map(_ => StorageError.InvalidEnvelope).map(value => (Some(value), after))
        }
      case _         => Left(StorageError.InvalidEnvelope)
    }

  private def readOptionString(reader: CodecReader): Either[StorageError, (Option[String], CodecReader)] =
    reader.u8.flatMap {
      case (0, next) => Right((None, next))
      case (1, next) => next.string(MaxFieldBytes).map { case (value, after) => (Some(value), after) }
      case _         => Left(StorageError.InvalidEnvelope)
    }

  private def normalize(value: String, field: String): Either[StorageError, String] =
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    if normalized.codePoints().anyMatch(codePoint => Character.isISOControl(codePoint)) then Left(StorageError.InvalidArgument(field))
    else if normalized.getBytes(StandardCharsets.UTF_8).length > MaxFieldBytes then Left(StorageError.InvalidArgument(field))
    else Right(normalized)

  private def confirmationCode(value: ContactConfirmation): Int =
    value match
      case ContactConfirmation.Pending         => 1
      case ContactConfirmation.Confirmed       => 2
      case ContactConfirmation.AccountMismatch => 3

  private def confirmationFromCode(value: Int): Either[StorageError, ContactConfirmation] =
    value match
      case 1 => Right(ContactConfirmation.Pending)
      case 2 => Right(ContactConfirmation.Confirmed)
      case 3 => Right(ContactConfirmation.AccountMismatch)
      case _ => Left(StorageError.InvalidEnvelope)

  private def contactStateCode(value: ContactState): Int =
    value match
      case ContactState.Active  => 1
      case ContactState.Deleted => 2

  private def contactStateFromCode(value: Int): Either[StorageError, ContactState] =
    value match
      case 1 => Right(ContactState.Active)
      case 2 => Right(ContactState.Deleted)
      case _ => Left(StorageError.InvalidEnvelope)

  private def invitationCode(value: InvitationState): Int =
    value match
      case InvitationState.Pending      => 1
      case InvitationState.Accepted     => 2
      case InvitationState.Declined     => 3
      case InvitationState.Expired      => 4
      case InvitationState.Consumed     => 5
      case InvitationState.PendingReply => 6

  private def invitationFromCode(value: Int): Either[StorageError, InvitationState] =
    value match
      case 1 => Right(InvitationState.Pending)
      case 2 => Right(InvitationState.Accepted)
      case 3 => Right(InvitationState.Declined)
      case 4 => Right(InvitationState.Expired)
      case 5 => Right(InvitationState.Consumed)
      case 6 => Right(InvitationState.PendingReply)
      case _ => Left(StorageError.InvalidEnvelope)

final private[store] class CodecWriter private ():

  private val out = new ByteArrayOutputStream(256)

  def u8(value: Int): CodecWriter =
    out.write(value & 0xff)
    this

  def u32(value: Long): CodecWriter =
    out.write(((value >>> 24) & 0xff).toInt)
    out.write(((value >>> 16) & 0xff).toInt)
    out.write(((value >>> 8) & 0xff).toInt)
    out.write((value & 0xff).toInt)
    this

  def u64(value: Long): CodecWriter =
    out.write(((value >>> 56) & 0xff).toInt)
    out.write(((value >>> 48) & 0xff).toInt)
    out.write(((value >>> 40) & 0xff).toInt)
    out.write(((value >>> 32) & 0xff).toInt)
    out.write(((value >>> 24) & 0xff).toInt)
    out.write(((value >>> 16) & 0xff).toInt)
    out.write(((value >>> 8) & 0xff).toInt)
    out.write((value & 0xff).toInt)
    this

  def bytes(value: ByteVector): CodecWriter =
    u32(value.size)
    out.write(value.toArray)
    this

  def fixed(value: ByteVector): CodecWriter =
    out.write(value.toArray)
    this

  def result: ByteVector = ByteVector.view(out.toByteArray)

private[store] object CodecWriter:

  def apply(): CodecWriter = new CodecWriter()

final private[store] case class CodecReader private (bytes: ByteVector, offset: Long):

  def u8: Either[StorageError, (Int, CodecReader)] =
    read(1).map { chunk =>
      (chunk(0).toInt & 0xff, copy(offset = offset + 1))
    }

  def u32: Either[StorageError, (Long, CodecReader)] =
    read(4).map(chunk => (unsigned(chunk), copy(offset = offset + 4)))

  def u64: Either[StorageError, (Long, CodecReader)] =
    read(8).flatMap { chunk =>
      val value = unsigned(chunk)
      if value < 0L then Left(StorageError.InvalidEnvelope) else Right((value, copy(offset = offset + 8)))
    }

  def fixed(length: Int): Either[StorageError, (ByteVector, CodecReader)] =
    read(length).map(chunk => (chunk, copy(offset = offset + length)))

  def bytes(maxLength: Int): Either[StorageError, (ByteVector, CodecReader)] =
    u32.flatMap { case (length, after) =>
      if length > maxLength.toLong then Left(StorageError.InvalidEnvelope)
      else after.fixed(length.toInt)
    }

  def string(maxBytes: Int): Either[StorageError, (String, CodecReader)] =
    bytes(maxBytes).flatMap { case (raw, after) =>
      decodeUtf8(raw).map(value => (value, after))
    }

  def finish: Either[StorageError, Unit] =
    if offset == bytes.size then Right(()) else Left(StorageError.InvalidEnvelope)

  private def read(length: Int): Either[StorageError, ByteVector] =
    if length < 0 || offset + length.toLong > bytes.size then Left(StorageError.InvalidEnvelope)
    else Right(bytes.slice(offset, offset + length))

  private def unsigned(chunk: ByteVector): Long =
    chunk.toArray.foldLeft(0L)((value, byte) => (value << 8) | (byte.toLong & 0xffL))

  private def decodeUtf8(raw: ByteVector): Either[StorageError, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(raw.toArray)).toString)
    catch case _: CharacterCodingException => Left(StorageError.InvalidEnvelope)

private[store] object CodecReader:

  def apply(value: ByteVector): CodecReader = new CodecReader(value, 0L)
