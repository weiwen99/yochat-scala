package yochat.store

import java.nio.file.Path
import java.security.KeyPairGenerator

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.{Crypto, Ed25519Private, Ed25519Public, Key32, Nonce}

final case class IdentityKeys(
  userPrivate: Ed25519Private,
  userPublic: Ed25519Public,
  devicePrivate: Ed25519Private,
  devicePublic: Ed25519Public
):

  override def toString: String = "IdentityKeys(<redacted>)"

/** `identity.bin`: four 32-byte Ed25519 values, encrypted with the identity subkey. The file is immutable in S1. */
object IdentityFile:

  val Length: Int = 32

  private val Magic       = Crypto.ascii("YCID")
  private val Version     = 1
  private val AadDomain   = Crypto.ascii("yochat-store/identity-file/v1")
  private val FileLength  = 161
  private val PlainLength = 4 * Length

  def isPresent(path: Path): Boolean = SecureFiles.exists(path)

  def create(path: Path, keys: IdentityKeys, identityKey: Key32): IO[Either[StorageError, Unit]] =
    if isPresent(path) then IO.pure(Left(StorageError.WriteConflict))
    else
      IO.delay {
        val nonce   = StoreCrypto.randomNonceUnsafe()
        val aad     = AadDomain ++ ByteVector(Version.toByte)
        val plain   = keys.userPrivate.bytes ++ keys.userPublic.bytes ++ keys.devicePrivate.bytes ++ keys.devicePublic.bytes
        val content =
          for cipher <- Crypto.aeadSeal(identityKey, nonce, aad, plain).left.map(_ => StorageError.StorageFailure)
          yield Magic ++ ByteVector(Version.toByte) ++ nonce.bytes ++ cipher
        content
      }.flatMap {
        case Left(error)  => IO.pure(Left(error))
        case Right(value) =>
          SecureFiles.writePrivate(path, value.toArray).attempt.map {
            case Right(_) => Right(())
            case Left(_)  => Left(StorageError.StorageFailure)
          }
      }

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
        userPrivate = Ed25519Private.from(rawPrivate(user)).fold(_ => throw new IllegalStateException("user private key"), identity),
        userPublic = Ed25519Public.from(rawPublic(user)).fold(_ => throw new IllegalStateException("user public key"), identity),
        devicePrivate = Ed25519Private.from(rawPrivate(device)).fold(_ => throw new IllegalStateException("device private key"), identity),
        devicePublic = Ed25519Public.from(rawPublic(device)).fold(_ => throw new IllegalStateException("device public key"), identity)
      )
    }

  private def parse(bytes: Array[Byte], identityKey: Key32): Either[StorageError, IdentityKeys] =
    val value = ByteVector.view(bytes)
    if value.size != FileLength.toLong then Left(StorageError.InvalidEnvelope)
    else if !value.take(4).equals(Magic) || value(4).toInt != Version then Left(StorageError.InvalidEnvelope)
    else
      for
        nonce <- Nonce.from(value.slice(5, 17)).left.map(_ => StorageError.InvalidEnvelope)
        aad    = AadDomain ++ ByteVector(Version.toByte)
        plain <- Crypto.aeadOpen(identityKey, nonce, aad, value.drop(17)).left.map(_ => StorageError.InvalidCiphertext)
        keys  <- decode(plain)
      yield keys

  private def decode(value: ByteVector): Either[StorageError, IdentityKeys] =
    if value.size != PlainLength.toLong then Left(StorageError.InvalidEnvelope)
    else
      for
        userPrivateRaw   <- Ed25519Private.from(value.take(32)).left.map(_ => StorageError.InvalidEnvelope)
        userPublicRaw    <- Ed25519Public.from(value.slice(32, 64)).left.map(_ => StorageError.InvalidEnvelope)
        devicePrivateRaw <- Ed25519Private.from(value.slice(64, 96)).left.map(_ => StorageError.InvalidEnvelope)
        devicePublicRaw  <- Ed25519Public.from(value.slice(96, 128)).left.map(_ => StorageError.InvalidEnvelope)
      yield IdentityKeys(userPrivateRaw, userPublicRaw, devicePrivateRaw, devicePublicRaw)

  private def rawPublic(pair: java.security.KeyPair): ByteVector =
    val encoded = ByteVector.view(pair.getPublic.getEncoded)
    encoded.drop(encoded.size - Length)

  private def rawPrivate(pair: java.security.KeyPair): ByteVector =
    val encoded = ByteVector.view(pair.getPrivate.getEncoded)
    encoded.drop(encoded.size - Length)
