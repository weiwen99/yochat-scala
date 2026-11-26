package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{
  CanonicalReader,
  CanonicalWriter,
  CoreError,
  Crypto,
  Ed25519Private,
  Ed25519Public,
  Key32,
  Nonce,
  Signature,
  X25519Private,
  X25519Public
}

/** B2 direct message to a read-only companion. The envelope is independent of the A1 sync stream and carries one logical author sequence. */
final case class FanoutEnvelope(
  senderUser: Ed25519Public,
  senderDevice: Ed25519Public,
  recipientDevice: Ed25519Public,
  conversationId: ByteVector,
  authorSeq: Long,
  ephemeralPub: X25519Public,
  nonce: Nonce,
  ciphertext: ByteVector,
  signature: Signature,
  order: Option[OrderStamp] = None
):

  def verify: Boolean =
    Crypto.verify(
      senderDevice,
      FanoutEnvelope.signingInput(senderUser, senderDevice, recipientDevice, conversationId, authorSeq, ephemeralPub, nonce, ciphertext, order),
      signature
    )

  def canonical: ByteVector =
    FanoutEnvelope.canonical(senderUser, senderDevice, recipientDevice, conversationId, authorSeq, ephemeralPub, nonce, ciphertext, order)

  def bytes: ByteVector = canonical ++ signature.bytes

  override def toString: String = s"FanoutEnvelope(conversation=${conversationId.toHex.take(8)}, seq=$authorSeq)"

