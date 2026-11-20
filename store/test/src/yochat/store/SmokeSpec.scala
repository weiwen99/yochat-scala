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
