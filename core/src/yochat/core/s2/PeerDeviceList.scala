package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalReader, CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature}

enum PeerDeviceRole:
  case Primary, Companion

  def code: Int = this match
    case Primary   => 1
    case Companion => 2

object PeerDeviceRole:

  def fromCode(value: Int): Either[CoreError, PeerDeviceRole] =
    value match
      case 1 => Right(Primary)
      case 2 => Right(Companion)
      case _ => Left(CoreError.InvalidEncoding)

final case class PeerDeviceEntry(
  devicePub: Ed25519Public,
  role: PeerDeviceRole,
  name: String,
  joinedAt: Long,
  revokedAt: Option[Long]
):
  override def toString: String = s"PeerDeviceEntry(role=$role, revoked=${revokedAt.isDefined})"

/** A user-signed snapshot of one contact's device set. B1 is limited to one user-private-key holder, so a strict version is enough; if
  * user-private sync later adds another signer, this type must be replaced by mergeable signed operations in a separate ADR.
  */
final case class PeerDeviceList(
  userPub: Ed25519Public,
  version: Long,
  issuedAt: Long,
  entries: Vector[PeerDeviceEntry],
  signature: Signature
):

  def verify: Boolean =
    Crypto.verify(userPub, PeerDeviceList.signingInput(userPub, version, issuedAt, entries), signature)

  def canonical: ByteVector =
    PeerDeviceList.canonical(userPub, version, issuedAt, entries)

  def bytes: ByteVector = canonical ++ signature.bytes

  def revoked: Map[Ed25519Public, Long] =
    entries.collect { case entry if entry.revokedAt.isDefined => entry.devicePub -> entry.revokedAt.getOrElse(0L) }.toMap

  override def toString: String = s"PeerDeviceList(version=$version, entries=${entries.size})"

object PeerDeviceList:

  val Version: Int      = 1
  val MaxEntries: Int   = 8
  val MaxNameBytes: Int = 128
  private val Domain    = Crypto.ascii("yochat-s2b-device-list")

  def create(
    userPrivate: Ed25519Private,
    userPub: Ed25519Public,
    version: Long,
    issuedAt: Long,
    entries: Vector[PeerDeviceEntry]
  ): Either[CoreError, PeerDeviceList] =
    normalize(entries).flatMap { sorted =>
      validate(userPub, version, issuedAt, sorted).flatMap { _ =>
        Crypto
          .sign(userPrivate, signingInput(userPub, version, issuedAt, sorted))
          .map(signature => PeerDeviceList(userPub, version, issuedAt, sorted, signature))
      }
    }

  def signingInput(
    userPub: Ed25519Public,
    version: Long,
    issuedAt: Long,
    entries: Vector[PeerDeviceEntry]
  ): ByteVector =
    Domain ++ canonical(userPub, version, issuedAt, entries)

  def canonical(
    userPub: Ed25519Public,
    version: Long,
    issuedAt: Long,
    entries: Vector[PeerDeviceEntry]
  ): ByteVector =
    val writer = CanonicalWriter()
      .u8(Version)
      .fixed(userPub.bytes)
      .u64(version)
      .u64(issuedAt)
      .u32(entries.size.toLong)
    entries.foreach { entry =>
      writer.fixed(entry.devicePub.bytes)
      writer.u8(entry.role.code)
      writer.string(entry.name)
      writer.u64(entry.joinedAt)
      writer.u64(entry.revokedAt.getOrElse(0L))
      writer.u8(if entry.revokedAt.isDefined then 1 else 0)
    }
    writer.result

  def fromBytes(value: ByteVector): Either[CoreError, PeerDeviceList] =
    if value.size < Signature.Length.toLong then Left(CoreError.InvalidEncoding)
    else
      for
        decoded   <- decode(value.dropRight(Signature.Length.toLong))
        signature <- Signature.from(value.takeRight(Signature.Length.toLong))
        result     = PeerDeviceList(decoded._1, decoded._2, decoded._3, decoded._4, signature)
        _         <- if result.verify then Right(()) else Left(CoreError.InvalidSignature)
      yield result

  private def decode(value: ByteVector): Either[CoreError, (Ed25519Public, Long, Long, Vector[PeerDeviceEntry])] =
    val reader = CanonicalReader(value)
    for
      (version, r1)     <- reader.u8
      _                 <- if version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (userRaw, r2)     <- r1.fixed(32)
      userPub           <- Ed25519Public.from(userRaw)
      (listVersion, r3) <- r2.u64
      (issuedAt, r4)    <- r3.u64
      (count, r5)       <- r4.u32
      _                 <- if count <= MaxEntries.toLong then Right(()) else Left(CoreError.InvalidEncoding)
      (entries, r6)     <- decodeEntries(r5, count.toInt, Vector.empty)
      _                 <- r6.finish
      sorted            <- normalize(entries)
      _                 <- validate(userPub, listVersion, issuedAt, sorted)
    yield (userPub, listVersion, issuedAt, sorted)

  private def decodeEntries(
    reader: CanonicalReader,
    remaining: Int,
    acc: Vector[PeerDeviceEntry]
  ): Either[CoreError, (Vector[PeerDeviceEntry], CanonicalReader)] =
    if remaining == 0 then Right((acc, reader))
    else
      for
        (deviceRaw, r1)  <- reader.fixed(32)
        devicePub        <- Ed25519Public.from(deviceRaw)
        (roleCode, r2)   <- r1.u8
        role             <- PeerDeviceRole.fromCode(roleCode)
        (name, r3)       <- r2.string(MaxNameBytes)
        (joinedAt, r4)   <- r3.u64
        (revokedRaw, r5) <- r4.u64
        (hasRevoked, r6) <- r5.u8
        revokedAt        <- hasRevoked match
                              case 0 => Right(None)
                              case 1 => Right(Some(revokedRaw))
                              case _ => Left(CoreError.InvalidEncoding)
        _                <- if revokedAt.forall(_ >= joinedAt) then Right(()) else Left(CoreError.InvalidEncoding)
        next             <- decodeEntries(r6, remaining - 1, acc :+ PeerDeviceEntry(devicePub, role, name, joinedAt, revokedAt))
      yield next

  private def normalize(entries: Vector[PeerDeviceEntry]): Either[CoreError, Vector[PeerDeviceEntry]] =
    val sorted = entries.sortBy(_.devicePub.bytes.toHex)
    if sorted.map(_.devicePub).distinct.size != sorted.size then Left(CoreError.InvalidEncoding)
    else if sorted.exists(_.name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MaxNameBytes) then Left(CoreError.InvalidEncoding)
    else Right(sorted)

  private def validate(
    userPub: Ed25519Public,
    version: Long,
    issuedAt: Long,
    entries: Vector[PeerDeviceEntry]
  ): Either[CoreError, Unit] =
    if userPub.bytes.size != 32L then Left(CoreError.InvalidLength("user_pub", 32, userPub.bytes.size))
    else if version < 0L || issuedAt < 0L then Left(CoreError.InvalidEncoding)
    else if entries.size > MaxEntries then Left(CoreError.InvalidEncoding)
    else if entries.exists(entry => entry.joinedAt < 0L || entry.revokedAt.exists(_ < entry.joinedAt)) then Left(CoreError.InvalidEncoding)
    else Right(())