object FanoutEnvelope:

  val Version: Int    = 2
  private val Domain  = Crypto.ascii("yochat-s2b-fanout-envelope")
  private val KeyInfo = Crypto.ascii("yochat-s2b-fanout-message/v1")

  def create(
    senderPrivate: Ed25519Private,
    senderUser: Ed25519Public,
    senderDevice: Ed25519Public,
    recipientDevice: Ed25519Public,
    recipientFanoutPublic: X25519Public,
    conversationId: ByteVector,
    authorSeq: Long,
    body: ByteVector,
    ephemeralPrivate: X25519Private,
    ephemeralPublic: X25519Public,
    nonce: Nonce,
    order: Option[OrderStamp] = None
  ): Either[CoreError, FanoutEnvelope] =
    validate(conversationId, authorSeq, body, recipientDevice) match
      case Left(error) => Left(error)
      case Right(_)    =>
        for
          shared    <- Crypto.agree(ephemeralPrivate, recipientFanoutPublic)
          key       <- Crypto.hkdf32(shared.bytes, conversationId, KeyInfo)
          cipher    <- Crypto.aeadSeal(key, nonce, aad(senderUser, senderDevice, recipientDevice, conversationId, authorSeq), body)
          signature <-
            Crypto.sign(
              senderPrivate,
              signingInput(senderUser, senderDevice, recipientDevice, conversationId, authorSeq, ephemeralPublic, nonce, cipher, order)
            )
        yield FanoutEnvelope(
          senderUser,
          senderDevice,
          recipientDevice,
          conversationId,
          authorSeq,
          ephemeralPublic,
          nonce,
          cipher,
          signature,
          order
        )

  def open(
    recipientFanoutPrivate: X25519Private,
    recipientDevice: Ed25519Public,
    envelope: FanoutEnvelope
  ): Either[CoreError, ByteVector] =
    validate(envelope.conversationId, envelope.authorSeq, envelope.ciphertext, envelope.recipientDevice) match
      case Left(error) => Left(error)
      case Right(_)    =>
        if envelope.recipientDevice != recipientDevice then Left(CoreError.InvalidBinding)
        else if !envelope.verify then Left(CoreError.InvalidSignature)
        else
          for
            shared <- Crypto.agree(recipientFanoutPrivate, envelope.ephemeralPub)
            key    <- Crypto.hkdf32(shared.bytes, envelope.conversationId, KeyInfo)
            plain  <- Crypto.aeadOpen(
                        key,
                        envelope.nonce,
                        aad(
                          envelope.senderUser,
                          envelope.senderDevice,
                          envelope.recipientDevice,
                          envelope.conversationId,
                          envelope.authorSeq
                        ),
                        envelope.ciphertext
                      )
          yield plain

  def fromBytes(value: ByteVector): Either[CoreError, FanoutEnvelope] =
    if value.size < Signature.Length.toLong then Left(CoreError.InvalidEncoding)
    else
      for
        unsigned  <- decode(value.dropRight(Signature.Length.toLong))
        signature <- Signature.from(value.takeRight(Signature.Length.toLong))
        result     = FanoutEnvelope(
                       unsigned._1,
                       unsigned._2,
                       unsigned._3,
                       unsigned._4,
                       unsigned._5,
                       unsigned._6,
                       unsigned._7,
                       unsigned._8,
                       signature,
                       unsigned._9
                     )
        _         <- if result.verify then Right(()) else Left(CoreError.InvalidSignature)
      yield result

  private def decode(
    value: ByteVector
  ): Either[CoreError, (Ed25519Public, Ed25519Public, Ed25519Public, ByteVector, Long, X25519Public, Nonce, ByteVector, Option[OrderStamp])] =
    val reader = CanonicalReader(value)
    for
      (version, r1)       <- reader.u8
      _                   <- if version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (senderUser, r2)    <- r1.fixed(32)
      userPub             <- Ed25519Public.from(senderUser)
      (senderDevice, r3)  <- r2.fixed(32)
      senderPub           <- Ed25519Public.from(senderDevice)
      (recipient, r4)     <- r3.fixed(32)
      recipientPub        <- Ed25519Public.from(recipient)
      (conversation, r5)  <- r4.fixed(32)
      (seq, r6)           <- r5.u64
      (ephemeral, r7)     <- r6.fixed(32)
      ephemeralPub        <- X25519Public.from(ephemeral)
      (nonceRaw, r8)      <- r7.fixed(Nonce.Length)
      nonce               <- Nonce.from(nonceRaw)
      (hasOrder, r9)      <- r8.u8
      (order, afterOrder) <- hasOrder match
                               case 0 => Right((None, r9))
                               case 1 =>
                                 r9.bytes(1_024).flatMap { case (raw, next) =>
                                   OrderStamp.fromBytes(raw).map(value => (Some(value), next))
                                 }
                               case _ => Left(CoreError.InvalidEncoding)
      (cipher, r10)       <- afterOrder.bytes(1_048_576)
      _                   <- r10.finish
      _                   <- validate(conversation, seq, cipher, recipientPub)
    yield (userPub, senderPub, recipientPub, conversation, seq, ephemeralPub, nonce, cipher, order)

  def signingInput(
    senderUser: Ed25519Public,
    senderDevice: Ed25519Public,
    recipientDevice: Ed25519Public,
    conversationId: ByteVector,
    authorSeq: Long,
    ephemeralPub: X25519Public,
    nonce: Nonce,
    ciphertext: ByteVector,
    order: Option[OrderStamp]
  ): ByteVector =
    Domain ++ canonical(senderUser, senderDevice, recipientDevice, conversationId, authorSeq, ephemeralPub, nonce, ciphertext, order)

  def canonical(
    senderUser: Ed25519Public,
    senderDevice: Ed25519Public,
    recipientDevice: Ed25519Public,
    conversationId: ByteVector,
    authorSeq: Long,
    ephemeralPub: X25519Public,
    nonce: Nonce,
    ciphertext: ByteVector,
    order: Option[OrderStamp]
  ): ByteVector =
    val writer = CanonicalWriter()
      .u8(Version)
      .fixed(senderUser.bytes)
      .fixed(senderDevice.bytes)
      .fixed(recipientDevice.bytes)
      .fixed(conversationId)
      .u64(authorSeq)
      .fixed(ephemeralPub.bytes)
      .fixed(nonce.bytes)
      .u8(if order.isDefined then 1 else 0)
    order.foreach(value => writer.bytes(value.bytes))
    writer.bytes(ciphertext).result

  private def aad(
    senderUser: Ed25519Public,
    senderDevice: Ed25519Public,
    recipientDevice: Ed25519Public,
    conversationId: ByteVector,
    authorSeq: Long
  ): ByteVector =
    Crypto.ascii("yochat-s2b-fanout-aad/v1") ++
      senderUser.bytes ++ senderDevice.bytes ++ recipientDevice.bytes ++ conversationId ++
      CanonicalWriter().u64(authorSeq).result

  private def validate(
    conversationId: ByteVector,
    authorSeq: Long,
    ciphertext: ByteVector,
    recipient: Ed25519Public
  ): Either[CoreError, Unit] =
    if conversationId.size != 32L then Left(CoreError.InvalidLength("conversation", 32, conversationId.size))
    else if authorSeq < 0L then Left(CoreError.InvalidEncoding)
    else if ciphertext.isEmpty || ciphertext.size > 1_048_576L then Left(CoreError.InvalidLength("ciphertext", 1_048_576, ciphertext.size))
    else if recipient.bytes.size != 32L then Left(CoreError.InvalidLength("recipient", 32, recipient.bytes.size))
    else Right(())
