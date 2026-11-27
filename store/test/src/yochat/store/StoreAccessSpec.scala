package yochat.store

import java.sql.DriverManager
import java.util.concurrent.{CountDownLatch, TimeUnit}

import cats.effect.IO
import munit.FunSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*

class StoreAccessSpec extends FunSuite:

  test("G15: SQLite PRAGMAs and all growing queries use indexes") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-access-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        for
          _           <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _           <- store.createConversation(StoreTestSupport.conversationId(), session.peerUserPub, 0L)
          _           <- store.insertSession(session, 0L)
          _           <- store.appendOutbound(
                           session,
                           1L,
                           0L,
                           StoreTestSupport.conversationId(),
                           session.peerUserPub,
                           session.peerDevicePub,
                           1L,
                           StoreTestSupport.body("hello"),
                           StoreTestSupport.wire(session, 1L),
                           MessageState.Pending
                         )
          journal     <- store.transact(sql"PRAGMA journal_mode".query[String].unique).map(StoreTestSupport.right)
          synchronous <- store.transact(sql"PRAGMA synchronous".query[Int].unique).map(StoreTestSupport.right)
          secure      <- store.transact(sql"PRAGMA secure_delete".query[Int].unique).map(StoreTestSupport.right)
          sessionPlan <-
            store
              .transact(
                sql"EXPLAIN QUERY PLAN SELECT state, state_version, generation FROM sessions WHERE id = ${session.sessionId.bytes.toArray}"
                  .query[(Int, Int, Int, String)]
                  .to[List]
              )
              .map(StoreTestSupport.right)
          messagePlan <- store
                           .transact(
                             sql"""EXPLAIN QUERY PLAN SELECT body FROM messages
                                |WHERE session_id = ${session.sessionId.bytes.toArray} AND device_pub = ${session.peerDevicePub.bytes.toArray} AND seq = 1""".stripMargin
                               .query[(Int, Int, Int, String)]
                               .to[List]
                           )
                           .map(StoreTestSupport.right)
          listPlan    <- store
                           .transact(
                             sql"""EXPLAIN QUERY PLAN SELECT id FROM messages
                              |WHERE conversation_id = ${StoreTestSupport
                                   .conversationId()
                                   .toArray} AND author_pub = ${session.peerUserPub.bytes.toArray}
                              |ORDER BY seq""".stripMargin
                               .query[(Int, Int, Int, String)]
                               .to[List]
                           )
                           .map(StoreTestSupport.right)
          contactPlan <-
            store
              .transact(
                sql"EXPLAIN QUERY PLAN SELECT user_pub, sealed, version FROM contacts WHERE user_pub = ${session.peerUserPub.bytes.toArray}"
                  .query[(Int, Int, Int, String)]
                  .to[List]
              )
              .map(StoreTestSupport.right)
        yield
          assertEquals(journal, "wal")
          assertEquals(synchronous, 2)
          assertEquals(secure, 1)
          assertPlan(sessionPlan)
          assertPlan(messagePlan)
          assertPlan(listPlan)
          assertPlan(contactPlan)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G15: a database with a newer schema version fails closed") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-schema-")
    try
      StoreTestSupport.withStore(directory)(_ => IO.unit)
      val connection = DriverManager.getConnection(s"jdbc:sqlite:${StorePaths.under(directory).database.toAbsolutePath}")
      try
        val statement = connection.createStatement()
        try statement.executeUpdate("UPDATE meta SET value = " + (Schema.Version + 1L) + " WHERE key = 'schema_version'")
        finally statement.close()
      finally connection.close()
      assertEquals(
        StoreTestSupport.left(
          StoreTestSupport.openResult(StoreConfig.default(directory, KeySource.Existing(MasterKeyProvider.fixed(StoreTestSupport.master))))
        ),
        StorageError.UnsupportedSchema
      )
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G16: Hikari max-one blocks a second writer until commit and rollback releases it") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-writer-")
    try
      StoreTestSupport.withStore(directory) { store =>
        val entered = new CountDownLatch(1)
        val release = new CountDownLatch(1)
        val hold    =
          for
            _ <- sql"UPDATE meta SET value = value WHERE key = 'lock-generation'".update.run
            _ <- FC.delay {
                   entered.countDown()
                   release.await(5L, TimeUnit.SECONDS)
                   ()
                 }
          yield ()
        val write   = sql"UPDATE meta SET value = value WHERE key = 'lock-generation'".update.run
        for
          first  <- store.transact(hold).start
          _      <- IO.blocking(entered.await(5L, TimeUnit.SECONDS)).flatMap { ready =>
                      if ready then IO.unit else IO.raiseError(new AssertionError("first writer did not start"))
                    }
          second <- store.transact(write).start
          _      <- IO.race(second.join, IO.sleep(scala.concurrent.duration.DurationInt(300).millis)).flatMap {
                      case Left(_)  => IO.raiseError(new AssertionError("second writer was not blocked"))
                      case Right(_) => IO.unit
                    }
          _      <- IO.delay(release.countDown())
          _      <- first.joinWithNever.flatMap(result => IO.fromEither(result.left.map(error => new AssertionError(error.code))))
          _      <- second.joinWithNever.flatMap(result => IO.fromEither(result.left.map(error => new AssertionError(error.code))))
          rolled <- store.transact(
                      for
                        _ <- sql"UPDATE meta SET value = value WHERE key = 'lock-generation'".update.run
                        _ <- FC.raiseError[Unit](new IllegalStateException("forced rollback"))
                      yield ()
                    )
          _      <- IO.delay(assert(StoreTestSupport.left(rolled) == StorageError.StorageFailure))
          after  <- store.transact(write)
          _      <- IO.fromEither(after.left.map(error => new AssertionError(error.code)))
        yield ()
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  private def assertPlan(plan: List[(Int, Int, Int, String)]): Unit =
    val details = plan.map(_._4)
    assert(details.nonEmpty, "EXPLAIN QUERY PLAN returned no rows")
    assert(details.forall(detail => !detail.toUpperCase.contains("SCAN")), s"query plan scans: $details")
    assert(details.forall(detail => !detail.toUpperCase.contains("TEMP B-TREE")), s"query plan sorts: $details")
