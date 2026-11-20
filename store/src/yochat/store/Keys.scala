package yochat.store

import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock

import cats.effect.IO
import scodec.bits.ByteVector

/** A 32-byte instance secret. It is never persisted in clear and never appears in `toString`. */
final class MasterKey private (private[store] val bytes: ByteVector):

  override def toString: String = "MasterKey(<redacted>)"

object MasterKey:

  val Length: Int = 32

  def from(value: ByteVector): Either[StorageError, MasterKey] =
    if value.size == Length.toLong then Right(new MasterKey(value)) else Left(StorageError.InvalidArgument("master_key"))

  def random: IO[MasterKey] =
    IO.delay {
      val random = new SecureRandom()
      val bytes  = new Array[Byte](Length)
      random.nextBytes(bytes)
      new MasterKey(ByteVector.view(bytes))
    }

/** Supplies an already-created master key, for example from the Electron keychain bridge or a test fixture. */
trait MasterKeyProvider:

  def load: IO[MasterKey]

object MasterKeyProvider:

  def fixed(master: MasterKey): MasterKeyProvider =
    new MasterKeyProvider:
      def load: IO[MasterKey] = IO.pure(master)

/** How this process obtains the master key. `New` is only valid when no instance artifact exists. */
enum KeySource:

  case KeyFile(passphrase: Array[Char])
  case Existing(provider: MasterKeyProvider)
  case New(provider: MasterKeyProvider)

  override def toString: String =
    this match
      case KeyFile(_)  => "KeyFile(<redacted>)"
      case Existing(_) => "Existing(<redacted>)"
      case New(_)      => "New(<redacted>)"

final case class StorePaths(database: Path, identityFile: Path, keyFile: Path):

  def directory: Path = database.getParent

object StorePaths:

  def under(directory: Path): StorePaths =
    StorePaths(
      database = directory.resolve("data.db"),
      identityFile = directory.resolve("identity.bin"),
      keyFile = directory.resolve("keystore.bin")
    )

final case class StoreConfig(paths: StorePaths, keySource: KeySource, clock: Clock)

object StoreConfig:

  def default(directory: Path, keySource: KeySource): StoreConfig =
    StoreConfig(StorePaths.under(directory), keySource, Clock.systemUTC())
