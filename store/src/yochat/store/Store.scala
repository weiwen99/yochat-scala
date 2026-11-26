package yochat.store

import java.nio.file.Path
import java.time.Clock

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import com.zaxxer.hikari.HikariConfig
import org.sqlite.{SQLiteConfig, SQLiteDataSource}
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.hikari.HikariTransactor
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.ConnectionIO
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, RatchetState, SessionId}
import yochat.core.s2.{JoinManifest, ManifestKey}

enum RebindOutcome:
  case Migrated, Skipped

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

  def adoptLockGeneration(expectedLockGeneration: Long): IO[Either[StorageError, Unit]] =
    SessionRepository.adoptLockGeneration(runtime, expectedLockGeneration)

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
    messageState: MessageState,
    attempts: Int = 0
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
      messageState,
      attempts
    )

  def appendInboundAndRetire(
    state: RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector,
    retired: Vector[(SessionId, CutoverRole, CutoverEvidence)]
  ): IO[Either[StorageError, AppendResult]] =
    MessageRepository.appendInboundAndRetire(
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
      retired
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

  def setAttemptsByKey(
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    attempts: Int,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    MessageRepository.setAttemptsByKey(
      runtime,
      conversationId,
      sessionId,
      authorPub,
      devicePub,
      seq,
      attempts,
      expectedLockGeneration
    )

  def rebindOutbound(
    state: RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    oldSessionId: SessionId,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    oldSeq: Long,
    seq: Long,
    body: ByteVector,
    wire: ByteVector
  ): IO[Either[StorageError, RebindOutcome]] =
    MessageRepository.rebindOutbound(
      runtime,
      state,
      expectedVersion,
      expectedLockGeneration,
      oldSessionId,
      conversationId,
      authorPub,
      devicePub,
      oldSeq,
      seq,
      body,
      wire
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

  def insertDevice(
    record: DeviceRecord,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceRecord]] =
    DeviceRepository.insert(runtime, record, expectedLockGeneration)

  def loadDevice(devicePub: Ed25519Public): IO[Either[StorageError, DeviceRecord]] =
    DeviceRepository.load(runtime, devicePub)

  def listDevices: IO[Either[StorageError, List[DeviceRecord]]] =
    DeviceRepository.list(runtime)

  def revokeDevice(
    devicePub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceRecord]] =
    DeviceRepository.revoke(runtime, devicePub, expectedVersion, expectedLockGeneration)

  def insertDeviceSync(
    record: DeviceSyncRecord,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceSyncRecord]] =
    DeviceSyncRepository.insert(runtime, record, expectedLockGeneration)

  def loadDeviceSync(peerDevicePub: Ed25519Public): IO[Either[StorageError, DeviceSyncRecord]] =
    DeviceSyncRepository.load(runtime, peerDevicePub)

  def updateDeviceSync(
    record: DeviceSyncRecord,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeviceSyncRecord]] =
    DeviceSyncRepository.update(runtime, record, expectedVersion, expectedLockGeneration)

  def deleteDeviceSync(
    peerDevicePub: Ed25519Public,
    expectedVersion: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    DeviceSyncRepository.delete(runtime, peerDevicePub, expectedVersion, expectedLockGeneration)

  def upsertCompanionMessage(
    record: CompanionMessageRecord
  ): IO[Either[StorageError, CompanionMessageRecord]] =
    CompanionMessageRepository.upsert(runtime, record)

  def loadCompanionMessage(sourceId: Long): IO[Either[StorageError, CompanionMessageRecord]] =
    CompanionMessageRepository.load(runtime, sourceId)

  def listCompanionMessages(
    conversationId: ByteVector,
    limit: Int
  ): IO[Either[StorageError, List[CompanionMessageRecord]]] =
    CompanionMessageRepository.list(runtime, conversationId, limit)

  def countCompanionMessages(conversationId: ByteVector): IO[Either[StorageError, Long]] =
    CompanionMessageRepository.count(runtime, conversationId)

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

  def advanceLockGeneration(target: Long): IO[Either[StorageError, Long]] =
    MetaRepository.advanceLockGeneration(runtime, target)

  def persistHandshake(write: HandshakeWrite): IO[Either[StorageError, Unit]] =
    for
      lock   <- lockGeneration
      now    <- runtime.now
      result <- lock match
                  case Left(error)  => IO.pure(Left(error))
                  case Right(value) => runtime.transact(HandshakeRepository.persist(runtime, write, value, now))
    yield result

  def listContacts: IO[Either[StorageError, List[ContactRecord]]] =
    ContactRepository.list(runtime)

  def listInvitations: IO[Either[StorageError, List[InvitationRecord]]] =
    InvitationRepository.list(runtime)

  def listSessions: IO[Either[StorageError, List[SessionRecord]]] =
    SessionRepository.list(runtime)

  def listConversations: IO[Either[StorageError, List[ConversationRecord]]] =
    ConversationRepository.list(runtime)

  def listRecentMessages(conversationId: ByteVector, limit: Int): IO[Either[StorageError, List[MessageRecord]]] =
    MessageRepository.listRecent(runtime, conversationId, limit)

  def listAllMessages(conversationId: ByteVector): IO[Either[StorageError, List[MessageRecord]]] =
    MessageRepository.listAll(runtime, conversationId, 100_000)

  def upsertPeerDeviceList(
    list: yochat.core.s2.PeerDeviceList,
    receivedAt: Long
  ): IO[Either[StorageError, yochat.core.s2.PeerDeviceList]] =
    PeerDeviceListRepository.upsert(runtime, list, receivedAt)

  def loadPeerDeviceList(userPub: Ed25519Public): IO[Either[StorageError, yochat.core.s2.PeerDeviceList]] =
    PeerDeviceListRepository.load(runtime, userPub)

  def nextPeerDeviceListVersion: IO[Either[StorageError, Long]] =
    runtime.run {
      Sql.meta(Store.PeerDeviceListVersionKey).flatMap {
        case Some(value) if value == Long.MaxValue => Sql.abort(StorageError.InvalidStateVersion)
        case Some(value)                           =>
          val next = value + 1L
          Sql.writeMeta(Store.PeerDeviceListVersionKey, next).flatMap(_ => FC.pure(next))
        case None                                  =>
          Sql.writeMeta(Store.PeerDeviceListVersionKey, 1L).flatMap(_ => FC.pure(1L))
      }
    }

  def maxMessageId: IO[Either[StorageError, Long]] =
    runtime.run(sql"SELECT COALESCE(MAX(id), 0) FROM messages".query[Long].unique)

  def maxMessageSyncSeq: IO[Either[StorageError, Long]] =
    runtime.run(sql"SELECT COALESCE(MAX(sync_seq), 0) FROM messages".query[Long].unique)

  def joinManifest: IO[Either[StorageError, JoinManifest]] =
    runtime
      .run(
        sql"""SELECT conversation_id, author_pub, device_pub, MAX(seq)
             |FROM messages GROUP BY conversation_id, author_pub, device_pub""".stripMargin
          .query[(Array[Byte], Array[Byte], Array[Byte], Long)]
          .to[List]
      )
      .map {
        case Left(error) => Left(error)
        case Right(rows) =>
          rows
            .foldLeft[Either[StorageError, Map[ManifestKey, Long]]](Right(Map.empty)) {
              case (Left(error), _)                                  => Left(error)
              case (Right(acc), (conversation, author, device, seq)) =>
                (
                  ByteVector.view(conversation),
                  Ed25519Public.from(ByteVector.view(author)),
                  Ed25519Public.from(ByteVector.view(device))
                ) match
                  case (conv, Right(authorPub), Right(devicePub)) if conv.size == 32L =>
                    Right(acc.updated(ManifestKey(conv, authorPub, devicePub), seq))
                  case _                                                              => Left(StorageError.InvalidEnvelope)
            }
            .flatMap(entries => JoinManifest.create(entries).left.map(_ => StorageError.InvalidEnvelope))
      }

  def messageSyncSeqs(after: Long): IO[Either[StorageError, Map[Long, Long]]] =
    runtime
      .run(
        sql"SELECT id, sync_seq FROM messages WHERE sync_seq > $after ORDER BY sync_seq"
          .query[(Long, Long)]
          .to[List]
      )
      .map(_.map(_.toMap))

  def snapshot: IO[Either[StorageError, StoreSnapshot]] =
    runtime.transact(SnapshotRepository.load(runtime))

  def backup(target: Path): IO[Either[StorageError, Unit]] =
    Backup.create(runtime, target)

  private[store] def transact[A](program: ConnectionIO[A]): IO[Either[StorageError, A]] =
    runtime.transact(program)

object Store:
  private[store] val PeerDeviceListVersionKey: String = "peer-device-list-version"

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

  /** Open a companion database created for an already-known user public key. It never creates or loads a user private key. */
  def openCompanion(config: StoreConfig, userPublic: Ed25519Public): Resource[IO, Either[StorageError, Store]] =
    Resource.eval(prepareCompanion(config, userPublic)).flatMap {
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

  private def prepareCompanion(config: StoreConfig, userPublic: Ed25519Public): IO[Either[StorageError, Prepared]] =
    IO.blocking(artifactState(config.paths)).flatMap {
      case AllAbsent           =>
        config.keySource match
          case KeySource.KeyFile(passphrase) =>
            for
              _        <- SecureFiles.ensureDirectory(config.paths.directory)
              master   <- KeyFile.create(config.paths.keyFile, passphrase)
              prepared <- master match
                            case Left(error) => IO.pure(Left(error))
                            case Right(key)  => createCompanionInstance(config, key, userPublic)
            yield prepared
          case KeySource.New(provider)       =>
            for
              _        <- SecureFiles.ensureDirectory(config.paths.directory)
              loaded   <- provider.load.attempt
              prepared <- loaded match
                            case Left(_)    => IO.pure(Left(StorageError.UnlockFailed))
                            case Right(key) => createCompanionInstance(config, key, userPublic)
            yield prepared
          case KeySource.Existing(_)         => IO.pure(Left(StorageError.InstancePartiallyPresent))
      case CompleteFiles       =>
        config.keySource match
          case KeySource.KeyFile(passphrase) => unlockCompanion(config, passphrase, userPublic)
          case KeySource.Existing(provider)  => loadCompanion(config, provider, userPublic)
          case KeySource.New(_)              => IO.pure(Left(StorageError.InstancePartiallyPresent))
      case DatabaseAndIdentity =>
        config.keySource match
          case KeySource.Existing(provider) => loadCompanion(config, provider, userPublic)
          case KeySource.KeyFile(_)         => IO.pure(Left(StorageError.InstancePartiallyPresent))
          case KeySource.New(_)             => IO.pure(Left(StorageError.InstancePartiallyPresent))
      case PartialArtifacts    => IO.pure(Left(StorageError.InstancePartiallyPresent))
    }

  private def createCompanionInstance(
    config: StoreConfig,
    master: MasterKey,
    userPublic: Ed25519Public
  ): IO[Either[StorageError, Prepared]] =
    val keys = StoreKeys.from(master)
    for
      identity <- IdentityFile.generateCompanion(userPublic)
      created  <- IdentityFile.create(config.paths.identityFile, identity, keys.identityKey)
    yield created.map(_ => Prepared(config.paths, keys, identity, isNew = true, config.clock))

  private def unlockCompanion(
    config: StoreConfig,
    passphrase: Array[Char],
    userPublic: Ed25519Public
  ): IO[Either[StorageError, Prepared]] =
    KeyFile.unlock(config.paths.keyFile, passphrase).flatMap {
      case Left(error)   => IO.pure(Left(error))
      case Right(master) => loadCompanionIdentity(config, master, userPublic)
    }

  private def loadCompanion(
    config: StoreConfig,
    provider: MasterKeyProvider,
    userPublic: Ed25519Public
  ): IO[Either[StorageError, Prepared]] =
    provider.load.attempt.flatMap {
      case Left(_)       => IO.pure(Left(StorageError.UnlockFailed))
      case Right(master) => loadCompanionIdentity(config, master, userPublic)
    }

  private def loadCompanionIdentity(
    config: StoreConfig,
    master: MasterKey,
    userPublic: Ed25519Public
  ): IO[Either[StorageError, Prepared]] =
    val keys = StoreKeys.from(master)
    IdentityFile
      .load(config.paths.identityFile, keys.identityKey)
      .map(_.flatMap { identity =>
        if identity.role == IdentityRole.Companion && identity.userPublic == userPublic && !identity.hasUserPrivate then
          Right(Prepared(config.paths, keys, identity, isNew = false, config.clock))
        else Left(StorageError.InvalidEnvelope)
      })

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
