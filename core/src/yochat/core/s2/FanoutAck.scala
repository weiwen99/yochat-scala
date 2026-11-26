package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalReader, CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature}

/** Companion acknowledgement for one direct envelope. It is signed by the read-only device key, not by the user private key. */
final case class FanoutAck(
  senderDevice: Ed25519Public,
  recipientDevice: Ed25519Public,
  conversationId: ByteVector,
  authorDevice: Ed25519Public,
  authorSeq: Long,
  receivedAt: Long,
  signature: Signature
):

  def verify: Boolean =
    Crypto.verify(
      senderDevice,
      FanoutAck.signingInput(senderDevice, recipientDevice, conversationId, authorDevice, authorSeq, receivedAt),
      signature
    )

  def bytes: ByteVector =
    FanoutAck.canonical(senderDevice, recipientDevice, conversationId, authorDevice, authorSeq, receivedAt) ++ signature.bytes

  def logicalKey: LogicalKey = LogicalKey(conversationId, authorDevice, authorSeq)

  override def toString: String = s"FanoutAck(conversation=${conversationId.toHex.take(8)}, seq=$authorSeq)"

object FanoutAck:
  val Version: Int   = 1
  private val Domain = Crypto.ascii("yochat-s2b-fanout-ack")

  def create(
    senderPrivate: Ed25519Private,
    senderDevice: Ed25519Public,
    recipientDevice: Ed25519Public,
    conversationId: ByteVector,
    authorDevice: Ed25519Public,
    authorSeq: Long,
    receivedAt: Long
  ): Either[CoreError, FanoutAck] =
    validate(conversationId, authorSeq, receivedAt).flatMap { _ =>
      Crypto
        .sign(senderPrivate, signingInput(senderDevice, recipientDevice, conversationId, authorDevice, authorSeq, receivedAt))
        .map(signature => FanoutAck(senderDevice, recipientDevice, conversationId, authorDevice, authorSeq, receivedAt, signature))
    }

  def fromBytes(value: ByteVector): Either[CoreError, FanoutAck] =
    if value.size < Signature.Length.toLong then Left(CoreError.InvalidEncoding)
    else
      for
        decoded   <- decode(value.dropRight(Signature.Length.toLong))
        signature <- Signature.from(value.takeRight(Signature.Length.toLong))
        result     = FanoutAck(decoded._1, decoded._2, decoded._3, decoded._4, decoded._5, decoded._6, signature)
        _         <- if result.verify then Right(()) else Left(CoreError.InvalidSignature)
      yield result

  def signingInput(
    senderDevice: Ed25519Public,
    recipientDevice: Ed25519Public,
    conversationId: ByteVector,
    authorDevice: Ed25519Public,
    authorSeq: Long,
    receivedAt: Long
  ): ByteVector =
    Domain ++ canonical(senderDevice, recipientDevice, conversationId, authorDevice, authorSeq, receivedAt)

  private def canonical(
    senderDevice: Ed25519Public,
    recipientDevice: Ed25519Public,
    conversationId: ByteVector,
    authorDevice: Ed25519Public,
    authorSeq: Long,
    receivedAt: Long
  ): ByteVector =
    CanonicalWriter()
      .u8(Version)
      .fixed(senderDevice.bytes)
      .fixed(recipientDevice.bytes)
      .fixed(conversationId)
      .fixed(authorDevice.bytes)
      .u64(authorSeq)
      .u64(receivedAt)
      .result

  private def decode(
    value: ByteVector
  ): Either[CoreError, (Ed25519Public, Ed25519Public, ByteVector, Ed25519Public, Long, Long)] =
    val reader = CanonicalReader(value)
    for
      (version, r1)      <- reader.u8
      _                  <- if version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (senderRaw, r2)    <- r1.fixed(32)
      sender             <- Ed25519Public.from(senderRaw)
      (recipientRaw, r3) <- r2.fixed(32)
      recipient          <- Ed25519Public.from(recipientRaw)
      (conversation, r4) <- r3.fixed(32)
      (authorRaw, r5)    <- r4.fixed(32)
      author             <- Ed25519Public.from(authorRaw)
      (seq, r6)          <- r5.u64
      (receivedAt, r7)   <- r6.u64
      _                  <- r7.finish
      _                  <- validate(conversation, seq, receivedAt)
    yield (sender, recipient, conversation, author, seq, receivedAt)

  private def validate(conversationId: ByteVector, authorSeq: Long, receivedAt: Long): Either[CoreError, Unit] =
    if conversationId.size != 32L then Left(CoreError.InvalidLength("conversation", 32, conversationId.size))
    else if authorSeq < 0L || receivedAt < 0L then Left(CoreError.InvalidEncoding)
    else Right(())
