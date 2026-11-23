package yochat.store

import munit.FunSuite
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

class HandshakeStoreSpec extends FunSuite:

  test("a handshake writes contact, conversation, session, and invitation in one transaction") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-handshake-")
    try
      val session    = StoreTestSupport.stateWith(StoreTestSupport.edPublic(), StoreTestSupport.edPublic(), 21, safetyVerified = false)
      val invitation = ByteVector.view(Array.fill[Byte](32)(0x71))
      val data       = invitationData(InvitationState.PendingReply)
      val write      = HandshakeWrite(
        contact = Some(session.peerUserPub -> StoreTestSupport.contact()),
        conversation = Some(StoreTestSupport.conversationId(21) -> session.peerUserPub),
        sessions = Vector(session),
        invitation = Some(invitation -> data),
        deleteInvitation = None,
        retire = Vector.empty,
        deletePendingSessions = Vector.empty
      )
      StoreTestSupport.withStore(directory) { store =>
        for
          result      <- store.persistHandshake(write)
          contact     <- store.loadContact(session.peerUserPub)
          conversation <- store.loadConversation(StoreTestSupport.conversationId(21))
          stored      <- store.loadSession(session.sessionId)
          storedInv   <- store.loadInvitation(invitation)
        yield
          assert(result.isRight, result.toString)
          assertEquals(contact.map(_.data.safetyVerified), Right(false))
          assertEquals(conversation.map(_.peerUserPub), Right(session.peerUserPub))
          assertEquals(stored.map(_.state.sessionId), Right(session.sessionId))
          assertEquals(storedInv.map(_.data.state), Right(InvitationState.PendingReply))
          assertEquals(storedInv.map(_.data.replyText), Right(Some("retry me")))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("a handshake that fails while retiring rolls back every row") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-handshake-rollback-")
    try
      val session    = StoreTestSupport.stateWith(StoreTestSupport.edPublic(), StoreTestSupport.edPublic(), 22, safetyVerified = false)
      val invitation = ByteVector.view(Array.fill[Byte](32)(0x72))
      val write      = HandshakeWrite(
        contact = Some(session.peerUserPub -> StoreTestSupport.contact()),
        conversation = Some(StoreTestSupport.conversationId(22) -> session.peerUserPub),
        sessions = Vector(session),
        invitation = Some(invitation -> invitationData(InvitationState.Pending)),
        deleteInvitation = None,
        retire = Vector(
          (session.sessionId, CutoverRole.Initiator, CutoverEvidence(confirmAckReceived = false, firstNewSessionDataReceived = false))
        ),
        deletePendingSessions = Vector.empty
      )
      StoreTestSupport.withStore(directory) { store =>
        for
          result      <- store.persistHandshake(write)
          contact     <- store.loadContact(session.peerUserPub)
          conversation <- store.loadConversation(StoreTestSupport.conversationId(22))
          stored      <- store.loadSession(session.sessionId)
          storedInv   <- store.loadInvitation(invitation)
        yield
          assertEquals(StoreTestSupport.left(result), StorageError.CutoverNotReady)
          assert(StoreTestSupport.left(contact).isInstanceOf[StorageError.NotFound])
          assert(StoreTestSupport.left(conversation).isInstanceOf[StorageError.NotFound])
          assert(StoreTestSupport.left(stored).isInstanceOf[StorageError.NotFound])
          assert(StoreTestSupport.left(storedInv).isInstanceOf[StorageError.NotFound])
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("outbound attempts survive append, update, and snapshot projection") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-attempts-")
    try
      val session = StoreTestSupport.stateWith(StoreTestSupport.edPublic(), StoreTestSupport.edPublic(), 23, safetyVerified = false)
      val conversation = StoreTestSupport.conversationId(23)
      StoreTestSupport.withStore(directory) { store =>
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
                          StoreTestSupport.body("hello"),
                          StoreTestSupport.wire(session, 1L),
                          MessageState.Pending,
                          attempts = 2
                        )
          _          <- store.setAttemptsByKey(
                          conversation,
                          session.sessionId,
                          session.peerUserPub,
                          session.peerDevicePub,
                          1L,
                          3,
                          0L
                        )
          loaded     <- store.loadMessage(conversation, session.sessionId, session.peerUserPub, session.peerDevicePub, 1L)
          snapshot   <- store.snapshot
        yield
          assertEquals(appended.map(_.message.attempts), Right(2))
          assertEquals(loaded.map(_.attempts), Right(3))
          assertEquals(
            snapshot.map(_.messages.get(conversation).toList.flatten.map(_.attempts)),
            Right(List(3))
          )
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("first-data append records a binding marker; deleting it exposes legacy data") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-binding-")
    try
      val session      = StoreTestSupport.stateWith(StoreTestSupport.edPublic(), StoreTestSupport.edPublic(), 24, safetyVerified = false)
      val conversation = StoreTestSupport.conversationId(24)
      StoreTestSupport.withStore(directory) { store =>
        for
          _        <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _        <- store.createConversation(conversation, session.peerUserPub, 0L)
          _        <- store.insertSession(session, 0L)
          _        <- store.appendOutbound(
                        session,
                        1L,
                        0L,
                        conversation,
                        session.peerUserPub,
                        session.peerDevicePub,
                        1L,
                        StoreTestSupport.body("hello"),
                        StoreTestSupport.wire(session, 1L),
                        MessageState.Pending
                      )
          marked   <- store.snapshot
          _         = StoreTestSupport.raw(store)(sql"DELETE FROM meta WHERE key LIKE 'binding/%'".update.run)
          legacy   <- store.snapshot
        yield
          assertEquals(marked.map(_.bindings), Right(Set(conversation)))
          assertEquals(legacy.map(_.bindings), Right(Set.empty[ByteVector]))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  private def invitationData(state: InvitationState): InvitationData =
    InvitationData(
      ephemeralPrivate = Some(StoreTestSupport.xpair().privateKey),
      ratchetPrivate = None,
      peerAccount = Some("bob@example.test"),
      state = state,
      confirmationState = ContactConfirmation.Confirmed,
      payload = ByteVector.view("invitation".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
      expiresAt = 1_760_000_000_000L,
      replyText = if state == InvitationState.PendingReply then Some("retry me") else None,
      replyAttempts = if state == InvitationState.PendingReply then 1 else 0
    )
