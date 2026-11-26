package yochat.server

import java.nio.file.Files

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scodec.bits.ByteVector

import munit.FunSuite

import yochat.core.{Crypto, Key32}
import yochat.store.{DeviceRecord, DeviceRole, DeviceSyncRecord, KeySource, MasterKeyProvider, MessageState, Store, StoreConfig}
import yochat.transport.{RelayClient, RelayPayload, RelayServer}

final class DeviceSyncServiceSpec extends FunSuite:

  test("primary snapshot reaches a companion store over the real relay") {
    val a1Directory = Files.createTempDirectory("yochat-sync-a1-")
    val a2Directory = Files.createTempDirectory("yochat-sync-a2-")
    val fixture     = ExecutorTestSupport.fixture()
    val syncRoot    = Key32.from(ByteVector.fill(32)(0x71)).fold(error => fail(error.toString), identity)
    val authHash    = ByteVector.fill(32)(0x72)
    try
      Store
        .open(StoreConfig.default(a1Directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))))
        .use {
          case Left(error) => IO.raiseError(new AssertionError(error.code))
          case Right(a1)   =>
            Store
              .openCompanion(
                StoreConfig.default(a2Directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))),
                fixture.identity.user.publicKey
              )
              .use {
                case Left(error) => IO.raiseError(new AssertionError(error.code))
                case Right(a2)   =>
                  val a1Sync = DeviceSyncRecord(
                    peerDevicePub = a2.identity.devicePublic,
                    syncRoot = syncRoot.bytes,
                    lastAckedSyncSeq = 0L,
                    lastBatchSeq = 0L,
                    lastBatchHash = ByteVector.fill(32)(0),
                    version = 0L,
                    updatedAt = 0L
                  )
                  for
                    _        <- a1.insertDeviceSync(a1Sync, 0L)
                    _        <- a1.insertContact(fixture.peerUser, ExecutorTestSupport.contactData(), 0L)
                    _        <- a1.insertSession(fixture.state, 0L)
                    _        <- a1.createConversation(fixture.conversationId, fixture.peerUser, 0L)
                    session  <- a1.loadSession(fixture.sessionId)
                    version  <- session match
                                  case Right(value) => IO.pure(value.version)
                                  case Left(error)  => IO.raiseError(new AssertionError(error.code))
                    appended <- a1.appendOutbound(
                                  fixture.state,
                                  version,
                                  0L,
                                  fixture.conversationId,
                                  fixture.identity.user.publicKey,
                                  fixture.identity.device.publicKey,
                                  1L,
                                  ExecutorTestSupport.body("同步正文"),
                                  ExecutorTestSupport.wire(fixture, 1L, fixture.identity.device.publicKey),
                                  MessageState.Pending
                                )
                    _        <- appended match
                                  case Left(error) => IO.raiseError(new AssertionError(error.code))
                                  case Right(_)    => IO.unit
                    server   <- RelayServer.start(0).allocated
                    (relayServer, releaseServer) = server
                    result   <- RelayClient.connect("127.0.0.1", relayServer.port, a1.identity.devicePublic).use { relayA =>
                                  RelayClient.connect("127.0.0.1", relayServer.port, a2.identity.devicePublic).use { relayB =>
                                    for
                                      _      <- relayA.awaitOnline(5.seconds)
                                      _      <- relayB.awaitOnline(5.seconds)
                                      sender <- DeviceSyncService.create(
                                                  a1,
                                                  relayA,
                                                  a2.identity.devicePublic,
                                                  authHash,
                                                  syncRoot,
                                                  0L,
                                                  0L,
                                                  ByteVector.fill(32)(0),
                                                  yochat.core.s2.JoinManifest.empty
                                                )
                                      receiver = new CompanionSyncService(
                                                   a2,
                                                   relayB,
                                                   a1.identity.devicePublic,
                                                   authHash,
                                                   syncRoot
                                                 )
                                      bytes  <- sender.buildSnapshot
                                      direct <- bytes match
                                                  case Left(error)  => IO.raiseError(new AssertionError(error))
                                                  case Right(value) => receiver.receive(value)
                                      _      <- direct match
                                                  case Left(error) => IO.raiseError(new AssertionError(s"direct: $error"))
                                                  case Right(_)    => IO.unit
                                      fiber  <- receiver.run.start
                                      sent   <- sender.sendSnapshot
                                      _      <- sent match
                                                  case Left(error) => IO.raiseError(new AssertionError(error))
                                                  case Right(_)    => IO.unit
                                      count  <- awaitCount(a2, fixture.conversationId, 1L)
                                      _      <- fiber.cancel
                                    yield count
                                  }
                                }
                    _        <- releaseServer
                  yield assertEquals(result, 1L)
              }
        }
        .unsafeRunSync()
    finally
      ExecutorTestSupport.deleteTree(a1Directory)
      ExecutorTestSupport.deleteTree(a2Directory)
  }


  test("A2 sync request triggers an automatic A1 snapshot") {
    val a1Directory = Files.createTempDirectory("yochat-auto-sync-a1-")
    val a2Directory = Files.createTempDirectory("yochat-auto-sync-a2-")
    val fixture     = ExecutorTestSupport.fixture()
    val syncRoot    = Key32.from(ByteVector.fill(32)(0x73)).fold(error => fail(error.toString), identity)
    val authCode    = ByteVector.fill(48)(0x74)
    val authHash    = Crypto.sha256(authCode).bytes
    try
      Store
        .open(StoreConfig.default(a1Directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))))
        .use {
          case Left(error) => IO.raiseError(new AssertionError(error.code))
          case Right(a1)   =>
            Store
              .openCompanion(
                StoreConfig.default(a2Directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))),
                fixture.identity.user.publicKey
              )
              .use {
                case Left(error) => IO.raiseError(new AssertionError(error.code))
                case Right(a2)   =>
                  val companionPub = a2.identity.devicePublic
                  val record       = DeviceRecord(
                    devicePub = companionPub,
                    userPub = a1.identity.userPublic,
                    role = DeviceRole.Companion,
                    name = "auto",
                    authorization = authCode,
                    version = 0L,
                    createdAt = 0L,
                    revokedAt = None,
                    joinEpoch = 1L,
                    joinManifest = yochat.core.s2.JoinManifest.empty.canonical,
                    joinManifestHash = yochat.core.s2.JoinManifest.empty.hash.bytes,
                    joinCursorSyncSeq = 0L,
                    pairingNonce = None,
                    requestHash = None
                  )
                  val sync         = DeviceSyncRecord(
                    peerDevicePub = companionPub,
                    syncRoot = syncRoot.bytes,
                    lastAckedSyncSeq = 0L,
                    lastBatchSeq = 0L,
                    lastBatchHash = ByteVector.fill(32)(0),
                    version = 0L,
                    updatedAt = 0L
                  )
                  for
                    _        <- a1.insertDeviceSync(sync, 0L)
                    _        <- a1.insertContact(fixture.peerUser, ExecutorTestSupport.contactData(), 0L)
                    _        <- a1.createConversation(fixture.conversationId, fixture.peerUser, 0L)
                    session  <- a1.insertSession(fixture.state, 0L)
                    _        <- session match
                                  case Left(error) => IO.raiseError(new AssertionError(error.code))
                                  case Right(_)    => IO.unit
                    version  <- a1.loadSession(fixture.sessionId).flatMap {
                                  case Left(error)  => IO.raiseError(new AssertionError(error.code))
                                  case Right(value) => IO.pure(value.version)
                                }
                    _        <- a1.appendOutbound(
                                  fixture.state,
                                  version,
                                  0L,
                                  fixture.conversationId,
                                  fixture.identity.user.publicKey,
                                  fixture.identity.device.publicKey,
                                  1L,
                                  ExecutorTestSupport.body("自动同步"),
                                  ExecutorTestSupport.wire(fixture, 1L, fixture.identity.device.publicKey),
                                  MessageState.Pending
                                )
                    _        <- a1.insertDevice(record, 0L)
                    server   <- RelayServer.start(0).allocated
                    (relayServer, releaseServer) = server
                    result   <- RelayClient.connect("127.0.0.1", relayServer.port, a1.identity.devicePublic).use { relayA =>
                                  RelayClient.connect("127.0.0.1", relayServer.port, companionPub).use { relayB =>
                                    for
                                      _        <- relayA.awaitOnline(5.seconds)
                                      _        <- relayB.awaitOnline(5.seconds)
                                      scheduler <- DeviceSyncScheduler.create(a1, relayA)
                                      receiver  = new CompanionSyncService(a2, relayB, a1.identity.devicePublic, authHash, syncRoot)
                                      fiber     <- receiver.run.start
                                      request   <- DeviceSyncProtocol.syncRequest(authHash) match
                                                     case Left(error)  => IO.raiseError(new AssertionError(error))
                                                     case Right(value) => IO.pure(value)
                                      _         <- relayB.send(a1.identity.devicePublic, RelayPayload.DeviceSync(request))
                                      count     <- awaitCount(a2, fixture.conversationId, 1L)
                                      secondPeer = ExecutorTestSupport.edPair().publicKey
                                      _         <- a1.insertContact(secondPeer, ExecutorTestSupport.contactData(), 0L)
                                      _         <- scheduler.notifyChange
                                      contacts  <- awaitContacts(a2, 2L)
                                      checkpoint <- awaitCheckpoint(a1, companionPub, count)
                                      version2  <- a1.loadSession(fixture.sessionId).flatMap {
                                                     case Left(error)  => IO.raiseError(new AssertionError(error.code))
                                                     case Right(value) => IO.pure(value.version)
                                                   }
                                      _         <- a1.appendOutbound(
                                                     fixture.state,
                                                     version2,
                                                     0L,
                                                     fixture.conversationId,
                                                     fixture.identity.user.publicKey,
                                                     fixture.identity.device.publicKey,
                                                     2L,
                                                     ExecutorTestSupport.body("增量正文"),
                                                     ExecutorTestSupport.wire(fixture, 2L, fixture.identity.device.publicKey),
                                                     MessageState.Pending
                                                   )
                                      _         <- scheduler.notifyChange
                                      count2    <- awaitCount(a2, fixture.conversationId, 2L)
                                      checkpoint2 <- awaitCheckpoint(a1, companionPub, 2L)
                                      _         <- scheduler.close
                                      _         <- fiber.cancel
                                    yield (count, contacts, checkpoint, count2, checkpoint2)
                                  }
                                }
                    _        <- releaseServer
                  yield
                    assertEquals(result._1, 1L)
                    assertEquals(result._2, 2L)
                    assert(result._3 >= 1L)
                    assertEquals(result._4, 2L)
                    assert(result._5 >= 2L)
              }
        }
        .unsafeRunSync()
    finally
      ExecutorTestSupport.deleteTree(a1Directory)
      ExecutorTestSupport.deleteTree(a2Directory)
  }

  private def awaitCheckpoint(store: Store, device: yochat.core.Ed25519Public, expected: Long): IO[Long] =
    def loop(remaining: Long): IO[Long] =
      store.loadDeviceSync(device).flatMap {
        case Right(value) if value.lastAckedSyncSeq >= expected => IO.pure(value.lastAckedSyncSeq)
        case Right(_) if remaining <= 0L                        => IO.raiseError(new AssertionError("checkpoint timeout"))
        case Right(_)                                           => IO.sleep(20.millis) *> loop(remaining - 20L)
        case Left(error)                                        => IO.raiseError(new AssertionError(error.code))
      }
    loop(5_000L)

  private def awaitContacts(store: Store, expected: Long): IO[Long] =
    def loop(remaining: Long): IO[Long] =
      store.listContacts.flatMap {
        case Right(values) if values.size >= expected => IO.pure(values.size.toLong)
        case Right(_) if remaining <= 0L              => IO.raiseError(new AssertionError("contact sync timeout"))
        case Right(_)                                => IO.sleep(20.millis) *> loop(remaining - 20L)
        case Left(error)                             => IO.raiseError(new AssertionError(error.code))
      }
    loop(5_000L)

  private def awaitCount(store: Store, conversation: ByteVector, expected: Long): IO[Long] =
    def loop(remaining: Long): IO[Long] =
      store.countCompanionMessages(conversation).flatMap {
        case Right(value) if value >= expected => IO.pure(value)
        case Right(_) if remaining <= 0L      => IO.raiseError(new AssertionError("sync timeout"))
        case Right(_)                          => IO.sleep(20.millis) *> loop(remaining - 20L)
        case Left(error)                       => IO.raiseError(new AssertionError(error.code))
      }
    loop(5_000L)
