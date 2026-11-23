package yochat.store

import munit.FunSuite

class SmokeSpec extends FunSuite:

  test("open, contact, session, message round trip") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-smoke-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        for
          _       <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _       <- store.createConversation(StoreTestSupport.conversationId(), session.peerUserPub, 0L)
          _       <- store.insertSession(session, 0L)
          loaded  <- store.loadSession(session.sessionId)
          message <- store.appendOutbound(
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
          read    <- store.loadMessage(
                       StoreTestSupport.conversationId(),
                       session.sessionId,
                       session.peerUserPub,
                       session.peerDevicePub,
                       1L
                     )
        yield
          assertEquals(loaded.map(_.version), Right(1L))
          assertEquals(message.map(_.message.body), Right(StoreTestSupport.body("hello")))
          assertEquals(read.map(_.body), Right(StoreTestSupport.body("hello")))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("unlock adoption makes existing sessions writable under the new lock generation") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-lock-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        for
          _        <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _        <- store.createConversation(StoreTestSupport.conversationId(), session.peerUserPub, 0L)
          _        <- store.insertSession(session, 0L)
          bumped     <- store.bumpLockGeneration(0L)
          stale      <- store.updateSession(session, 1L, 1L)
          wrongAdopt <- store.adoptLockGeneration(0L)
          adopted    <- store.adoptLockGeneration(1L)
          loaded     <- store.loadSession(session.sessionId)
          oldGen     <- store.updateSession(session, 2L, 0L)
          oldVersion <- store.updateSession(session, 1L, 1L)
          unchanged  <- store.loadSession(session.sessionId)
          updated    <- store.updateSession(session, 2L, 1L)
        yield
          assertEquals(bumped, Right(1L))
          assertEquals(StoreTestSupport.left(stale), StorageError.StaleGeneration)
          assertEquals(StoreTestSupport.left(wrongAdopt), StorageError.StaleGeneration)
          assertEquals(adopted, Right(()))
          assertEquals(loaded.map(record => (record.version, record.generation)), Right((2L, 1L)))
          assertEquals(StoreTestSupport.left(oldGen), StorageError.StaleGeneration)
          assertEquals(StoreTestSupport.left(oldVersion), StorageError.StaleGeneration)
          assertEquals(unchanged.map(record => (record.version, record.generation)), Right((2L, 1L)))
          assertEquals(updated.map(_.generation), Right(1L))
      }
    finally StoreTestSupport.deleteTree(directory)
  }
