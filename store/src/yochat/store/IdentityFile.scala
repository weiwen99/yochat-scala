package yochat.store

import java.nio.file.Path
import java.security.KeyPairGenerator

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.{Crypto, Ed25519Private, Ed25519Public, Key32, Nonce}

final case class IdentityKeys(
  userPrivate: Option[Ed25519Private],
  userPublic: Ed25519Public,
  devicePrivate: Ed25519Private,
  devicePublic: Ed25519Public,
  role: IdentityRole
):

  def hasUserPrivate: Boolean = userPrivate.isDefined

  override def toString: String = s"IdentityKeys(role=$role, userPrivate=$hasUserPrivate)"

/** `identity.bin`: v1 stores four Ed25519 private/public values; v2 permits a companion with no user private key. The file is immutable. */
object IdentityFile:

  val Length: Int = 32

  private val Magic                = Crypto.ascii("YCID")
  private val VersionOne           = 1
  private val VersionTwo           = 2
  private val AadDomainOne         = Crypto.ascii("yochat-store/identity-file/v1")
  private val AadDomainTwo         = Crypto.ascii("yochat-store/identity-file/v2")
  private val FileLengthOne        = 161
  private val PlainLengthOne       = 4 * Length
  private val PlainLengthCompanion = 3 * Length
  private val HasUserPrivateFlag   = 1
  private val CompanionFlag        = 2
  private val KnownFlags           = HasUserPrivateFlag | CompanionFlag

  def isPresent(path: Path): Boolean = SecureFiles.exists(path)

  def create(path: Path, keys: IdentityKeys, identityKey: Key32): IO[Either[StorageError, Unit]] =
    if isPresent(path) then IO.pure(Left(StorageError.WriteConflict))
    else
      IO.delay {
        val userPrivate = keys.userPrivate.map(_.bytes).getOrElse(ByteVector.empty)
        val flags       =
          (if keys.hasUserPrivate then HasUserPrivateFlag else 0) |
            (if keys.role == IdentityRole.Companion then CompanionFlag else 0)
        val plain       =
          if keys.hasUserPrivate then userPrivate ++ keys.userPublic.bytes ++ keys.devicePrivate.bytes ++ keys.devicePublic.bytes
          else keys.userPublic.bytes ++ keys.devicePrivate.bytes ++ keys.devicePublic.bytes
        val nonce       = StoreCrypto.randomNonceUnsafe()
        val aad         = AadDomainTwo ++ ByteVector(VersionTwo.toByte, flags.toByte)
        val content     =
          for cipher <- Crypto.aeadSeal(identityKey, nonce, aad, plain).left.map(_ => StorageError.StorageFailure)
          yield Magic ++ ByteVector(VersionTwo.toByte, flags.toByte) ++ nonce.bytes ++ cipher
        content
      }.flatMap {
        case Left(error)  => IO.pure(Left(error))
        case Right(value) =>
          SecureFiles.writePrivate(path, value.toArray).attempt.map {
            case Right(_) => Right(())
            case Left(_)  => Left(StorageError.StorageFailure)
          }
      }

  def createCompanion(
    path: Path,
    userPublic: Ed25519Public,
    devicePrivate: Ed25519Private,
    devicePublic: Ed25519Public,
    identityKey: Key32
  ): IO[Either[StorageError, Unit]] =
    create(
      path,
      IdentityKeys(
        userPrivate = None,
        userPublic = userPublic,
        devicePrivate = devicePrivate,
        devicePublic = devicePublic,
        role = IdentityRole.Companion
      ),
      identityKey
    )

  def load(path: Path, identityKey: Key32): IO[Either[StorageError, IdentityKeys]] =
    SecureFiles.read(path).attempt.flatMap {
      case Left(_)      => IO.pure(Left(StorageError.NotFound("identity")))
      case Right(bytes) =>
        IO.delay(parse(bytes, identityKey)).attempt.map {
          case Right(value) => value
          case Left(_)      => Left(StorageError.InvalidEnvelope)
        }
    }

  def generate: IO[IdentityKeys] =
    IO.blocking {
      val user   = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      val device = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      IdentityKeys(
        userPrivate = Some(edPrivate(user)),
        userPublic = edPublic(user),
        devicePrivate = edPrivate(device),
        devicePublic = edPublic(device),
        role = IdentityRole.Primary
      )
    }

  def generateCompanion(userPublic: Ed25519Public): IO[IdentityKeys] =
    IO.blocking {
      val device = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      IdentityKeys(
        userPrivate = None,
        userPublic = userPublic,
        devicePrivate = edPrivate(device),
        devicePublic = edPublic(device),
        role = IdentityRole.Companion
      )
    }

  private def parse(bytes: Array[Byte], identityKey: Key32): Either[StorageError, IdentityKeys] =
    val value = ByteVector.view(bytes)
    if value.size < 5L || !value.take(4).equals(Magic) then Left(StorageError.InvalidEnvelope)
    else
      value(4).toInt match
        case VersionOne => parseV1(value, identityKey)
        case VersionTwo => parseV2(value, identityKey)
        case _          => Left(StorageError.InvalidEnvelope)

  private def parseV1(value: ByteVector, identityKey: Key32): Either[StorageError, IdentityKeys] =
    if value.size != FileLengthOne.toLong then Left(StorageError.InvalidEnvelope)
    else
      for
        nonce <- Nonce.from(value.slice(5, 17)).left.map(_ => StorageError.InvalidEnvelope)
        aad    = AadDomainOne ++ ByteVector(VersionOne.toByte)
        plain <- Crypto.aeadOpen(identityKey, nonce, aad, value.drop(17)).left.map(_ => StorageError.InvalidCiphertext)
        keys  <- decode(plain, hasUserPrivate = true, IdentityRole.Primary)
      yield keys

  private def parseV2(value: ByteVector, identityKey: Key32): Either[StorageError, IdentityKeys] =
    if value.size < 18L then Left(StorageError.InvalidEnvelope)
    else
      val flags = value(5).toInt
      if (flags & ~KnownFlags) != 0 then Left(StorageError.InvalidEnvelope)
      else
        val hasUserPrivate = (flags & HasUserPrivateFlag) != 0
        val companion      = (flags & CompanionFlag) != 0
        val role           = IdentityRole.fromCompanionFlag(companion)
        val nonceOffset    = 6
        val bodyOffset     = nonceOffset + StoreCrypto.NonceLength
        for
          nonce <- Nonce.from(value.slice(nonceOffset, bodyOffset)).left.map(_ => StorageError.InvalidEnvelope)
          aad    = AadDomainTwo ++ ByteVector(VersionTwo.toByte, flags.toByte)
          plain <- Crypto.aeadOpen(identityKey, nonce, aad, value.drop(bodyOffset)).left.map(_ => StorageError.InvalidCiphertext)
          keys  <- decode(plain, hasUserPrivate, role)
        yield keys

  private def decode(value: ByteVector, hasUserPrivate: Boolean, role: IdentityRole): Either[StorageError, IdentityKeys] =
    if hasUserPrivate then
      if value.size != PlainLengthOne.toLong then Left(StorageError.InvalidEnvelope)
      else
        for
          userPrivateRaw   <- Ed25519Private.from(value.take(32)).left.map(_ => StorageError.InvalidEnvelope)
          userPublicRaw    <- Ed25519Public.from(value.slice(32, 64)).left.map(_ => StorageError.InvalidEnvelope)
          devicePrivateRaw <- Ed25519Private.from(value.slice(64, 96)).left.map(_ => StorageError.InvalidEnvelope)
          devicePublicRaw  <- Ed25519Public.from(value.slice(96, 128)).left.map(_ => StorageError.InvalidEnvelope)
        yield IdentityKeys(Some(userPrivateRaw), userPublicRaw, devicePrivateRaw, devicePublicRaw, role)
    else if value.size != PlainLengthCompanion.toLong then Left(StorageError.InvalidEnvelope)
    else
      for
        userPublicRaw    <- Ed25519Public.from(value.take(32)).left.map(_ => StorageError.InvalidEnvelope)
        devicePrivateRaw <- Ed25519Private.from(value.slice(32, 64)).left.map(_ => StorageError.InvalidEnvelope)
        devicePublicRaw  <- Ed25519Public.from(value.slice(64, 96)).left.map(_ => StorageError.InvalidEnvelope)
      yield IdentityKeys(None, userPublicRaw, devicePrivateRaw, devicePublicRaw, role)

  private def edPublic(pair: java.security.KeyPair): Ed25519Public =
    Ed25519Public.from(rawPublic(pair)).fold(_ => throw new IllegalStateException("device public key"), identity)

  private def edPrivate(pair: java.security.KeyPair): Ed25519Private =
    Ed25519Private.from(rawPrivate(pair)).fold(_ => throw new IllegalStateException("device private key"), identity)

  private def rawPublic(pair: java.security.KeyPair): ByteVector =
    val encoded = ByteVector.view(pair.getPublic.getEncoded)
    encoded.drop(encoded.size - Length)

  private def rawPrivate(pair: java.security.KeyPair): ByteVector =
    val encoded = ByteVector.view(pair.getPrivate.getEncoded)
    encoded.drop(encoded.size - Length)
