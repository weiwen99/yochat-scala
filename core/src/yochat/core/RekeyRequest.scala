package yochat.core

import scodec.bits.ByteVector

final case class RekeyRequest private (
  sessionId: SessionId,
  epoch: Long,
  id: RekeyId,
  r1Pub: X25519Public,
  devicePub: Ed25519Public,
  nonce: Nonce,
  ciphertext: ByteVector,
  signature: Signature,
  canonical: ByteVector
):

  def transcript: ByteVector = RekeyRequest.transcript(sessionId, epoch, id, r1Pub, devicePub, nonce)

  def verify(rekeyBase: Key32): Either[CoreError, Unit] =
    for
      key       <- RekeyRequest.key(rekeyBase, epoch, id)
      plaintext <- Crypto.aeadOpen(key, nonce, transcript, ciphertext)
      _         <- if plaintext.isEmpty then Right(()) else Left(CoreError.InvalidEncoding)
    yield ()

object RekeyRequest:

  val Version: Int = 1
  val Type: Int    = 1

  private val Domain        = Crypto.ascii("yochat-s1-rekey-request")
  private val KeyInfo       = Crypto.ascii("yochat-s1-rekey-request")
  private val MaxCiphertext = 256

  def create(
    sessionId: SessionId,
    epoch: Long,
    id: RekeyId,
    r1Pub: X25519Public,
    devicePub: Ed25519Public,
    devicePrivate: Ed25519Private,
    nonce: Nonce,
    rekeyBase: Key32
  ): Either[CoreError, RekeyRequest] =
    if epoch < 0L then Left(CoreError.InvalidState)
    else
      val transcriptBytes = transcript(sessionId, epoch, id, r1Pub, devicePub, nonce)
      for
        key            <- key(rekeyBase, epoch, id)
        ciphertext     <- Crypto.aeadSeal(key, nonce, transcriptBytes, ByteVector.empty)
        signature      <- Crypto.sign(devicePrivate, transcriptBytes)
        canonicalFields = CanonicalWriter()
                            .u8(Version)
                            .u8(Type)
                            .fixed(sessionId.bytes)
                            .u64(epoch)
                            .fixed(id.bytes)
                            .fixed(r1Pub.bytes)
                            .fixed(devicePub.bytes)
                            .fixed(nonce.bytes)
                            .bytes(ciphertext)
                            .result
      yield RekeyRequest(sessionId, epoch, id, r1Pub, devicePub, nonce, ciphertext, signature, canonicalFields ++ signature.bytes)

  def fromBytes(value: ByteVector): Either[CoreError, RekeyRequest] =
    val reader = CanonicalReader(value)
    for
      (version, r1)     <- reader.u8
      _                 <- if version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (messageType, r2) <- r1.u8
      _                 <- if messageType == Type then Right(()) else Left(CoreError.InvalidEncoding)
      (sessionRaw, r3)  <- r2.fixed(SessionId.Length)
      sessionId         <- SessionId.from(sessionRaw)
      (epoch, r4)       <- r3.u64
      (idRaw, r5)       <- r4.fixed(RekeyId.Length)
      id                <- RekeyId.from(idRaw)
      (r1Raw, r6)       <- r5.fixed(X25519Public.Length)
      r1Pub             <- X25519Public.from(r1Raw)
      (deviceRaw, r7)   <- r6.fixed(Ed25519Public.Length)
      devicePub         <- Ed25519Public.from(deviceRaw)
      (nonceRaw, r8)    <- r7.fixed(Nonce.Length)
      nonce             <- Nonce.from(nonceRaw)
      (ciphertext, r9)  <- r8.bytes(MaxCiphertext)
      (sigRaw, r10)     <- r9.fixed(Signature.Length)
      signature         <- Signature.from(sigRaw)
      _                 <- r10.finish
      _                 <- if Crypto.verify(devicePub, transcript(sessionId, epoch, id, r1Pub, devicePub, nonce), signature) then Right(())
                           else Left(CoreError.InvalidSignature)
    yield RekeyRequest(sessionId, epoch, id, r1Pub, devicePub, nonce, ciphertext, signature, value)

  private def transcript(
    sessionId: SessionId,
    epoch: Long,
    id: RekeyId,
    r1Pub: X25519Public,
    devicePub: Ed25519Public,
    nonce: Nonce
  ): ByteVector =
    CanonicalWriter()
      .fixed(Domain)
      .u8(Type)
      .fixed(sessionId.bytes)
      .u64(epoch)
      .fixed(id.bytes)
      .fixed(r1Pub.bytes)
      .fixed(devicePub.bytes)
      .fixed(nonce.bytes)
      .result

  private def key(rekeyBase: Key32, epoch: Long, id: RekeyId): Either[CoreError, Key32] =
    val salt = CanonicalWriter().u64(epoch).fixed(id.bytes).result
    Crypto.hkdf32(rekeyBase.bytes, salt, KeyInfo)
