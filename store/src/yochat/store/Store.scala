package yochat.store

import java.nio.file.Path
import java.time.Clock

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import com.zaxxer.hikari.HikariConfig
import org.sqlite.{SQLiteConfig, SQLiteDataSource}
import org.typelevel.doobie.hikari.HikariTransactor
import org.typelevel.doobie.ConnectionIO
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, RatchetState, SessionId}

/** The production store boundary. Every public operation returns a stable error code, never a JDBC message. */
final class Store private[store] (
  private[store] val runtime: StoreRuntime,
  val isNew: Boolean,
  val identity: IdentityKeys
):

  def insertSession(state: RatchetState, expectedLockGeneration: Long): IO[Either[StorageError, SessionRecord]] =
    SessionRepository.insert(runtime, state, expectedLockGeneration)

  def loadSession(sessionId: SessionId): IO[Either[StorageError, SessionRecord]] =
    SessionRepository.load(runtime, sessionId)

  def updateSession(
    state: RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, SessionRecord]] =
    SessionRepository.update(runtime, state, expectedVersion, expectedLockGeneration)

  def appendOutbound(
    state: RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector,
    messageState: MessageState
  ): IO[Either[StorageError, AppendResult]] =
    MessageRepository.appendOutbound(
      runtime,
      state,
      expectedVersion,
      expectedLockGeneration,
      conversationId,
      authorPub,
      devicePub,
      seq,
      body,
      wire,
      messageState
    )

  def appendInbound(
    state: RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector
  ): IO[Either[StorageError, AppendResult]] =
    MessageRepository.appendInbound(
      runtime,
      state,
      expectedVersion,
      expectedLockGeneration,
      conversationId,
      authorPub,
      devicePub,
      seq,
      body,
      wire
    )

  def loadMessage(
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long
  ): IO[Either[StorageError, MessageRecord]] =
    MessageRepository.load(runtime, conversationId, sessionId, authorPub, devicePub, seq)

  def listMessages(
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    limit: Int
  ): IO[Either[StorageError, List[MessageRecord]]] =
    MessageRepository.list(runtime, conversationId, authorPub, limit)

  def setMessageState(
    messageId: Long,
    expected: MessageState,
    next: MessageState,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    MessageRepository.setState(runtime, messageId, expected, next, expectedLockGeneration)

  def setMessageStateByKey(
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    expected: MessageState,
    next: MessageState,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    MessageRepository.setStateByKey(
      runtime,
      conversationId,
      sessionId,
      authorPub,
      devicePub,
      seq,
      expected,
      next,
      expectedLockGeneration
    )

  def pendingCount(sessionId: SessionId): IO[Either[StorageError, Long]] =
    MessageRepository.pendingCount(runtime, sessionId)

  def retireSession(
    sessionId: SessionId,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    role: CutoverRole,
    evidence: CutoverEvidence
  ): IO[Either[StorageError, Unit]] =
    SessionRepository.retire(runtime, sessionId, expectedVersion, expectedLockGeneration, role, evidence)

  def insertContact(
    userPub: Ed25519Public,
    data: ContactData,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ContactRecord]] =
    ContactRepository.insert(runtime, userPub, data, expectedLockGeneration)

  def loadContact(userPub: Ed25519Public): IO[Either[StorageError, ContactRecord]] =
    ContactRepository.load(runtime, userPub)

  def updateContact(
    userPub: Ed25519Public,
    data: ContactData,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ContactRecord]] =
    ContactRepository.update(runtime, userPub, data, expectedVersion, expectedLockGeneration)

  def setSafetyVerified(
    userPub: Ed25519Public,
    verified: Boolean,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ContactRecord]] =
    ContactRepository.setSafetyVerified(runtime, userPub, verified, expectedVersion, expectedLockGeneration)

  def deleteContact(
    userPub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    ContactRepository.delete(runtime, userPub, expectedVersion, expectedLockGeneration)

  def insertInvitation(
    id: ByteVector,
    data: InvitationData,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, InvitationRecord]] =
    InvitationRepository.insert(runtime, id, data, expectedLockGeneration)

  def loadInvitation(id: ByteVector): IO[Either[StorageError, InvitationRecord]] =
    InvitationRepository.load(runtime, id)

  def updateInvitation(
    id: ByteVector,
    data: InvitationData,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, InvitationRecord]] =
    InvitationRepository.update(runtime, id, data, expectedVersion, expectedLockGeneration)

  def deleteInvitation(
    id: ByteVector,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    InvitationRepository.delete(runtime, id, expectedVersion, expectedLockGeneration)

  def createConversation(
    id: ByteVector,
    peerUserPub: Ed25519Public,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ConversationRecord]] =
    ConversationRepository.create(runtime, id, peerUserPub, expectedLockGeneration)

  def loadConversation(id: ByteVector): IO[Either[StorageError, ConversationRecord]] =
    ConversationRepository.load(runtime, id)

  def advanceLastAppliedSeq(
    id: ByteVector,
    expected: Long,
    next: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, ConversationRecord]] =
    ConversationRepository.advance(runtime, id, expected, next, expectedLockGeneration)

  def lockGeneration: IO[Either[StorageError, Long]] =
    runtime.run(Sql.lockGeneration)

  def bumpLockGeneration(expected: Long): IO[Either[StorageError, Long]] =
    MetaRepository.bumpLockGeneration(runtime, expected)

  def backup(target: Path): IO[Either[StorageError, Unit]] =
    Backup.create(runtime, target)

  private[store] def transact[A](program: ConnectionIO[A]): IO[Either[StorageError, A]] =
    runtime.transact(program)

