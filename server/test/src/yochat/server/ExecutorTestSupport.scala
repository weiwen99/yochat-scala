package yochat.server

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.KeyPairGenerator
import java.util.Comparator

import scala.util.Using

import cats.effect.unsafe.implicits.global
import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.*
import yochat.core.app.*
import yochat.store.*

object ExecutorTestSupport:

  val master: MasterKey =
    MasterKey.from(ByteVector.fill(32)(9.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)

  final case class Fixture(
    identity: LocalIdentity,
    peerUser: Ed25519Public,
    peerDevice: Ed25519Public,
    sessionId: SessionId,
    conversationId: ByteVector,
    state: RatchetState
  )

  def fixture(): Fixture =
    val user         = edPair()
    val device       = edPair()
    val peerUser     = edPair().publicKey
    val peerDevice   = edPair().publicKey
    val localRatchet = xpair(1)
    val peerRatchet  = xpair(2)
    val sessionId    = SessionId.from(ByteVector.fill(32)(0x33.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)
    val keys         = SessionKeys(sessionId, key32(3), key32(4))
    val state        = SessionSetup
      .initiator(keys, peerUser, peerDevice, localRatchet, peerRatchet.publicKey)
      .fold(error => throw new IllegalStateException(error.toString), Predef.identity)
      .copy(safetyVerified = true)
    Fixture(LocalIdentity(user, device), peerUser, peerDevice, sessionId, ByteVector.fill(32)(0x44.toByte), state)

  def withStore[A](body: Store => IO[A]): A =
    val directory = Files.createTempDirectory("yochat-executor-")
    try
      val config = StoreConfig.default(directory, KeySource.New(MasterKeyProvider.fixed(master)))
      Store
        .open(config)
        .use {
          case Left(error)  => IO.raiseError(new IllegalStateException(error.code))
          case Right(store) => body(store)
        }
        .unsafeRunSync()
    finally deleteTree(directory)

  def appState(fixture: Fixture): AppState =
    val contact = AppContact(
      fixture.peerUser,
      fixture.peerDevice,
      "test",
      "@peer",
      Some("@peer"),
      safetyVerified = true,
      yochat.core.app.ContactConfirmation.Confirmed
    )
    val session = AppSession(fixture.state, fixture.conversationId, 1L)
    AppState.empty.copy(
      identity = Some(fixture.identity),
      lock = LockState.Unlocked(0L),
      idleDeadline = Some(1_000_000L),
      contacts = Map(fixture.peerUser -> contact),
      sessions = Map(fixture.sessionId -> session),
      conversations = Map(
        fixture.conversationId -> Conversation(
          fixture.conversationId,
          fixture.peerUser,
          fixture.peerDevice,
          Map.empty,
          Set.empty,
          Map.empty
        )
      ),
      connection = ConnectionState.Online
    )

  def initialize(fixture: Fixture, store: Store): IO[Unit] =
    for
      _ <- store.insertContact(fixture.peerUser, contactData(), 0L)
      _ <- store.insertSession(fixture.state, 0L)
    yield ()

  def contactData(): ContactData =
    ContactData("test", "@peer", Some("@peer"), None, None, safetyVerified = true, yochat.store.ContactConfirmation.Confirmed)

  def wire(fixture: Fixture, seq: Long): ByteVector = wire(fixture, seq, fixture.peerDevice)

  def wire(fixture: Fixture, seq: Long, devicePub: Ed25519Public): ByteVector =
    val header     = MessageHeader(
      version = MessageHeader.Version,
      kind = MessageKind.Data,
      sessionId = fixture.sessionId,
      devicePub = devicePub,
      dhPub = fixture.state.dhSelf.publicKey,
      pn = 0L,
      n = 0L,
      seq = seq,
      nonce = nonce(seq.toInt),
      control = None
    )
    val ciphertext = ByteVector.view(Array.fill[Byte](32)(seq.toByte))
    val signature  =
      Signature.from(ByteVector.fill(Signature.Length)(seq.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)
    WireFrame.create(header, ciphertext, signature).fold(error => throw new IllegalStateException(error.toString), identity).bytes

  def body(text: String): ByteVector = ByteVector.view(text.getBytes(StandardCharsets.UTF_8))

  def right[A](value: Either[StorageError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => throw new AssertionError(error.code)

  def executionRight[A](value: Either[AppExecutionError, A]): A =
    value match
      case Right(result) => result
      case Left(error)   => throw new AssertionError(error.code)

  def edPair(): Ed25519KeyPair =
    val generated  = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val privateKey = Ed25519Private
      .from(ByteVector.view(generated.getPrivate.getEncoded).takeRight(32))
      .fold(error => throw new IllegalStateException(error.toString), identity)
    val publicKey  = Ed25519Public
      .from(ByteVector.view(generated.getPublic.getEncoded).takeRight(32))
      .fold(error => throw new IllegalStateException(error.toString), identity)
    Ed25519KeyPair(privateKey, publicKey)

  def xpair(seed: Int): X25519KeyPair =
    val value = X25519Private.from(ByteVector.fill(32)(seed.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)
    X25519KeyPair.fromSeed(value).fold(error => throw new IllegalStateException(error.toString), identity)

  def nonce(seed: Int): Nonce =
    Nonce.from(ByteVector.fill(Nonce.Length)(seed.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)

  def key32(seed: Int): Key32 =
    Key32.from(ByteVector.fill(32)(seed.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)

  def deleteTree(directory: Path): Unit =
    if Files.exists(directory) then
      Using.resource(Files.walk(directory)) { stream =>
        stream.sorted(Comparator.reverseOrder[Path]()).forEach(path => Files.delete(path))
      }
