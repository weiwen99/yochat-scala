package yochat.store

import cats.effect.IO
import munit.FunSuite

class InternalReviewStoreSpec extends FunSuite:

  test("M5: delete then reinsert rejects session and contact tombstones through the store API") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-tombstone-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        for
          contactRecord   <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          contactRow      <- IO.fromEither(contactRecord.left.map(error => new AssertionError(error.code)))
          insertedSession <- store.insertSession(session, 0L)
          sessionRecord   <- IO.fromEither(insertedSession.left.map(error => new AssertionError(error.code)))
          retired         <- store.retireSession(
                               session.sessionId,
                               sessionRecord.version,
                               0L,
                               CutoverRole.Initiator,
                               CutoverEvidence(confirmAckReceived = true, firstNewSessionDataReceived = false)
                             )
          _               <- IO.fromEither(retired.left.map(error => new AssertionError(error.code)))
          reinserted      <- store.insertSession(session, 0L)
          deletedContact  <- store.deleteContact(session.peerUserPub, contactRow.version, 0L)
          _               <- IO.fromEither(deletedContact.left.map(error => new AssertionError(error.code)))
          reinsertContact <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
        yield
          assertEquals(StoreTestSupport.left(reinserted), StorageError.StaleGeneration)
          assertEquals(StoreTestSupport.left(reinsertContact), StorageError.StaleGeneration)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("B2: deleting a pending session refuses an Active session row") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-delete-active-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        for
          contact <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _       <- IO.fromEither(contact.left.map(error => new AssertionError(error.code)))
          inserted <- store.insertSession(session, 0L)
          _        <- IO.fromEither(inserted.left.map(error => new AssertionError(error.code)))
          deleted <- store.persistHandshake(
                       HandshakeWrite(
                         contact = None,
                         conversation = None,
                         sessions = Vector.empty,
                         invitation = None,
                         deleteInvitation = None,
                         retire = Vector.empty,
                         deletePendingSessions = Vector(session.sessionId)
                       )
                     )
          loaded  <- store.loadSession(session.sessionId)
        yield
          assertEquals(StoreTestSupport.left(deleted), StorageError.InvalidArgument("session-phase"))
          assertEquals(loaded.map(_.state.sessionId), Right(session.sessionId))
      }
    finally StoreTestSupport.deleteTree(directory)
  }
