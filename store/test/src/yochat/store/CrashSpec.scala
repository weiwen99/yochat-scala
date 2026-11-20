package yochat.store

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import scodec.bits.ByteVector

class CrashSpec extends FunSuite:

  test("G9: halt before commit leaves the old state; halt after commit keeps the exact wire") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-crash-")
    try
      val session      = StoreTestSupport.state(safetyVerified = false)
      val conversation = StoreTestSupport.conversationId(1)
      StoreTestSupport.withStore(directory) { store =>
        for
          _ <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _ <- store.createConversation(conversation, session.peerUserPub, 0L)
          _ <- store.insertSession(session, 0L)
          _ <- store.appendOutbound(
                 session,
                 1L,
                 0L,
                 conversation,
                 session.peerUserPub,
                 session.peerDevicePub,
                 1L,
                 StoreTestSupport.body("baseline"),
                 StoreTestSupport.wire(session, 1L),
                 MessageState.Sent
               )
        yield ()
      }

      runChild(directory, "before", ByteVector.empty)
      StoreTestSupport.withStore(directory) { store =>
        for
          loaded <- store.loadSession(session.sessionId)
          absent <- store.loadMessage(conversation, session.sessionId, session.peerUserPub, session.peerDevicePub, 2L)
        yield
          assertEquals(loaded.map(_.version), Right(2L))
          assertEquals(StoreTestSupport.left(absent), StorageError.NotFound("message"))
      }

      val wire = StoreTestSupport.wire(session, 2L, "crash-after")
      runChild(directory, "after", wire)
      StoreTestSupport.withStore(directory) { store =>
        for
          loaded  <- store.loadSession(session.sessionId)
          message <- store.loadMessage(conversation, session.sessionId, session.peerUserPub, session.peerDevicePub, 2L)
        yield
          assertEquals(loaded.map(_.version), Right(3L))
          assertEquals(message.map(_.body), Right(StoreTestSupport.body("crash-after")))
          assertEquals(message.map(_.wire), Right(wire))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  private def runChild(directory: Path, mode: String, wire: ByteVector): Unit =
    val java     = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val command  = List(
      java,
      "--enable-native-access=ALL-UNNAMED",
      "-cp",
      System.getProperty("java.class.path"),
      "yochat.store.CrashHarness",
      mode,
      directory.toString,
      wire.toHex
    ).asJava
    val process  = new ProcessBuilder(command).redirectErrorStream(true).start()
    val finished = process.waitFor(20L, TimeUnit.SECONDS)
    if !finished then process.destroyForcibly()
    val output   = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    assertEquals(process.exitValue(), 0, s"crash child $mode failed: $output")
