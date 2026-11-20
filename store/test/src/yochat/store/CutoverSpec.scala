package yochat.store

import cats.effect.IO
import munit.FunSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*

class CutoverSpec extends FunSuite:

  test("G12: initiator needs ACK and zero pending; retired state cannot be resurrected") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-cutover-initiator-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        val conversation = StoreTestSupport.conversationId()
        for
          _           <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _           <- store.createConversation(conversation, session.peerUserPub, 0L)
          _           <- store.insertSession(session, 0L)
          appended    <- store.appendOutbound(
                           session,
                           1L,
                           0L,
                           conversation,
                           session.peerUserPub,
                           session.peerDevicePub,
                           1L,
                           StoreTestSupport.body("pending"),
                           StoreTestSupport.wire(session, 1L),
                           MessageState.Pending
                         )
          result      <- IO.fromEither(appended.left.map(error => new AssertionError(error.code)))
          oldRow      <- rawSession(store, session.sessionId)
          pending     <- store.retireSession(
                           session.sessionId,
                           result.session.version,
                           0L,
                           CutoverRole.Initiator,
                           CutoverEvidence(confirmAckReceived = true, firstNewSessionDataReceived = false)
                         )
          _           <- store.setMessageState(result.message.id, MessageState.Pending, MessageState.Sent, 0L)
          noAck       <- store.retireSession(
                           session.sessionId,
                           result.session.version,
                           0L,
                           CutoverRole.Initiator,
                           CutoverEvidence(confirmAckReceived = false, firstNewSessionDataReceived = false)
                         )
          deleted     <- store.retireSession(
                           session.sessionId,
                           result.session.version,
                           0L,
                           CutoverRole.Initiator,
                           CutoverEvidence(confirmAckReceived = true, firstNewSessionDataReceived = false)
                         )
          _           <- IO.fromEither(deleted.left.map(error => new AssertionError(error.code)))
          gone        <- store.loadSession(session.sessionId)
          history     <- store.loadMessage(conversation, session.sessionId, session.peerUserPub, session.peerDevicePub, 1L)
          _           <- store.transact(
                           sql"""INSERT INTO sessions(id, peer_user_pub, peer_device_pub, state, state_version, generation, updated_at)
                              |VALUES (${session.sessionId.bytes.toArray}, ${oldRow._1}, ${oldRow._2}, ${oldRow._3}, ${oldRow._4}, ${oldRow._5}, ${oldRow._6})""".stripMargin.update.run
                         )
          resurrected <- store.loadSession(session.sessionId)
        yield
          assertEquals(StoreTestSupport.left(pending), StorageError.PendingMessages)
          assertEquals(StoreTestSupport.left(noAck), StorageError.CutoverNotReady)
          assertEquals(StoreTestSupport.left(gone), StorageError.NotFound("session"))
          assertEquals(history.map(_.body), Right(StoreTestSupport.body("pending")))
          assertEquals(StoreTestSupport.left(resurrected), StorageError.StaleGeneration)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G12: responder cannot use local state or zero pending to infer a lost ACK") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-cutover-responder-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        val conversation = StoreTestSupport.conversationId()
        for
          _          <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _          <- store.createConversation(conversation, session.peerUserPub, 0L)
          _          <- store.insertSession(session, 0L)
          appended   <- store.appendOutbound(
                          session,
                          1L,
                          0L,
                          conversation,
                          session.peerUserPub,
                          session.peerDevicePub,
                          1L,
                          StoreTestSupport.body("sent"),
                          StoreTestSupport.wire(session, 1L),
                          MessageState.Pending
                        )
          result     <- IO.fromEither(appended.left.map(error => new AssertionError(error.code)))
          _          <- store.setMessageState(result.message.id, MessageState.Pending, MessageState.Sent, 0L)
          lostAck    <- store.retireSession(
                          session.sessionId,
                          result.session.version,
                          0L,
                          CutoverRole.Responder,
                          CutoverEvidence(confirmAckReceived = false, firstNewSessionDataReceived = false)
                        )
          stillThere <- store.loadSession(session.sessionId)
          confirmed  <- store.retireSession(
                          session.sessionId,
                          result.session.version,
                          0L,
                          CutoverRole.Responder,
                          CutoverEvidence(confirmAckReceived = false, firstNewSessionDataReceived = true)
                        )
          _          <- IO.fromEither(confirmed.left.map(error => new AssertionError(error.code)))
          gone       <- store.loadSession(session.sessionId)
        yield
          assertEquals(StoreTestSupport.left(lostAck), StorageError.CutoverNotReady)
          assertEquals(stillThere.map(_.version), Right(result.session.version))
          assertEquals(StoreTestSupport.left(gone), StorageError.NotFound("session"))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  private def rawSession(store: Store, sessionId: yochat.core.SessionId): IO[(Array[Byte], Array[Byte], Array[Byte], Long, Long, Long)] =
    store
      .transact(
        sql"SELECT peer_user_pub, peer_device_pub, state, state_version, generation, updated_at FROM sessions WHERE id = ${sessionId.bytes.toArray}"
          .query[(Array[Byte], Array[Byte], Array[Byte], Long, Long, Long)]
          .unique
      )
      .map(StoreTestSupport.right)
