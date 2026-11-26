package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalReader, CanonicalWriter, CoreError, Ed25519Public}

/** Stable identity of one authored logical message, shared by A1 device-sync and B1 direct fanout. */
final case class LogicalKey(conversationId: ByteVector, authorDevice: Ed25519Public, authorSeq: Long):
  def bytes: ByteVector = LogicalKey.encode(conversationId, authorDevice, authorSeq)

  override def toString: String = s"LogicalKey(conversation=${conversationId.toHex.take(8)}, seq=$authorSeq)"

object LogicalKey:
  val Length: Int    = 32 + 32 + 8
  private val Domain = yochat.core.Crypto.ascii("yochat-s2b-logical-key")

  def encode(conversationId: ByteVector, authorDevice: Ed25519Public, authorSeq: Long): ByteVector =
    CanonicalWriter()
      .fixed(conversationId)
      .fixed(authorDevice.bytes)
      .u64(authorSeq)
      .result

  def fromBytes(value: ByteVector): Either[CoreError, LogicalKey] =
    if value.size != Length.toLong then Left(CoreError.InvalidLength("logical_key", Length, value.size))
    else
      for
        (conversation, r1) <- CanonicalReader(value).fixed(32)
        (deviceRaw, r2)    <- r1.fixed(32)
        (seq, r3)          <- r2.u64
        device             <- Ed25519Public.from(deviceRaw)
        _                  <- r3.finish
        _                  <- if seq >= 0L then Right(()) else Left(CoreError.InvalidEncoding)
      yield LogicalKey(conversation, device, seq)

  /** Defensive domain separation for callers that need a fixed-width digest instead of the canonical bytes. */
  def digest(value: LogicalKey): ByteVector = yochat.core.Crypto.sha256(Domain ++ value.bytes).bytes
