package yochat.store

import java.nio.file.Path
import java.sql.DriverManager

import cats.effect.unsafe.implicits.global
import cats.effect.IO
import scodec.bits.ByteVector

/** Child process for G9. `after` commits through the production store, then halts; `before` halts mid-transaction. */
object CrashHarness:

  def main(args: Array[String]): Unit =
    args.toList match
      case mode :: directory :: wireHex :: Nil => run(mode, Path.of(directory), wireHex)
      case _                                   => Runtime.getRuntime.halt(2)

  private def run(mode: String, directory: Path, wireHex: String): Unit =
    mode match
      case "after"  => afterCommit(directory, wireHex)
      case "before" => beforeCommit(directory)
      case _        => Runtime.getRuntime.halt(2)

  private def afterCommit(directory: Path, wireHex: String): Unit =
    val config       = StoreTestSupport.config(directory)
    val sessionId    = StoreTestSupport.sessionIdOf(1)
    val conversation = StoreTestSupport.conversationId(1)
    val wire         = ByteVector.fromValidHex(wireHex)
    Store
      .open(config)
      .use {
        case Left(error)  => IO.delay { System.err.println(error.code); Runtime.getRuntime.halt(3) }
        case Right(store) =>
          store.loadSession(sessionId).flatMap {
            case Left(error)   => IO.delay { System.err.println(error.code); Runtime.getRuntime.halt(3) }
            case Right(record) =>
              store
                .appendOutbound(
                  record.state,
                  record.version,
                  0L,
                  conversation,
                  record.state.peerUserPub,
                  record.state.peerDevicePub,
                  2L,
                  StoreTestSupport.body("crash-after"),
                  wire,
                  MessageState.Sent
                )
                .flatMap {
                  case Left(error) => IO.delay { System.err.println(error.code); Runtime.getRuntime.halt(3) }
                  case Right(_)    => IO.delay(Runtime.getRuntime.halt(0))
                }
          }
      }
      .unsafeRunSync()

  private def beforeCommit(directory: Path): Unit =
    val paths      = StorePaths.under(directory)
    val session    = StoreTestSupport.sessionIdOf(1)
    val connection = DriverManager.getConnection(s"jdbc:sqlite:${paths.database.toAbsolutePath}")
    try
      connection.setAutoCommit(false)
      val update = connection.prepareStatement("UPDATE sessions SET state = ?, state_version = 99 WHERE id = ?")
      update.setBytes(1, StoreTestSupport.random(32))
      update.setBytes(2, session.bytes.toArray)
      update.executeUpdate()
      update.close()
      val insert = connection.prepareStatement(
        """INSERT INTO messages(conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at)
          |VALUES (?, ?, ?, ?, 2, ?, ?, 1, 1)""".stripMargin
      )
      insert.setBytes(1, StoreTestSupport.conversationId(1).toArray)
      insert.setBytes(2, session.bytes.toArray)
      insert.setBytes(3, StoreTestSupport.random(32))
      insert.setBytes(4, StoreTestSupport.random(32))
      insert.setBytes(5, StoreTestSupport.random(48))
      insert.setBytes(6, StoreTestSupport.random(64))
      insert.executeUpdate()
      insert.close()
      Runtime.getRuntime.halt(0)
    finally connection.close()
