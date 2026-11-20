package yochat.store

import java.nio.file.Files

import cats.effect.IO
import munit.FunSuite

class BackupSpec extends FunSuite:

  test("G14: online backup is consistent and carries identity plus key file") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-backup-source-")
    val backup    = StoreTestSupport.temporaryDirectory("yochat-store-backup-target-")
    try
      val session      = StoreTestSupport.state(safetyVerified = false)
      val conversation = StoreTestSupport.conversationId(5)
      val wire         = StoreTestSupport.wire(session, 1L)
      StoreTestSupport.withKeyFileStore(directory) { store =>
        for
          _      <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _      <- store.createConversation(conversation, session.peerUserPub, 0L)
          _      <- store.insertSession(session, 0L)
          _      <- store.appendOutbound(
                      session,
                      1L,
                      0L,
                      conversation,
                      session.peerUserPub,
                      session.peerDevicePub,
                      1L,
                      StoreTestSupport.body("backup"),
                      wire,
                      MessageState.Sent
                    )
          copied <- store.backup(backup)
          _      <- IO.fromEither(copied.left.map(error => new AssertionError(error.code)))
        yield ()
      }
      val backupPaths  = StorePaths.under(backup)
      assert(Files.exists(backupPaths.database))
      assert(Files.exists(backupPaths.identityFile))
      assert(Files.exists(backupPaths.keyFile))
      StoreTestSupport.withKeyFileStore(backup) { restored =>
        for
          sessionResult <- restored.loadSession(session.sessionId)
          messageResult <- restored.loadMessage(conversation, session.sessionId, session.peerUserPub, session.peerDevicePub, 1L)
        yield
          assertEquals(sessionResult.map(_.version), Right(2L))
          assertEquals(messageResult.map(_.body), Right(StoreTestSupport.body("backup")))
          assertEquals(messageResult.map(_.wire), Right(wire))
      }
    finally
      StoreTestSupport.deleteTree(directory)
      StoreTestSupport.deleteTree(backup)
  }
