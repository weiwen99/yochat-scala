package yochat.store

import cats.effect.IO
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

/** S3-A storage: blocked peers, contact tombstones and history deletion. */
class RelationshipStoreSpec extends munit.FunSuite:

  private def pub(marker: Int): yochat.core.Ed25519Public =
    yochat.core.Ed25519Public
      .from(ByteVector.view(Array.fill[Byte](32)(marker.toByte)))
      .fold(error => throw new AssertionError(error.toString), identity)

  test("block persists across reopen, is idempotent, and unblock is idempotent") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-block-")
    val peer      = pub(0x21)
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          first   <- store.blockPeer(peer, 0L)
          again   <- store.blockPeer(peer, 0L)
          blocked <- store.isBlocked(peer)
          listed  <- store.listBlockedPeers
        yield
          assertEquals(first, Right(true))
          assertEquals(again, Right(false))
          assert(blocked)
          assertEquals(listed.map(_.map(_.userPub)), Right(List(peer)))
      }
      StoreTestSupport.withStore(directory) { store =>
        for
          blocked <- store.isBlocked(peer)
          first   <- store.unblockPeer(peer, 0L)
          again   <- store.unblockPeer(peer, 0L)
          after   <- store.isBlocked(peer)
          listed  <- store.listBlockedPeers
        yield
          assert(blocked)
          assertEquals(first, Right(true))
          assertEquals(again, Right(false))
          assert(!after)
          assertEquals(listed, Right(Nil))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("a rolled-back blocked row fails the instance open instead of unblocking") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-block-rollback-")
    val peer      = pub(0x22)
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _       <- store.blockPeer(peer, 0L)
          _       <- store.unblockPeer(peer, 0L)
          _       <- IO.delay(
                       StoreTestSupport.raw(store)(
                         sql"""INSERT INTO blocked_peers(user_pub, version, created_at, updated_at)
                              |VALUES (${peer.bytes.toArray}, 1, 1, 1)""".stripMargin.update.run
                       )
                     )
        yield ()
      }
      assertEquals(
        StoreTestSupport.openResult(StoreTestSupport.config(directory)),
        Left(StorageError.StaleGeneration)
      )
    finally StoreTestSupport.deleteTree(directory)
  }

  test("deleting a contact leaves a tombstone that cannot be reinserted and revives only as unverified") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-tombstone-")
    val session   = StoreTestSupport.state(safetyVerified = true)
    val peer      = session.peerUserPub
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _          <- store.insertContact(peer, StoreTestSupport.contact(safetyVerified = true), 0L)
          _          <- store.deleteContact(peer, 1L, 0L)
          listed     <- store.listContacts
          loaded     <- store.loadContact(peer)
          forSync    <- store.loadContactForSync(peer)
          reinserted <- store.insertContact(peer, StoreTestSupport.contact(), 0L)
          revived    <- IO.delay(
                          StoreTestSupport.raw(store)(
                            ContactRepository.upsertProgram(store.runtime, peer, StoreTestSupport.contact(safetyVerified = false), 0L, 100L)
                          )
                        )
          cleared    <- store
                          .setSafetyVerified(peer, verified = true, expectedVersion = revived.version, expectedLockGeneration = 0L)
                          .map(StoreTestSupport.right)
        yield
          assertEquals(listed, Right(Nil))
          assertEquals(StoreTestSupport.left(loaded), StorageError.NotFound("contact"))
          assertEquals(forSync.map(_.map(_.data.state)), Right(Some(ContactState.Deleted)))
          assertEquals(StoreTestSupport.left(reinserted), StorageError.StaleGeneration)
          assertEquals(revived.data.state, ContactState.Active)
          assert(revived.data.recreated)
          assert(!revived.data.safetyVerified)
          assert(!cleared.data.recreated)
          assert(cleared.data.safetyVerified)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("deleting a relationship keeps history by default and removes pending outbound rows from the send path") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-relationship-keep-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    val peer      = session.peerUserPub
    val conv      = StoreTestSupport.conversationId()
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _        <- store.insertContact(peer, StoreTestSupport.contact(), 0L)
          _        <- store.createConversation(conv, peer, 0L)
          _        <- store.insertSession(session, 0L)
          _        <- store.appendOutbound(
                        session,
                        1L,
                        0L,
                        conv,
                        peer,
                        session.peerDevicePub,
                        1L,
                        StoreTestSupport.body("hello"),
                        StoreTestSupport.wire(session, 1L),
                        MessageState.Pending
                      )
          _        <- store.deleteRelationship(peer, 1L, deleteHistory = false, 0L)
          contacts <- store.listContacts
          messages <- store.listRecentMessages(conv, 10)
          loaded   <- store.loadConversation(conv)
          live     <- store.loadSession(session.sessionId)
        yield
          assertEquals(contacts, Right(Nil))
          assertEquals(messages.map(_.map(_.state)), Right(List(MessageState.Failed)))
          assert(loaded.isRight)
          assert(live.isRight)
      }
      assertEquals(StoreTestSupport.openResult(StoreTestSupport.config(directory)), Right(()))
    finally StoreTestSupport.deleteTree(directory)
  }

  test("deleting a relationship with history removes messages, conversation and sessions") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-relationship-delete-")
    val session   = StoreTestSupport.state(safetyVerified = false)
    val peer      = session.peerUserPub
    val conv      = StoreTestSupport.conversationId()
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          _        <- store.insertContact(peer, StoreTestSupport.contact(), 0L)
          _        <- store.createConversation(conv, peer, 0L)
          _        <- store.insertSession(session, 0L)
          _        <- store.appendOutbound(
                        session,
                        1L,
                        0L,
                        conv,
                        peer,
                        session.peerDevicePub,
                        1L,
                        StoreTestSupport.body("hello"),
                        StoreTestSupport.wire(session, 1L),
                        MessageState.Pending
                      )
          _        <- store.deleteRelationship(peer, 1L, deleteHistory = true, 0L)
          messages <- store.listRecentMessages(conv, 10)
          loaded   <- store.loadConversation(conv)
          sessions <- store.listSessions
        yield
          assertEquals(messages, Right(Nil))
          assertEquals(StoreTestSupport.left(loaded), StorageError.NotFound("conversation"))
          assertEquals(sessions, Right(Nil))
      }
      assertEquals(StoreTestSupport.openResult(StoreTestSupport.config(directory)), Right(()))
    finally StoreTestSupport.deleteTree(directory)
  }

  test("companion upsert reports first insert versus repeat") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-companion-upsert-")
    val conv      = StoreTestSupport.conversationId()
    val key       = yochat.core.s2.LogicalKey(conv, pub(0x31), 1L)
    val record    = CompanionMessageRecord(
      sourceId = 0L,
      conversationId = conv,
      authorPub = pub(0x32),
      devicePub = pub(0x31),
      seq = 1L,
      body = StoreTestSupport.body("once"),
      state = MessageState.Received,
      createdAt = 1L,
      syncSeq = 0L,
      logicalKey = Some(key)
    )
    try
      StoreTestSupport.withStore(directory) { store =>
        for
          first  <- store.upsertCompanionMessage(record)
          second <- store.upsertCompanionMessage(record.copy(createdAt = 2L))
        yield
          assert(first.map(_.inserted).getOrElse(false))
          assert(!second.map(_.inserted).getOrElse(true))
      }
    finally StoreTestSupport.deleteTree(directory)
  }
