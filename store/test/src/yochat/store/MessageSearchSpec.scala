package yochat.store

import cats.effect.IO
import org.typelevel.doobie.implicits.*

/** S3-C: bounded local search. The scan is capped by rows and reports what it actually read, so a
  * truncated scan cannot be presented as "no results".
  */
class MessageSearchSpec extends munit.FunSuite:

  private def seed(store: Store, session: yochat.core.RatchetState): IO[Unit] =
    for
      _ <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
      _ <- store.createConversation(StoreTestSupport.conversationId(), session.peerUserPub, 0L)
      _ <- store.insertSession(session, 0L)
      _ <- (1L to 3L).toVector.foldLeft(IO.unit) { (acc, seq) =>
             acc *> store
               .appendOutbound(
                 session,
                 seq,
                 0L,
                 StoreTestSupport.conversationId(),
                 session.peerUserPub,
                 session.peerDevicePub,
                 seq,
                 StoreTestSupport.body(
                   seq match
                     case 1L => "Alpha one"
                     case 2L => "beta two"
                     case _  => "ALPHA three"
                 ),
                 StoreTestSupport.wire(session, seq),
                 MessageState.Received
               )
               .void
           }
    yield ()

  test("search matches case-insensitively, newest first, and respects the result limit") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _     <- seed(store, session)
          all   <- store.searchMessages("alpha", 20, 100)
          one   <- store.searchMessages("alpha", 1, 100)
          none  <- store.searchMessages("gamma", 20, 100)
        yield
          val hits = StoreTestSupport.right(all)
          assertEquals(hits.hits.map(_.seq), List(3L, 1L))
          assertEquals(hits.scanned, 3)
          assert(!hits.truncated)
          val limited = StoreTestSupport.right(one)
          assertEquals(limited.hits.map(_.seq), List(3L))
          assert(limited.truncated)
          assertEquals(StoreTestSupport.right(none).hits, Nil)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("a scan limit stops the scan and marks the result truncated") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-scan-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _      <- seed(store, session)
          result <- store.searchMessages("alpha one", 20, 1)
        yield
          val outcome = StoreTestSupport.right(result)
          assertEquals(outcome.hits, Nil)
          assertEquals(outcome.scanned, 1)
          assert(outcome.truncated, "a bounded scan that did not reach the match must say so")
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("search rejects empty needles and out-of-range bounds") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-args-")
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          empty  <- store.searchMessages("", 20, 100)
          long   <- store.searchMessages("x" * 201, 20, 100)
          limit  <- store.searchMessages("x", 0, 100)
          scan   <- store.searchMessages("x", 20, 0)
        yield
          assertEquals(StoreTestSupport.left(empty), StorageError.InvalidArgument("needle"))
          assertEquals(StoreTestSupport.left(long), StorageError.InvalidArgument("needle"))
          assertEquals(StoreTestSupport.left(limit), StorageError.InvalidArgument("limit"))
          assertEquals(StoreTestSupport.left(scan), StorageError.InvalidArgument("scan_limit"))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("deleting the history also removes the messages from search") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-delete-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _       <- seed(store, session)
          before  <- store.searchMessages("alpha", 20, 100)
          _       <- store.deleteRelationship(session.peerUserPub, 1L, deleteHistory = true, 0L)
          after   <- store.searchMessages("alpha", 20, 100)
        yield
          assertEquals(StoreTestSupport.right(before).hits.size, 2)
          assertEquals(StoreTestSupport.right(after).hits, Nil)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("the keyset pages use the rowid index and never full-scan the table") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-plan-")
    try
      StoreTestSupport.withStore(directory) { (store: Store) =>
        val plan = StoreTestSupport.raw(store)(
          sql"""EXPLAIN QUERY PLAN
               |SELECT id FROM messages WHERE id < 1000 ORDER BY id DESC LIMIT 256""".stripMargin
            .query[(Int, Int, Int, String)]
            .to[List]
        ).map(_._4)
        assert(plan.exists(line => line.contains("SEARCH messages") || line.contains("USING INTEGER PRIMARY KEY")), plan.toString)
        assert(!plan.exists(_.contains("SCAN messages")), plan.toString)
        IO.unit
      }
    finally StoreTestSupport.deleteTree(directory)
  }
