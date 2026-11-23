package yochat.core

import java.nio.charset.StandardCharsets

import scodec.bits.ByteVector

/** AEAD-plaintext envelope for `MessageKind.Data`. The first Data from an author carries the account/safety binding required by ADR-0006 §3.3;
  * later Data carries only the user body. The header and relay-visible bytes are unchanged.
  */
final case class DataBinding(senderAccount: String, peerAccount: String, safetyDigits: String):

  override def toString: String = "DataBinding(<redacted>)"

final case class DataPayload(binding: Option[DataBinding], body: ByteVector):

  override def toString: String = s"DataPayload(binding=${binding.isDefined}, bodyBytes=${body.size})"

object DataPayload:

  val Version: Int            = 1
  val FlagsBinding: Int       = 1
  val SafetyDigitsLength: Int = 39
  val MaxBodyBytes: Int       = 262_144

  def encode(payload: DataPayload): Either[CoreError, ByteVector] =
    for
      _       <- if payload.body.size <= MaxBodyBytes.toLong then Right(())
                 else Left(CoreError.InvalidLength("body", MaxBodyBytes, payload.body.size))
      encoded <- payload.binding match
                   case None        => Right(CanonicalWriter().u8(Version).u8(0).bytes(payload.body).result)
                   case Some(value) =>
                     for
                       sender <- account(value.senderAccount, "sender_account")
                       peer   <- account(value.peerAccount, "peer_account")
                       digits <- validateDigits(value.safetyDigits)
                     yield CanonicalWriter()
                       .u8(Version)
                       .u8(FlagsBinding)
                       .string(sender)
                       .string(peer)
                       .fixed(ByteVector.view(digits.getBytes(StandardCharsets.US_ASCII)))
                       .bytes(payload.body)
                       .result
    yield encoded

  def decode(value: ByteVector): Either[CoreError, DataPayload] =
    for
      (version, r1) <- CanonicalReader(value).u8
      _             <- if version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (flags, r2)   <- r1.u8
      _             <- if flags == 0 || flags == FlagsBinding then Right(()) else Left(CoreError.InvalidEncoding)
      binding       <- if flags == FlagsBinding then decodeBinding(r2) else Right((None, r2))
      (body, r4)    <- binding._2.bytes(MaxBodyBytes)
      _             <- r4.finish
    yield DataPayload(binding._1, body)

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
