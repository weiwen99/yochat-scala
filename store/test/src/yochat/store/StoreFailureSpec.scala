package yochat.store

import cats.effect.IO
import munit.FunSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*

class StoreFailureSpec extends FunSuite:

  test("G8: failed message append rolls back state, high-water, and the partial row") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-failure-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        val conversation = StoreTestSupport.conversationId()
        val wire         = StoreTestSupport.wire(session, 1L)
        for
          _         <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _         <- store.createConversation(conversation, session.peerUserPub, 0L)
          _         <- store.insertSession(session, 0L)
          first     <- store.appendOutbound(
                         session,
                         1L,
                         0L,
                         conversation,
                         session.peerUserPub,
                         session.peerDevicePub,
                         1L,
                         StoreTestSupport.body("first"),
                         wire,
                         MessageState.Pending
                       )
          _         <- IO.fromEither(first.left.map(error => new AssertionError(error.code)))
          before    <- store.loadSession(session.sessionId)
          beforeRec <- IO.fromEither(before.left.map(error => new AssertionError(error.code)))
          duplicate <- store.appendOutbound(
                         beforeRec.state.copy(ns = 99L),
                         beforeRec.version,
                         0L,
                         conversation,
                         session.peerUserPub,
                         session.peerDevicePub,
                         1L,
                         StoreTestSupport.body("duplicate"),
                         wire,
                         MessageState.Pending
                       )
          _         <- IO.delay(assertEquals(StoreTestSupport.left(duplicate), StorageError.DuplicateSeq))
          after     <- store.loadSession(session.sessionId)
          afterRec  <- IO.fromEither(after.left.map(error => new AssertionError(error.code)))
          count     <- store.transact(sql"SELECT count(*) FROM messages".query[Long].unique)
          high      <- store.transact(
                         sql"SELECT value FROM meta WHERE key = ${"session-state-version/" + session.sessionId.bytes.toHex}"
                           .query[Long]
                           .unique
                       )
        yield
          assertEquals(afterRec.version, beforeRec.version)
          assertEquals(afterRec.state.ns, beforeRec.state.ns)
          assertEquals(StoreTestSupport.right(count), 1L)
          assertEquals(StoreTestSupport.right(high), beforeRec.version)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G8: corrupt ciphertext is a failure, not an empty read") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-corrupt-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        for
          _       <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _       <- store.insertSession(session, 0L)
          _       <- store.transact(
                       sql"UPDATE sessions SET state = ${Array[Byte](1, 2, 3, 4)} WHERE id = ${session.sessionId.bytes.toArray}".update.run
                     )
          corrupt <- store.loadSession(session.sessionId)
          missing <- store.loadSession(StoreTestSupport.sessionIdOf(99))
        yield
          assertEquals(StoreTestSupport.left(corrupt), StorageError.InvalidEnvelope)
          assertEquals(StoreTestSupport.left(missing), StorageError.NotFound("session"))
      }
    finally StoreTestSupport.deleteTree(directory)
  }
