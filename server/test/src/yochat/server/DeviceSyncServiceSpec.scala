package yochat.server

import java.nio.file.Files

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scodec.bits.ByteVector

import munit.FunSuite

import yochat.core.Key32
import yochat.store.{KeySource, MasterKeyProvider, MessageState, Store, StoreConfig}
import yochat.transport.{RelayClient, RelayServer}

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
                  for
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
                                                  0L
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

  private def awaitCount(store: Store, conversation: ByteVector, expected: Long): IO[Long] =
    def loop(remaining: Long): IO[Long] =
      store.countCompanionMessages(conversation).flatMap {
        case Right(value) if value >= expected => IO.pure(value)
        case Right(_) if remaining <= 0L      => IO.raiseError(new AssertionError("sync timeout"))
        case Right(_)                          => IO.sleep(20.millis) *> loop(remaining - 20L)
        case Left(error)                       => IO.raiseError(new AssertionError(error.code))
      }
    loop(5_000L)
