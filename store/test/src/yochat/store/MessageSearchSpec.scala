package yochat.store

import cats.effect.IO
import org.typelevel.doobie.Fragment

/** S3-C: bounded local search. The scan is capped by rows and reports what it actually read, so a
  * truncated scan cannot be presented as "no results", and a complete scan cannot be presented as truncated.
  */
class MessageSearchSpec extends munit.FunSuite:

  private def seed(store: Store, session: yochat.core.RatchetState, bodies: Vector[String]): IO[Unit] =
    for
      _ <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
      _ <- store.createConversation(StoreTestSupport.conversationId(), session.peerUserPub, 0L)
      _ <- store.insertSession(session, 0L)
      _ <- bodies.zipWithIndex.foldLeft(IO.unit) { case (acc, (text, index)) =>
             val seq = index.toLong + 1L
             acc *> store
               .appendOutbound(
                 session,
                 seq,
                 0L,
                 StoreTestSupport.conversationId(),
                 session.peerUserPub,
                 session.peerDevicePub,
                 seq,
                 StoreTestSupport.body(text),
                 StoreTestSupport.wire(session, seq),
                 MessageState.Received
               )
               .void
           }
    yield ()

  test("search matches case-insensitively, newest first, and reports a complete scan") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _    <- seed(store, session, Vector("Alpha one", "beta two", "ALPHA three"))
          all  <- store.searchMessages("alpha", 20)
          none <- store.searchMessages("gamma", 20)
        yield
          val hits = StoreTestSupport.right(all)
          assertEquals(hits.hits.map(_.seq), List(3L, 1L))
          assertEquals(hits.scanned, 3)
          assert(!hits.truncated)
          assertEquals(StoreTestSupport.right(none).hits, Nil)
          assert(!StoreTestSupport.right(none).truncated)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("a hit limit stops the scan only when rows remain, and says so") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-limit-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    val bodies    = Vector.fill(50)("quota row") ++ Vector.fill(300)("filler") ++ Vector("alpha one", "alpha two")
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _       <- seed(store, session, bodies)
          capped  <- store.searchMessages("alpha", 1)
          exactly <- store.searchMessages("quota", 50)
        yield
          val hit = StoreTestSupport.right(capped)
          assertEquals(hit.hits.size, 1)
          assert(hit.truncated, "rows remain after the hit limit, so the result is incomplete")
          val full = StoreTestSupport.right(exactly)
          assertEquals(full.hits.size, 50)
          assert(!full.truncated, "a scan that reached the end of the table is complete even at the limit")
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("a scan limit with zero hits is reported as truncated, never as a complete empty result") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-scan-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _      <- seed(store, session, Vector("Alpha one", "beta two", "ALPHA three"))
          result <- MessageRepository.searchBounded(store.runtime, "alpha one", 20, 1)
        yield
          val outcome = StoreTestSupport.right(result)
          assertEquals(outcome.hits, Nil)
          assertEquals(outcome.scanned, 1)
          assert(outcome.truncated)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("search rejects empty needles and out-of-range bounds") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-args-")
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          empty  <- store.searchMessages("", 20)
          long   <- store.searchMessages("x" * 201, 20)
          limit  <- store.searchMessages("x", 0)
          high   <- store.searchMessages("x", 51)
          scan   <- MessageRepository.searchBounded(store.runtime, "x", 20, 5001)
        yield
          assertEquals(StoreTestSupport.left(empty), StorageError.InvalidArgument("needle"))
          assertEquals(StoreTestSupport.left(long), StorageError.InvalidArgument("needle"))
          assertEquals(StoreTestSupport.left(limit), StorageError.InvalidArgument("limit"))
          assertEquals(StoreTestSupport.left(high), StorageError.InvalidArgument("limit"))
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
          _      <- seed(store, session, Vector("Alpha one", "beta two", "ALPHA three"))
          before <- store.searchMessages("alpha", 20)
          _      <- store.deleteRelationship(session.peerUserPub, 1L, deleteHistory = true, 0L)
          after  <- store.searchMessages("alpha", 20)
        yield
          assertEquals(StoreTestSupport.right(before).hits.size, 2)
          assertEquals(StoreTestSupport.right(after).hits, Nil)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("both keyset pages search by rowid and never scan the table or sort in a temp b-tree") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-plan-")
    try
      StoreTestSupport.withStore(directory) { (store: Store) =>
        def plan(query: String): List[String] =
          StoreTestSupport.raw(store)(
            (Fragment.const("EXPLAIN QUERY PLAN ") ++ Fragment.const(query))
              .query[(Int, Int, Int, String)]
              .to[List]
          ).map(_._4)
        // The two statements the production path actually issues: the first page is bounded by MAX(id),
        // the follow-up page by the last seen id.
        val first  = plan(
          """SELECT id FROM messages WHERE id <= (SELECT COALESCE(MAX(id), 0) FROM messages)
            |ORDER BY id DESC LIMIT 256""".stripMargin
        )
        val next   = plan("""SELECT id FROM messages WHERE id < 1000 ORDER BY id DESC LIMIT 256""")
        for all <- List(first, next) do
          assert(all.exists(_.contains("SEARCH messages")), all.toString)
          assert(!all.exists(_.contains("SCAN messages")), all.toString)
          assert(!all.exists(_.contains("TEMP B-TREE")), all.toString)
        IO.unit
      }
    finally StoreTestSupport.deleteTree(directory)
  }
