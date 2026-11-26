package yochat.core

import java.nio.charset.StandardCharsets

import scodec.bits.ByteVector

import yochat.core.s2.OrderStamp

/** AEAD-plaintext envelope for `MessageKind.Data`. The first Data from an author carries the account/safety binding required by ADR-0006 §3.3;
  * later Data carries only the user body. The header and relay-visible bytes are unchanged.
  */
final case class DataBinding(senderAccount: String, peerAccount: String, safetyDigits: String):

  override def toString: String = "DataBinding(<redacted>)"

final case class DataPayload(binding: Option[DataBinding], body: ByteVector, order: Option[OrderStamp] = None):

  override def toString: String = s"DataPayload(binding=${binding.isDefined}, bodyBytes=${body.size})"

object DataPayload:

  val Version: Int            = 2
  val LegacyVersion: Int      = 1
  val FlagsBinding: Int       = 1
  val FlagsOrder: Int         = 2
  val SafetyDigitsLength: Int = 39
  val MaxBodyBytes: Int       = 262_144

  def encode(payload: DataPayload): Either[CoreError, ByteVector] =
    for
      _       <- if payload.body.size <= MaxBodyBytes.toLong then Right(())
                 else Left(CoreError.InvalidLength("body", MaxBodyBytes, payload.body.size))
      encoded <- payload.order match
                   case None        =>
                     payload.binding match
                       case None        => Right(CanonicalWriter().u8(LegacyVersion).u8(0).bytes(payload.body).result)
                       case Some(value) => encodeBinding(LegacyVersion, value, None, payload.body)
                   case Some(order) =>
                     payload.binding match
                       case None        => Right(CanonicalWriter().u8(Version).u8(FlagsOrder).bytes(order.bytes).bytes(payload.body).result)
                       case Some(value) => encodeBinding(Version, value, Some(order), payload.body)
    yield encoded

  private def encodeBinding(
    version: Int,
    binding: DataBinding,
    order: Option[OrderStamp],
    body: ByteVector
  ): Either[CoreError, ByteVector] =
    for
      sender <- account(binding.senderAccount, "sender_account")
      peer   <- account(binding.peerAccount, "peer_account")
      digits <- validateDigits(binding.safetyDigits)
    yield
      val writer = CanonicalWriter()
        .u8(version)
        .u8(FlagsBinding | order.fold(0)(_ => FlagsOrder))
      order.foreach(value => writer.bytes(value.bytes))
      writer
        .string(sender)
        .string(peer)
        .fixed(ByteVector.view(digits.getBytes(StandardCharsets.US_ASCII)))
        .bytes(body)
        .result

  def decode(value: ByteVector): Either[CoreError, DataPayload] =
    for
      (version, r1) <- CanonicalReader(value).u8
      _             <- if version == LegacyVersion || version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (flags, r2)   <- r1.u8
      validFlags     =
        if version == LegacyVersion then flags == 0 || flags == FlagsBinding
        else (flags & ~(FlagsBinding | FlagsOrder)) == 0 && (version == Version || (flags & FlagsOrder) == 0)
      _             <- if validFlags then Right(()) else Left(CoreError.InvalidEncoding)
      (order, r3)   <- decodeOrder(flags, r2)
      binding       <- if (flags & FlagsBinding) != 0 then decodeBinding(r3) else Right((None, r3))
      (body, r4)    <- binding._2.bytes(MaxBodyBytes)
      _             <- r4.finish
      _             <- if version == LegacyVersion && order.isDefined then Left(CoreError.InvalidEncoding) else Right(())
    yield DataPayload(binding._1, body, order)

  private def decodeOrder(
    flags: Int,
    reader: CanonicalReader
  ): Either[CoreError, (Option[OrderStamp], CanonicalReader)] =
    if (flags & FlagsOrder) == 0 then Right((None, reader))
    else
      reader.bytes(1_024).flatMap { case (raw, next) =>
        OrderStamp.fromBytes(raw).map(value => (Some(value), next))
      }

  private def decodeBinding(reader: CanonicalReader): Either[CoreError, (Option[DataBinding], CanonicalReader)] =
    for
      (sender, r1)    <- reader.string(Text.MaxFieldBytes)
      senderClean     <- canonical(sender, "sender_account")
      (peer, r2)      <- r1.string(Text.MaxFieldBytes)
      peerClean       <- canonical(peer, "peer_account")
      (digitsRaw, r3) <- r2.fixed(SafetyDigitsLength)
      digits          <- decodeDigits(digitsRaw)
    yield (Some(DataBinding(senderClean, peerClean, digits)), r3)

  private def account(value: String, field: String): Either[CoreError, String] =
    Text.normalize(value, field).flatMap { normalized =>
      if normalized.nonEmpty then Right(normalized) else Left(CoreError.InvalidBinding)
    }

  private def canonical(value: String, field: String): Either[CoreError, String] =
    Text.normalize(value, field).flatMap { normalized =>
      if normalized == value && normalized.nonEmpty then Right(normalized) else Left(CoreError.InvalidBinding)
    }

  private def validateDigits(value: String): Either[CoreError, String] =
    if value.length == SafetyDigitsLength && value.forall(character => character >= '0' && character <= '9') then Right(value)
    else Left(CoreError.InvalidEncoding)

  private def decodeDigits(value: ByteVector): Either[CoreError, String] =
    val digits = new String(value.toArray, StandardCharsets.US_ASCII)
    validateDigits(digits)
