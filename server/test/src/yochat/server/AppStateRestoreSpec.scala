package yochat.server

import java.nio.file.Files
import java.sql.DriverManager

import scala.util.Using

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import yochat.store.{KeySource, MasterKeyProvider, MessageState, Store, StoreConfig, StorePaths}

class AppStateRestoreSpec extends FunSuite:

  test("unmarked first-data history stays readable but cannot resume Data") {
    val directory = Files.createTempDirectory("yochat-restore-legacy-")
    val master    = ExecutorTestSupport.master
    val fixture   = ExecutorTestSupport.fixture()
    try
      val parsedWire = yochat.core.WireFrame
        .parse(ExecutorTestSupport.wire(fixture, 1L))
        .fold(error => throw new AssertionError(error.toString), identity)
      val localWire  = parsedWire.copy(header = parsedWire.header.copy(devicePub = fixture.identity.device.publicKey)).bytes
      Store
        .open(StoreConfig.default(directory, KeySource.New(MasterKeyProvider.fixed(master))))
        .use {
          case Left(error)  => IO.raiseError(new AssertionError(s"store open failed: ${error.code}"))
          case Right(store) =>
            for
              _ <- store.insertContact(fixture.peerUser, ExecutorTestSupport.contactData(), 0L)
              _ <- store.createConversation(fixture.conversationId, fixture.peerUser, 0L)
              _ <- store.insertSession(fixture.state, 0L)
              appended <- store.appendOutbound(
                            fixture.state,
                            1L,
                            0L,
                            fixture.conversationId,
                            fixture.identity.user.publicKey,
                            fixture.identity.device.publicKey,
                            1L,
                            ExecutorTestSupport.body("legacy"),
                            localWire,
                            MessageState.Pending
                          )
              _ <- appended match
                     case Left(error) => IO.raiseError(new AssertionError(error.code))
                     case Right(_)    => IO.unit
            yield ()
        }
        .unsafeRunSync()

      Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${StorePaths.under(directory).database}")) { connection =>
        connection.createStatement().executeUpdate("DELETE FROM meta WHERE key LIKE 'binding/%'")
      }

      val restored = Store
        .open(StoreConfig.default(directory, KeySource.Existing(MasterKeyProvider.fixed(master))))
        .use {
          case Left(error)  => IO.raiseError(new AssertionError(s"store open failed: ${error.code}"))
          case Right(store) =>
            for
              snapshot <- store.snapshot
              state    <- AppStateRestore.load(store)
            yield (snapshot, state)
        }
        .unsafeRunSync()
      val (snapshotResult, restoredResult) = restored
      val stored = snapshotResult.fold(error => fail(error.code), identity)
      assert(stored.bindings.isEmpty)
      assert(stored.messages.values.exists(_.nonEmpty), stored.messages.toString)
      assert(stored.sessions.nonEmpty, stored.sessions.toString)
      val state    = restoredResult.fold(error => fail(error.code), identity)
      assert(state.contacts.nonEmpty)
      assert(state.sessions.nonEmpty)
      assert(state.conversations.nonEmpty)
      assert(!state.sessions.values.head.bindingEstablished)
      assert(!yochat.core.app.Application.canSend(state, state.sessions.values.head.conversationId))
    finally ExecutorTestSupport.deleteTree(directory)
  }
