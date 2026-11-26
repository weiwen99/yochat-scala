package yochat.server

import java.nio.ByteBuffer
import java.security.SecureRandom

import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Ed25519Private, Ed25519Public, Key32, Nonce, Signature}

/** Snapshot-only S2-A transport used before the full checkpoint worker. A1 sends a sealed, signed JSON snapshot; A2 upserts it. */
object DeviceSyncProtocol:

  final case class Batch(
    authHash: ByteVector,
    batchSeq: Long,
    prevBatchHash: ByteVector,
    nonce: Nonce,
    ciphertext: ByteVector,
    signature: ByteVector
  )

  private val Domain = Crypto.ascii("yochat-s2-device-sync")
  private val Random = new SecureRandom()

  /** A2 asks A1 for a fresh sealed snapshot after it comes online or restarts. */
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
    records: Value
  ): Either[String, ByteVector] =
    for
      _      <- if authHash.size == 32L then Right(()) else Left("auth-hash")
      _      <- if prevBatchHash.size == 32L then Right(()) else Left("prev-hash")
      nonce  <- Nonce.from(ByteVector.view(randomBytes(12))).left.map(_ => "nonce")
      key    <- Crypto.hkdf32(syncRoot.bytes, u64(batchSeq), Domain).left.map(_ => "batch-key")
      plain   = ByteVector.view(ujson.write(records).getBytes("UTF-8"))
      cipher <- Crypto.aeadSeal(key, nonce, aad(authHash, batchSeq, prevBatchHash, nonce), plain).left.map(_ => "seal")
      header  = headerBytes(authHash, batchSeq, prevBatchHash, nonce)
      sig    <- Crypto.sign(primaryDevicePrivate, header ++ cipher).left.map(_ => "sign")
    yield ByteVector.view(
      ujson
        .Obj(
          "version"       -> 1,
          "authHash"      -> authHash.toHex,
          "batchSeq"      -> batchSeq,
          "prevBatchHash" -> prevBatchHash.toHex,
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
  ): Either[String, Value] =
    for
      json      <- parseJson(bytes)
      version   <- fieldLong(json, "version")
      _         <- if version == 1L then Right(()) else Left("version")
      authHash  <- fieldHex(json, "authHash", 32)
      _         <- if authHash == expectedAuthHash then Right(()) else Left("auth-hash")
      batchSeq  <- fieldLong(json, "batchSeq")
      _         <- if batchSeq >= 0L then Right(()) else Left("batch-seq")
      prev      <- fieldHex(json, "prevBatchHash", 32)
      nonce     <- fieldHex(json, "nonce", 12).flatMap(Nonce.from(_).left.map(_ => "nonce"))
      cipher    <- fieldHexAny(json, "ciphertext")
      rawSig    <- fieldHex(json, "signature", 64)
      signature <- Signature.from(rawSig).left.map(_ => "signature")
      header     = headerBytes(authHash, batchSeq, prev, nonce)
      _         <- if Crypto.verify(primaryDevicePub, header ++ cipher, signature) then Right(()) else Left("signature")
      key       <- Crypto.hkdf32(syncRoot.bytes, u64(batchSeq), Domain).left.map(_ => "batch-key")
      plain     <- Crypto.aeadOpen(key, nonce, aad(authHash, batchSeq, prev, nonce), cipher).left.map(_ => "decrypt")
      value     <- parseJson(plain)
    yield value

  def batchHash(bytes: ByteVector): ByteVector = Crypto.sha256(bytes).bytes

  private def aad(
    authHash: ByteVector,
    batchSeq: Long,
    prev: ByteVector,
    nonce: Nonce
  ): ByteVector =
    Domain ++ headerBytes(authHash, batchSeq, prev, nonce)

  private def headerBytes(
    authHash: ByteVector,
    batchSeq: Long,
    prev: ByteVector,
    nonce: Nonce
  ): ByteVector =
    authHash ++ u64(batchSeq) ++ prev ++ nonce.bytes

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

  private def fieldHex(value: Value, name: String, length: Int): Either[String, ByteVector] =
    fieldHexAny(value, name).flatMap(bytes => if bytes.size == length.toLong then Right(bytes) else Left(name))

  private def fieldHexAny(value: Value, name: String): Either[String, ByteVector] =
    value.obj.get(name) match
      case Some(ujson.Str(text)) =>
        ByteVector.fromHex(text) match
          case Some(bytes) => Right(bytes)
          case None        => Left(name)
      case _                     => Left(name)
