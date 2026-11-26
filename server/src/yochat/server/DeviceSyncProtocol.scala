package yochat.server

import java.nio.ByteBuffer
import java.security.SecureRandom

import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Ed25519Private, Ed25519Public, Key32, Nonce, Signature}
import yochat.core.s2.{SyncStatus, SyncStatusRequest}

/** S2-A device-sync transport: sealed, signed incremental batches plus a signed ACK carrying the A2 checkpoint. */
object DeviceSyncProtocol:

  final case class Opened(
    authHash: ByteVector,
    batchSeq: Long,
    prevBatchHash: ByteVector,
    firstSyncSeq: Long,
    lastSyncSeq: Long,
    records: Value
  )

  final case class Ack(
    authHash: ByteVector,
    batchSeq: Long,
    batchHash: ByteVector,
    lastAckedSyncSeq: Long,
    signature: Signature
  )

  private val Domain    = Crypto.ascii("yochat-s2-device-sync")
  private val AckDomain = Crypto.ascii("yochat-s2-sync-ack")
  private val Random    = new SecureRandom()

  /** A2 asks A1 for a fresh batch after it comes online or restarts. */
  def syncRequest(authHash: ByteVector): Either[String, ByteVector] =
    if authHash.size == 32L then
      Right(
        ByteVector.view(
          ujson.Obj("kind" -> "s2-sync-request", "version" -> 1, "authHash" -> authHash.toHex).render().getBytes("UTF-8")
        )
      )
    else Left("auth-hash")

  def decodeSyncRequest(bytes: ByteVector): Either[String, ByteVector] =
    try
      val json = ujson.read(new String(bytes.toArray, "UTF-8"))
      for
        kind     <- json.obj.get("kind").collect { case ujson.Str(value) => value }.toRight("kind")
        _        <- if kind == "s2-sync-request" then Right(()) else Left("kind")
        version  <- fieldLong(json, "version")
        _        <- if version == 1L then Right(()) else Left("version")
        authHash <- fieldHex(json, "authHash", 32)
      yield authHash
    catch case _: Exception => Left("json")

  def seal(
    syncRoot: Key32,
    primaryDevicePrivate: Ed25519Private,
    authHash: ByteVector,
    batchSeq: Long,
    prevBatchHash: ByteVector,
    firstSyncSeq: Long,
    lastSyncSeq: Long,
    records: Value
  ): Either[String, ByteVector] =
    for
      _      <- if authHash.size == 32L then Right(()) else Left("auth-hash")
      _      <- if prevBatchHash.size == 32L then Right(()) else Left("prev-hash")
      _      <- if firstSyncSeq >= 0L && lastSyncSeq >= firstSyncSeq then Right(()) else Left("sync-range")
      nonce  <- Nonce.from(ByteVector.view(randomBytes(12))).left.map(_ => "nonce")
      key    <- Crypto.hkdf32(syncRoot.bytes, u64(batchSeq), Domain).left.map(_ => "batch-key")
      plain   = ByteVector.view(ujson.write(records).getBytes("UTF-8"))
      cipher <-
        Crypto.aeadSeal(key, nonce, aad(authHash, batchSeq, prevBatchHash, firstSyncSeq, lastSyncSeq, nonce), plain).left.map(_ => "seal")
      header  = headerBytes(authHash, batchSeq, prevBatchHash, firstSyncSeq, lastSyncSeq, nonce)
      sig    <- Crypto.sign(primaryDevicePrivate, header ++ cipher).left.map(_ => "sign")
    yield ByteVector.view(
      ujson
        .Obj(
          "version"       -> 1,
          "authHash"      -> authHash.toHex,
          "batchSeq"      -> ujson.Num(batchSeq.toDouble),
          "prevBatchHash" -> prevBatchHash.toHex,
          "firstSyncSeq"  -> ujson.Num(firstSyncSeq.toDouble),
          "lastSyncSeq"   -> ujson.Num(lastSyncSeq.toDouble),
          "nonce"         -> nonce.bytes.toHex,
          "ciphertext"    -> cipher.toHex,
          "signature"     -> sig.bytes.toHex
        )
        .render()
        .getBytes("UTF-8")
    )

  def open(
    syncRoot: Key32,
    primaryDevicePub: Ed25519Public,
    expectedAuthHash: ByteVector,
    bytes: ByteVector
  ): Either[String, Opened] =
    for
      json      <- parseJson(bytes)
      version   <- fieldLong(json, "version")
      _         <- if version == 1L then Right(()) else Left("version")
      authHash  <- fieldHex(json, "authHash", 32)
      _         <- if authHash == expectedAuthHash then Right(()) else Left("auth-hash")
      batchSeq  <- fieldLong(json, "batchSeq")
      _         <- if batchSeq >= 0L then Right(()) else Left("batch-seq")
      prev      <- fieldHex(json, "prevBatchHash", 32)
      firstSync <- fieldLong(json, "firstSyncSeq")
      lastSync  <- fieldLong(json, "lastSyncSeq")
      _         <- if firstSync >= 0L && lastSync >= firstSync then Right(()) else Left("sync-range")
      nonce     <- fieldHex(json, "nonce", 12).flatMap(Nonce.from(_).left.map(_ => "nonce"))
      cipher    <- fieldHexAny(json, "ciphertext")
      rawSig    <- fieldHex(json, "signature", 64)
      signature <- Signature.from(rawSig).left.map(_ => "signature")
      header     = headerBytes(authHash, batchSeq, prev, firstSync, lastSync, nonce)
      _         <- if Crypto.verify(primaryDevicePub, header ++ cipher, signature) then Right(()) else Left("signature")
      key       <- Crypto.hkdf32(syncRoot.bytes, u64(batchSeq), Domain).left.map(_ => "batch-key")
      plain     <- Crypto.aeadOpen(key, nonce, aad(authHash, batchSeq, prev, firstSync, lastSync, nonce), cipher).left.map(_ => "decrypt")
      records   <- parseJson(plain)
    yield Opened(authHash, batchSeq, prev, firstSync, lastSync, records)

  def ack(
    authHash: ByteVector,
    batchSeq: Long,
    batchHash: ByteVector,
    lastAckedSyncSeq: Long,
    companionDevicePrivate: Ed25519Private
  ): Either[String, ByteVector] =
    if authHash.size != 32L || batchHash.size != 32L || lastAckedSyncSeq < 0L then Left("ack-fields")
    else
      for signature <- Crypto
                         .sign(companionDevicePrivate, AckDomain ++ ackInput(authHash, batchSeq, batchHash, lastAckedSyncSeq))
                         .left
                         .map(_ => "sign")
      yield ByteVector.view(
        ujson
          .Obj(
            "kind"             -> "s2-sync-ack",
            "version"          -> 1,
            "authHash"         -> authHash.toHex,
            "batchSeq"         -> ujson.Num(batchSeq.toDouble),
            "batchHash"        -> batchHash.toHex,
            "lastAckedSyncSeq" -> ujson.Num(lastAckedSyncSeq.toDouble),
            "signature"        -> signature.bytes.toHex
          )
          .render()
          .getBytes("UTF-8")
      )

  def decodeAck(
    companionDevicePub: Ed25519Public,
    expectedAuthHash: ByteVector,
    bytes: ByteVector
  ): Either[String, Ack] =
    for
      json      <- parseJson(bytes)
      kind      <- fieldString(json, "kind")
      _         <- if kind == "s2-sync-ack" then Right(()) else Left("kind")
      version   <- fieldLong(json, "version")
      _         <- if version == 1L then Right(()) else Left("version")
      authHash  <- fieldHex(json, "authHash", 32)
      _         <- if authHash == expectedAuthHash then Right(()) else Left("auth-hash")
      batchSeq  <- fieldLong(json, "batchSeq")
      batchHash <- fieldHex(json, "batchHash", 32)
      lastAcked <- fieldLong(json, "lastAckedSyncSeq")
      rawSig    <- fieldHex(json, "signature", 64)
      signature <- Signature.from(rawSig).left.map(_ => "signature")
      _         <- if Crypto.verify(companionDevicePub, AckDomain ++ ackInput(authHash, batchSeq, batchHash, lastAcked), signature) then Right(())
                   else Left("signature")
    yield Ack(authHash, batchSeq, batchHash, lastAcked, signature)

  def syncStatus(status: SyncStatus): ByteVector =
    ByteVector.view(
      ujson
        .Obj(
          "kind"             -> "s2-sync-status",
          "version"          -> 1,
          "authHash"         -> status.authHash.toHex,
          "lastBatchSeq"     -> ujson.Num(status.lastBatchSeq.toDouble),
          "lastBatchHash"    -> status.lastBatchHash.toHex,
          "lastAckedSyncSeq" -> ujson.Num(status.lastAckedSyncSeq.toDouble),
          "signature"        -> status.signature.bytes.toHex
        )
        .render()
        .getBytes("UTF-8")
    )

  def decodeSyncStatus(
    companionDevicePub: Ed25519Public,
    expectedAuthHash: ByteVector,
    bytes: ByteVector
  ): Either[String, SyncStatus] =
    for
      json      <- parseJson(bytes)
      kind      <- fieldString(json, "kind")
      _         <- if kind == "s2-sync-status" then Right(()) else Left("kind")
      version   <- fieldLong(json, "version")
      _         <- if version == 1L then Right(()) else Left("version")
      authHash  <- fieldHex(json, "authHash", 32)
      _         <- if authHash == expectedAuthHash then Right(()) else Left("auth-hash")
      batchSeq  <- fieldLong(json, "lastBatchSeq")
      batchHash <- fieldHex(json, "lastBatchHash", 32)
      lastAck   <- fieldLong(json, "lastAckedSyncSeq")
      rawSig    <- fieldHex(json, "signature", 64)
      signature <- Signature.from(rawSig).left.map(_ => "signature")
      status     = SyncStatus(authHash, batchSeq, batchHash, lastAck, signature)
      _         <- if status.verify(companionDevicePub) then Right(()) else Left("signature")
    yield status

  def syncStatusRequest(
    authHash: ByteVector,
    primaryDevicePrivate: Ed25519Private
  ): Either[String, ByteVector] =
    for
      nonce   <- Nonce.from(ByteVector.view(randomBytes(16))).left.map(_ => "nonce")
      request <- SyncStatusRequest.create(primaryDevicePrivate, authHash, nonce.bytes).left.map(_ => "request")
    yield ByteVector.view(
      ujson
        .Obj(
          "kind"      -> "s2-sync-status-request",
          "version"   -> 1,
          "authHash"  -> request.authHash.toHex,
          "nonce"     -> request.nonce.toHex,
          "signature" -> request.signature.bytes.toHex
        )
        .render()
        .getBytes("UTF-8")
    )

  def decodeSyncStatusRequest(
    primaryDevicePub: Ed25519Public,
    expectedAuthHash: ByteVector,
    bytes: ByteVector
  ): Either[String, SyncStatusRequest] =
    for
      json      <- parseJson(bytes)
      kind      <- fieldString(json, "kind")
      _         <- if kind == "s2-sync-status-request" then Right(()) else Left("kind")
      version   <- fieldLong(json, "version")
      _         <- if version == 1L then Right(()) else Left("version")
      authHash  <- fieldHex(json, "authHash", 32)
      _         <- if authHash == expectedAuthHash then Right(()) else Left("auth-hash")
      nonce     <- fieldHex(json, "nonce", 16)
      rawSig    <- fieldHex(json, "signature", 64)
      signature <- Signature.from(rawSig).left.map(_ => "signature")
      request    = SyncStatusRequest(authHash, nonce, signature)
      _         <- if request.verify(primaryDevicePub) then Right(()) else Left("signature")
    yield request

  def batchHash(bytes: ByteVector): ByteVector = Crypto.sha256(bytes).bytes

  private def ackInput(
    authHash: ByteVector,
    batchSeq: Long,
    batchHash: ByteVector,
    lastAckedSyncSeq: Long
  ): ByteVector =
    authHash ++ u64(batchSeq) ++ batchHash ++ u64(lastAckedSyncSeq)

  private def aad(
    authHash: ByteVector,
    batchSeq: Long,
    prev: ByteVector,
    firstSyncSeq: Long,
    lastSyncSeq: Long,
    nonce: Nonce
  ): ByteVector =
    Domain ++ headerBytes(authHash, batchSeq, prev, firstSyncSeq, lastSyncSeq, nonce)

  private def headerBytes(
    authHash: ByteVector,
    batchSeq: Long,
    prev: ByteVector,
    firstSyncSeq: Long,
    lastSyncSeq: Long,
    nonce: Nonce
  ): ByteVector =
    authHash ++ u64(batchSeq) ++ prev ++ u64(firstSyncSeq) ++ u64(lastSyncSeq) ++ nonce.bytes

  private def u64(value: Long): ByteVector =
    ByteVector.view(ByteBuffer.allocate(8).putLong(value).array())

  private def randomBytes(length: Int): Array[Byte] =
    val bytes = new Array[Byte](length)
    Random.nextBytes(bytes)
    bytes

  private def parseJson(bytes: ByteVector): Either[String, Value] =
    try Right(ujson.read(new String(bytes.toArray, "UTF-8")))
    catch case _: Exception => Left("json")

  private def fieldLong(value: Value, name: String): Either[String, Long] =
    value.obj.get(name) match
      case Some(ujson.Num(number)) => Right(number.toLong)
      case Some(ujson.Str(text))   => text.toLongOption.toRight(name)
      case _                       => Left(name)

  private def fieldString(value: Value, name: String): Either[String, String] =
    value.obj.get(name) match
      case Some(ujson.Str(text)) => Right(text)
      case _                     => Left(name)

  private def fieldHex(value: Value, name: String, length: Int): Either[String, ByteVector] =
    fieldHexAny(value, name).flatMap(bytes => if bytes.size == length.toLong then Right(bytes) else Left(name))

  private def fieldHexAny(value: Value, name: String): Either[String, ByteVector] =
    value.obj.get(name) match
      case Some(ujson.Str(text)) =>
        ByteVector.fromHex(text) match
          case Some(bytes) => Right(bytes)
          case None        => Left(name)
      case _                     => Left(name)
