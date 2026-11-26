package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalReader, CanonicalWriter, CoreError, Crypto, Ed25519Private, Ed25519Public, Signature, X25519Public}

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
  revokedAt: Option[Long],
  fanoutPub: Option[X25519Public] = None
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
  signature: Signature,
  features: Long = 0L,
  format: Int = PeerDeviceList.Version
):

  def verify: Boolean =
    Crypto.verify(userPub, PeerDeviceList.signingInput(userPub, version, issuedAt, entries, features, format), signature)

  def canonical: ByteVector =
    PeerDeviceList.canonical(userPub, version, issuedAt, entries, features, format)

  def bytes: ByteVector = canonical ++ signature.bytes

  def revoked: Map[Ed25519Public, Long] =
    entries.collect { case entry if entry.revokedAt.isDefined => entry.devicePub -> entry.revokedAt.getOrElse(0L) }.toMap

  override def toString: String = s"PeerDeviceList(version=$version, entries=${entries.size})"

object PeerDeviceList:

  val Version: Int                = 2
  val LegacyVersion: Int          = 1
  val MaxEntries: Int             = 8
  val MaxNameBytes: Int           = 128
  val FeatureS2b: Long            = 1L
  private val KnownFeatures: Long = FeatureS2b
  private val Domain              = Crypto.ascii("yochat-s2b-device-list")
  private val DomainV2            = Crypto.ascii("yochat-s2b-device-list/v2")

  def create(
    userPrivate: Ed25519Private,
    userPub: Ed25519Public,
    version: Long,
    issuedAt: Long,
    entries: Vector[PeerDeviceEntry],
    features: Long = 0L
  ): Either[CoreError, PeerDeviceList] =
    normalize(entries).flatMap { sorted =>
      validate(userPub, version, issuedAt, sorted, features).flatMap { _ =>
        Crypto
          .sign(userPrivate, signingInput(userPub, version, issuedAt, sorted, features, Version))
          .map(signature => PeerDeviceList(userPub, version, issuedAt, sorted, signature, features, Version))
      }
    }

  def signingInput(
    userPub: Ed25519Public,
    version: Long,
    issuedAt: Long,
    entries: Vector[PeerDeviceEntry],
    features: Long = 0L,
    format: Int = Version
  ): ByteVector =
    val domain = if format == LegacyVersion then Domain else DomainV2
    domain ++ canonical(userPub, version, issuedAt, entries, features, format)

  def canonical(
    userPub: Ed25519Public,
    version: Long,
    issuedAt: Long,
    entries: Vector[PeerDeviceEntry],
    features: Long = 0L,
    format: Int = Version
  ): ByteVector =
    val writer = CanonicalWriter()
      .u8(format)
      .fixed(userPub.bytes)
      .u64(version)
      .u64(issuedAt)
    if format != LegacyVersion then writer.u64(features)
    writer.u32(entries.size.toLong)
    entries.foreach { entry =>
      writer.fixed(entry.devicePub.bytes)
      writer.u8(entry.role.code)
      writer.string(entry.name)
      writer.u64(entry.joinedAt)
      writer.u64(entry.revokedAt.getOrElse(0L))
      writer.u8(if entry.revokedAt.isDefined then 1 else 0)
      if format != LegacyVersion then
        entry.fanoutPub match
          case None        => writer.u8(0)
          case Some(value) => writer.u8(1).fixed(value.bytes)
    }
    writer.result

  def fromBytes(value: ByteVector): Either[CoreError, PeerDeviceList] =
    if value.size < Signature.Length.toLong then Left(CoreError.InvalidEncoding)
    else
      for
        decoded   <- decode(value.dropRight(Signature.Length.toLong))
        signature <- Signature.from(value.takeRight(Signature.Length.toLong))
        result     = PeerDeviceList(decoded._1, decoded._2, decoded._3, decoded._4, signature, decoded._5, decoded._6)
        _         <- if result.verify then Right(()) else Left(CoreError.InvalidSignature)
      yield result

  private def decode(
    value: ByteVector
  ): Either[CoreError, (Ed25519Public, Long, Long, Vector[PeerDeviceEntry], Long, Int)] =
    val reader = CanonicalReader(value)
    for
      (version, r1)     <- reader.u8
      _                 <- if version == LegacyVersion || version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (userRaw, r2)     <- r1.fixed(32)
      userPub           <- Ed25519Public.from(userRaw)
      (listVersion, r3) <- r2.u64
      (issuedAt, r4)    <- r3.u64
      (features, r5)    <- if version == LegacyVersion then Right((0L, r4)) else r4.u64
      (count, r6)       <- r5.u32
      _                 <- if count <= MaxEntries.toLong then Right(()) else Left(CoreError.InvalidEncoding)
      (entries, r7)     <- decodeEntries(r6, count.toInt, Vector.empty, version)
      _                 <- r7.finish
      sorted            <- normalize(entries)
      _                 <- validate(userPub, listVersion, issuedAt, sorted, features)
    yield (userPub, listVersion, issuedAt, sorted, features, version)

  private def decodeEntries(
    reader: CanonicalReader,
    remaining: Int,
    acc: Vector[PeerDeviceEntry],
    version: Int
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
        (fanout, r7)     <- if version == LegacyVersion then Right((None, r6))
                            else
                              r6.u8.flatMap {
                                case (0, next) => Right((None, next))
                                case (1, next) =>
                                  next.fixed(32).flatMap { case (raw, after) => X25519Public.from(raw).map(value => (Some(value), after)) }
                                case _         => Left(CoreError.InvalidEncoding)
                              }
        next             <- decodeEntries(r7, remaining - 1, acc :+ PeerDeviceEntry(devicePub, role, name, joinedAt, revokedAt, fanout), version)
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
    entries: Vector[PeerDeviceEntry],
    features: Long
  ): Either[CoreError, Unit] =
    if userPub.bytes.size != 32L then Left(CoreError.InvalidLength("user_pub", 32, userPub.bytes.size))
    else if version < 0L || issuedAt < 0L then Left(CoreError.InvalidEncoding)
    else if features < 0L || (features & ~KnownFeatures) != 0L then Left(CoreError.InvalidEncoding)
    else if entries.size > MaxEntries then Left(CoreError.InvalidEncoding)
    else if entries.exists(entry => entry.joinedAt < 0L || entry.revokedAt.exists(_ < entry.joinedAt)) then Left(CoreError.InvalidEncoding)
    else if entries.exists(entry => entry.fanoutPub.exists(_.bytes.size != 32L)) then Left(CoreError.InvalidEncoding)
    else Right(())
