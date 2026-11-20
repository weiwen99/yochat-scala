package yochat.store

import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.SecureRandom

import scala.util.control.NonFatal

import cats.effect.IO
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import scodec.bits.ByteVector

import yochat.core.{Crypto, Key32, Nonce}

/** `keystore.bin`: Argon2id-wrapped master secret. The header is authenticated but contains no plaintext secret. */
object KeyFile:

  final case class Params(memoryKiB: Int, iterations: Int, parallelism: Int)

  val DefaultParams: Params = Params(memoryKiB = 65_536, iterations = 3, parallelism = 1)

  private val Magic        = Crypto.ascii("YCKS")
  private val Version      = 1
  private val KdfArgon2id  = 1
  private val SaltLength   = 16
  private val HeaderLength = 34
  private val FileLength   = 94
  private val AadDomain    = "yochat-store/keystore/v1"
  private val Random       = new SecureRandom()

  final private case class Parsed(
    params: Params,
    salt: ByteVector,
    nonce: Nonce,
    ciphertext: ByteVector,
    aad: ByteVector
  )

  def isPresent(path: Path): Boolean = SecureFiles.exists(path)

  def create(path: Path, passphrase: Array[Char]): IO[Either[StorageError, MasterKey]] =
    if isPresent(path) then IO.pure(Left(StorageError.WriteConflict))
    else
      MasterKey.random.flatMap { master =>
        IO.delay {
          val salt   = randomBytes(SaltLength)
          val nonce  = StoreCrypto.randomNonceUnsafe()
          val params = DefaultParams
          val header = headerBytes(params, salt)
          val aad    = StoreCrypto.aad(AadDomain, header)
          val key    = derive(passphrase, params, salt)
          key.flatMap { kek =>
            Crypto.aeadSeal(kek, nonce, aad, master.bytes).left.map(_ => StorageError.StorageFailure).map { ciphertext =>
              val file = header ++ nonce.bytes ++ ciphertext
              (file, master)
            }
          }
        }.flatMap {
          case Left(error)  => IO.pure(Left(error))
          case Right(value) =>
            SecureFiles.writePrivate(path, value._1.toArray).attempt.map {
              case Right(_) => Right(value._2)
              case Left(_)  => Left(StorageError.StorageFailure)
            }
        }
      }

  def unlock(path: Path, passphrase: Array[Char]): IO[Either[StorageError, MasterKey]] =
    SecureFiles.read(path).attempt.flatMap {
      case Left(_)      => IO.pure(Left(StorageError.UnlockFailed))
      case Right(bytes) =>
        IO.delay {
          for
            parsed <- parse(bytes)
            key    <- derive(passphrase, parsed.params, parsed.salt)
            plain  <- Crypto.aeadOpen(key, parsed.nonce, parsed.aad, parsed.ciphertext).left.map(_ => StorageError.UnlockFailed)
            master <- MasterKey.from(plain)
          yield master
        }.attempt
          .map {
            case Right(value) => value
            case Left(_)      => Left(StorageError.UnlockFailed)
          }
    }

  private def parse(bytes: Array[Byte]): Either[StorageError, Parsed] =
    val value = ByteVector.view(bytes)
    if value.size != FileLength.toLong then Left(StorageError.UnlockFailed)
    else if !value.take(4).equals(Magic) then Left(StorageError.UnlockFailed)
    else if value(4).toInt != Version || value(5).toInt != KdfArgon2id then Left(StorageError.UnlockFailed)
    else
      for
        memory      <- u32(value, 6)
        iterations  <- u32(value, 10)
        parallelism <- u32(value, 14)
        _           <- checkBounds(memory, iterations, parallelism)
        params      <- validate(Params(memory.toInt, iterations.toInt, parallelism.toInt))
        salt         = value.slice(18, 34)
        nonce       <- Nonce.from(value.slice(34, 46)).left.map(_ => StorageError.UnlockFailed)
        header       = value.take(HeaderLength)
        aad          = StoreCrypto.aad(AadDomain, header)
      yield Parsed(params, salt, nonce, value.drop(46), aad)

  private def checkBounds(memory: Long, iterations: Long, parallelism: Long): Either[StorageError, Unit] =
    val valid = memory <= Int.MaxValue.toLong && iterations <= Int.MaxValue.toLong && parallelism <= Int.MaxValue.toLong
    if valid then Right(()) else Left(StorageError.UnlockFailed)

  private def validate(params: Params): Either[StorageError, Params] =
    val memoryValid     = params.memoryKiB >= 16 * 1024 && params.memoryKiB <= 256 * 1024
    val iterationsValid = params.iterations >= 1 && params.iterations <= 10
    val parallelValid   = params.parallelism >= 1 && params.parallelism <= 4
    if memoryValid && iterationsValid && parallelValid then Right(params) else Left(StorageError.UnlockFailed)

  private def derive(passphrase: Array[Char], params: Params, salt: ByteVector): Either[StorageError, Key32] =
    val output = new Array[Byte](32)
    try
      val builder   = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withMemoryAsKB(params.memoryKiB)
        .withIterations(params.iterations)
        .withParallelism(params.parallelism)
        .withSalt(salt.toArray)
      val generator = new Argon2BytesGenerator()
      generator.init(builder.build())
      generator.generateBytes(passphraseBytes(passphrase), output)
      Key32.from(ByteVector.view(output)).left.map(_ => StorageError.UnlockFailed)
    catch case NonFatal(_) => Left(StorageError.UnlockFailed)

  private def headerBytes(params: Params, salt: ByteVector): ByteVector =
    val out = new ByteArrayOutputStream(HeaderLength)
    out.write(Magic.toArray)
    out.write(Version)
    out.write(KdfArgon2id)
    out.write(u32(params.memoryKiB))
    out.write(u32(params.iterations))
    out.write(u32(params.parallelism))
    out.write(salt.toArray)
    ByteVector.view(out.toByteArray)

  private def passphraseBytes(passphrase: Array[Char]): Array[Byte] =
    val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
    val out    = new Array[Byte](buffer.remaining())
    buffer.get(out)
    out

  private def randomBytes(length: Int): ByteVector =
    val bytes = new Array[Byte](length)
    Random.nextBytes(bytes)
    ByteVector.view(bytes)

  private def u32(value: Long): Array[Byte] =
    ByteBuffer.allocate(4).putInt(value.toInt).array()

  private def u32(value: ByteVector, offset: Int): Either[StorageError, Long] =
    if offset < 0 || offset + 4 > value.size then Left(StorageError.UnlockFailed)
    else
      val number = ByteBuffer.wrap(value.slice(offset, offset + 4).toArray).getInt.toLong & 0xffffffffL
      Right(number)
