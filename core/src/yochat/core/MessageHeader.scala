package yochat.core

import scodec.bits.ByteVector

final case class MessageHeader(
  version: Int,
  kind: MessageKind,
  sessionId: SessionId,
  devicePub: Ed25519Public,
  dhPub: X25519Public,
  pn: Long,
  n: Long,
  seq: Long,
  nonce: Nonce,
  control: Option[ControlExtension]
):

  def canonical: ByteVector =
    val writer = CanonicalWriter()
      .u8(version)
      .u8(kind.code)
      .fixed(sessionId.bytes)
      .fixed(devicePub.bytes)
      .fixed(dhPub.bytes)
      .u64(pn)
      .u64(n)
      .u64(seq)
      .fixed(nonce.bytes)
    control.fold(writer)(extension => writer.u64(extension.rekeyEpoch).fixed(extension.rekeyId.bytes)).result

object MessageHeader:

  val Version: Int = 1

  private[core] def read(reader: CanonicalReader): Either[CoreError, (MessageHeader, CanonicalReader)] =
    for
      (version, r1)    <- reader.u8
      _                <- if version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (kindCode, r2)   <- r1.u8
      kind             <- MessageKind.fromCode(kindCode)
      (sessionRaw, r3) <- r2.fixed(SessionId.Length)
      sessionId        <- SessionId.from(sessionRaw)
      (deviceRaw, r4)  <- r3.fixed(Ed25519Public.Length)
      devicePub        <- Ed25519Public.from(deviceRaw)
      (dhRaw, r5)      <- r4.fixed(X25519Public.Length)
      dhPub            <- X25519Public.from(dhRaw)
      (pn, r6)         <- r5.u64
      (n, r7)          <- r6.u64
      (seq, r8)        <- r7.u64
      (nonceRaw, r9)   <- r8.fixed(Nonce.Length)
      nonce            <- Nonce.from(nonceRaw)
      (control, r10)   <- readControl(kind, r9)
      _                <- validateSeq(kind, seq)
    yield (MessageHeader(version, kind, sessionId, devicePub, dhPub, pn, n, seq, nonce, control), r10)

  private def readControl(kind: MessageKind, reader: CanonicalReader): Either[CoreError, (Option[ControlExtension], CanonicalReader)] =
    if kind == MessageKind.Data then Right((None, reader))
    else
      for
        (epoch, r1) <- reader.u64
        (idRaw, r2) <- r1.fixed(RekeyId.Length)
        id          <- RekeyId.from(idRaw)
      yield (Some(ControlExtension(epoch, id)), r2)

  private def validateSeq(kind: MessageKind, seq: Long): Either[CoreError, Unit] =
    if kind == MessageKind.Data && seq > 0L then Right(())
    else if kind != MessageKind.Data && seq == 0L then Right(())
    else Left(CoreError.InvalidState)
