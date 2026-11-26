package yochat.store

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.SecureRandom
import java.util.Comparator

import scala.util.Using

import cats.effect.unsafe.implicits.global
import cats.effect.IO
import org.typelevel.doobie.ConnectionIO
import scodec.bits.ByteVector

import yochat.core.{
  Ed25519KeyPair,
  Ed25519Private,
  Ed25519Public,
  Key32,
  MessageHeader,
  MessageKind,
  Nonce,
  SessionId,
  SessionKeys,
  SessionSetup,
  Signature,
  WireFrame,
  X25519KeyPair,
  X25519Private
}

object StoreTestSupport:

  private val Random                      = new SecureRandom()
  private val TestPassphrase: Array[Char] = "correct horse battery staple".toCharArray

  val master: MasterKey =
    MasterKey.from(ByteVector.fill(32)(7.toByte)).fold(error => throw new AssertionError(error.toString), identity)

  def passphrase: Array[Char] = TestPassphrase.clone()

  def config(directory: Path): StoreConfig =
    val paths = StorePaths.under(directory)
    if Files.exists(paths.database) then StoreConfig.default(directory, KeySource.Existing(MasterKeyProvider.fixed(master)))
    else StoreConfig.default(directory, KeySource.New(MasterKeyProvider.fixed(master)))

  def keyFileConfig(directory: Path): StoreConfig =
    StoreConfig.default(directory, KeySource.KeyFile(passphrase))

  def withStore[A](directory: Path)(body: Store => IO[A]): A =
    Store
      .open(config(directory))
      .use {
        case Left(error)  => IO.raiseError(new AssertionError(s"store open failed: ${error.code}"))
        case Right(store) => body(store)
      }
      .unsafeRunSync()

  def withKeyFileStore[A](directory: Path)(body: Store => IO[A]): A =
    Store
      .open(keyFileConfig(directory))
      .use {
        case Left(error)  => IO.raiseError(new AssertionError(s"store open failed: ${error.code}"))
        case Right(store) => body(store)
      }
      .unsafeRunSync()

  def right[A](value: Either[StorageError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => throw new AssertionError(s"expected Right, got ${error.code}")

  def left[A](value: Either[StorageError, A]): StorageError =
    value match
      case Left(error) => error
      case Right(_)    => throw new AssertionError("expected Left")

  def state(safetyVerified: Boolean, marker: Int = 1): yochat.core.RatchetState =
    val sessionId = sessionIdOf(marker)
    val local     = xpair()
    val peer      = xpair()
    val keys      = SessionKeys(sessionId, key32(), key32())
    val created   = SessionSetup
      .initiator(keys, edPublic(), edPublic(), local, peer.publicKey)
      .fold(error => throw new AssertionError(error.toString), identity)
    created.copy(safetyVerified = safetyVerified)

  def stateWith(
    peerUser: Ed25519Public,
    peerDevice: Ed25519Public,
    sessionMarker: Int,
    safetyVerified: Boolean
  ): yochat.core.RatchetState =
    val sessionId = sessionIdOf(sessionMarker)
    val local     = xpair()
    val peer      = xpair()
    val keys      = SessionKeys(sessionId, key32(), key32())
    SessionSetup
      .initiator(keys, peerUser, peerDevice, local, peer.publicKey)
      .fold(error => throw new AssertionError(error.toString), identity)
      .copy(safetyVerified = safetyVerified)

  def raw[A](store: Store)(program: ConnectionIO[A]): A =
    right(store.transact(program).unsafeRunSync())

  def openResult(config: StoreConfig): Either[StorageError, Unit] =
    Store
      .open(config)
      .use {
        case Left(error) => IO.pure(Left(error))
        case Right(_)    => IO.pure(Right(()))
      }
      .unsafeRunSync()

  def contact(safetyVerified: Boolean = false): ContactData =
    ContactData(
      channelLabel = "test",
      channelAccount = "alice@example.test",
      peerChannelAccount = Some("bob@example.test"),
      alias = Some("Bob"),
      note = None,
      safetyVerified = safetyVerified,
      confirmationState = ContactConfirmation.Confirmed
    )

  def wire(state: yochat.core.RatchetState, seq: Long, plaintext: String = "wire"): ByteVector =
    val header     = MessageHeader(
      version = MessageHeader.Version,
      kind = MessageKind.Data,
      sessionId = state.sessionId,
      devicePub = state.peerDevicePub,
      dhPub = state.dhSelf.publicKey,
      pn = 0L,
      n = 0L,
      seq = seq,
      nonce = Nonce.from(ByteVector.view(random(12))).fold(error => throw new AssertionError(error.toString), identity),
      control = None
    )
    val ciphertext = ByteVector.view(random(32).appendedAll(plaintext.getBytes(StandardCharsets.UTF_8)))
    val signature  = Signature.from(ByteVector.view(random(Signature.Length))).fold(error => throw new AssertionError(error.toString), identity)
    WireFrame.create(header, ciphertext, signature).fold(error => throw new AssertionError(error.toString), identity).bytes

  def body(text: String): ByteVector = ByteVector.view(text.getBytes(StandardCharsets.UTF_8))

  def conversationId(marker: Int = 1): ByteVector = ByteVector.view(Array.fill[Byte](32)(marker.toByte))

  def sessionIdOf(marker: Int): SessionId =
    SessionId.from(ByteVector.view(Array.fill[Byte](32)(marker.toByte))).fold(error => throw new AssertionError(error.toString), identity)

  def temporaryDirectory(prefix: String): Path =
    Files.createTempDirectory(prefix)

  def deleteTree(directory: Path): Unit =
    if Files.exists(directory) then
      Using.resource(Files.walk(directory)) { stream =>
        stream.sorted(Comparator.reverseOrder[Path]()).forEach(path => Files.delete(path))
      }

  def edPair(): Ed25519KeyPair =
    val generated = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val publicKey = Ed25519Public
      .from(ByteVector.view(generated.getPublic.getEncoded).drop(12))
      .fold(error => throw new IllegalStateException(s"generated public key: $error"), identity)
    val privateKey = Ed25519Private
      .from(ByteVector.view(generated.getPrivate.getEncoded).drop(16))
      .fold(error => throw new IllegalStateException(s"generated private key: $error"), identity)
    Ed25519KeyPair(privateKey, publicKey)

  def edPublic(): Ed25519Public =
    Ed25519Public.from(ByteVector.view(random(32))).fold(error => throw new AssertionError(error.toString), identity)

  def key32(): Key32 =
    Key32.from(ByteVector.view(random(32))).fold(error => throw new AssertionError(error.toString), identity)

  def xpair(): X25519KeyPair =
    val seed = X25519Private.from(ByteVector.view(random(32))).fold(error => throw new AssertionError(error.toString), identity)
    X25519KeyPair.fromSeed(seed).fold(error => throw new AssertionError(error.toString), identity)

  def random(length: Int): Array[Byte] =
    val bytes = new Array[Byte](length)
    Random.nextBytes(bytes)
    bytes