object Store:

  import ArtifactState.*

  final private case class Prepared(paths: StorePaths, keys: StoreKeys, identity: IdentityKeys, isNew: Boolean, clock: Clock)

  /** Open an instance. A partial-instance or unlock failure is returned without opening the database. */
  def open(config: StoreConfig): Resource[IO, Either[StorageError, Store]] =
    Resource.eval(prepare(config)).flatMap {
      case Left(error)     => Resource.pure(Left(error))
      case Right(prepared) =>
        HikariTransactor.fromHikariConfig[IO](hikari(prepared.paths.database)).flatMap { transactor =>
          Resource.eval(create(prepared, transactor))
        }
    }

  private def prepare(config: StoreConfig): IO[Either[StorageError, Prepared]] =
    IO.blocking(artifactState(config.paths)).flatMap {
      case AllAbsent           =>
        config.keySource match
          case KeySource.KeyFile(passphrase) =>
            for
              _        <- SecureFiles.ensureDirectory(config.paths.directory)
              master   <- KeyFile.create(config.paths.keyFile, passphrase)
              prepared <- master match
                            case Left(error) => IO.pure(Left(error))
                            case Right(key)  => createInstance(config, key)
            yield prepared
          case KeySource.New(provider)       =>
            for
              _        <- SecureFiles.ensureDirectory(config.paths.directory)
              loaded   <- provider.load.attempt
              prepared <- loaded match
                            case Left(_)    => IO.pure(Left(StorageError.UnlockFailed))
                            case Right(key) => createInstance(config, key)
            yield prepared
          case KeySource.Existing(_)         => IO.pure(Left(StorageError.InstancePartiallyPresent))
      case CompleteFiles       =>
        config.keySource match
          case KeySource.KeyFile(passphrase) => unlockInstance(config, passphrase)
          case KeySource.Existing(provider)  => loadExisting(config, provider)
          case KeySource.New(_)              => IO.pure(Left(StorageError.InstancePartiallyPresent))
      case DatabaseAndIdentity =>
        config.keySource match
          case KeySource.Existing(provider) => loadExisting(config, provider)
          case KeySource.KeyFile(_)         => IO.pure(Left(StorageError.InstancePartiallyPresent))
          case KeySource.New(_)             => IO.pure(Left(StorageError.InstancePartiallyPresent))
      case PartialArtifacts    => IO.pure(Left(StorageError.InstancePartiallyPresent))
    }

  private def createInstance(config: StoreConfig, master: MasterKey): IO[Either[StorageError, Prepared]] =
    val keys = StoreKeys.from(master)
    for
      identity <- IdentityFile.generate
      created  <- IdentityFile.create(config.paths.identityFile, identity, keys.identityKey)
    yield created.map(_ => Prepared(config.paths, keys, identity, isNew = true, config.clock))

  private def unlockInstance(config: StoreConfig, passphrase: Array[Char]): IO[Either[StorageError, Prepared]] =
    KeyFile.unlock(config.paths.keyFile, passphrase).flatMap {
      case Left(error)   => IO.pure(Left(error))
      case Right(master) =>
        val keys = StoreKeys.from(master)
        IdentityFile
          .load(config.paths.identityFile, keys.identityKey)
          .map(_.map(identity => Prepared(config.paths, keys, identity, isNew = false, config.clock)))
    }

  private def loadExisting(config: StoreConfig, provider: MasterKeyProvider): IO[Either[StorageError, Prepared]] =
    provider.load.attempt.flatMap {
      case Left(_)       => IO.pure(Left(StorageError.UnlockFailed))
      case Right(master) =>
        val keys = StoreKeys.from(master)
        IdentityFile
          .load(config.paths.identityFile, keys.identityKey)
          .map(_.map(identity => Prepared(config.paths, keys, identity, isNew = false, config.clock)))
    }

  private def create(prepared: Prepared, transactor: org.typelevel.doobie.util.transactor.Transactor[IO]): IO[Either[StorageError, Store]] =
    Semaphore[IO](1).flatMap { semaphore =>
      val runtime = new StoreRuntime(transactor, semaphore, prepared.keys, prepared.clock, prepared.paths)
      runtime.run(Schema.initialize).map(_.map(_ => new Store(runtime, prepared.isNew, prepared.identity)))
    }

  private def hikari(database: Path): HikariConfig =
    val sqlite     = new SQLiteConfig()
    sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL)
    sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL)
    sqlite.setBusyTimeout(5_000)
    sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
    sqlite.setPragma(SQLiteConfig.Pragma.SECURE_DELETE, "ON")
    val dataSource = new SQLiteDataSource(sqlite)
    dataSource.setUrl(s"jdbc:sqlite:${database.toAbsolutePath}")
    val hikari     = new HikariConfig()
    hikari.setDataSource(dataSource)
    hikari.setMaximumPoolSize(1)
    hikari.setMinimumIdle(1)
    hikari.setAutoCommit(true)
    hikari.setConnectionTimeout(5_000L)
    hikari.setPoolName("yochat-store")
    hikari

  private enum ArtifactState:
    case AllAbsent, CompleteFiles, DatabaseAndIdentity, PartialArtifacts

  private def artifactState(paths: StorePaths): ArtifactState =
    val database = SecureFiles.exists(paths.database)
    val identity = IdentityFile.isPresent(paths.identityFile)
    val keyFile  = KeyFile.isPresent(paths.keyFile)
    (database, identity, keyFile) match
      case (false, false, false) => ArtifactState.AllAbsent
      case (true, true, true)    => ArtifactState.CompleteFiles
      case (true, true, false)   => ArtifactState.DatabaseAndIdentity
      case _                     => ArtifactState.PartialArtifacts
