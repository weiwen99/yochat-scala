package yochat.store

import cats.effect.IO
import munit.FunSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

class ContactInvitationSpec extends FunSuite:

  test("G11: contact and invitation envelopes bind user/id, so row swaps fail") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-binding-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      val other   = StoreTestSupport.edPublic()
      StoreTestSupport.withStore(directory) { store =>
        val invitationA = ByteVector.view(Array.fill[Byte](32)(0x21))
        val invitationB = ByteVector.view(Array.fill[Byte](32)(0x22))
        for
          _                 <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _                 <- store.insertContact(other, StoreTestSupport.contact(), 0L)
          _                 <- store.insertInvitation(invitationA, invitationData(), 0L)
          _                 <- store.insertInvitation(invitationB, invitationData(), 0L)
          contactB          <- store
                                 .transact(
                                   sql"SELECT sealed FROM contacts WHERE user_pub = ${other.bytes.toArray}".query[Array[Byte]].unique
                                 )
                                 .map(StoreTestSupport.right)
          _                 <- store.transact(
                                 sql"UPDATE contacts SET sealed = $contactB WHERE user_pub = ${session.peerUserPub.bytes.toArray}".update.run
                               )
          contactBad        <- store.loadContact(session.peerUserPub)
          invitationBSealed <- store
                                 .transact(
                                   sql"SELECT sealed FROM invitations WHERE id = ${invitationB.toArray}".query[Array[Byte]].unique
                                 )
                                 .map(StoreTestSupport.right)
          _                 <- store.transact(
                                 sql"UPDATE invitations SET sealed = $invitationBSealed WHERE id = ${invitationA.toArray}".update.run
                               )
          invitationBad     <- store.loadInvitation(invitationA)
          contactPlan       <-
            store.transact(
              sql"EXPLAIN QUERY PLAN SELECT user_pub, sealed, version FROM contacts WHERE user_pub = ${session.peerUserPub.bytes.toArray}"
                .query[(Int, Int, Int, String)]
                .to[List]
            )
          invitationPlan    <- store.transact(
                                 sql"EXPLAIN QUERY PLAN SELECT id, sealed, version FROM invitations WHERE id = ${invitationA.toArray}"
                                   .query[(Int, Int, Int, String)]
                                   .to[List]
                               )
        yield
          assertEquals(StoreTestSupport.left(contactBad), StorageError.InvalidCiphertext)
          assertEquals(StoreTestSupport.left(invitationBad), StorageError.InvalidCiphertext)
          assertPlan(StoreTestSupport.right(contactPlan))
          assertPlan(StoreTestSupport.right(invitationPlan))
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G11: rolling back a row version and deleting then reinserting are detected") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-rollback-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        val invitation = ByteVector.view(Array.fill[Byte](32)(0x31))
        for
          _                     <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _                     <- store.insertInvitation(invitation, invitationData(), 0L)
          positive              <- store.loadInvitation(invitation)
          codec                 <- IO.pure(StoreDataCodec.encodeInvitation(invitationData()).flatMap(StoreDataCodec.decodeInvitation))
          contactV1             <- rawContact(store, session.peerUserPub.bytes)
          _                     <- store.updateContact(session.peerUserPub, StoreTestSupport.contact().copy(alias = Some("Bob 2")), 1L, 0L)
          contactV2             <- rawContact(store, session.peerUserPub.bytes)
          _                     <-
            store.transact(
              sql"UPDATE contacts SET sealed = ${contactV1._1}, version = ${contactV1._2} WHERE user_pub = ${session.peerUserPub.bytes.toArray}".update.run
            )
          rolled                <- store.loadContact(session.peerUserPub)
          _                     <-
            store.transact(
              sql"UPDATE contacts SET sealed = ${contactV2._1}, version = ${contactV2._2} WHERE user_pub = ${session.peerUserPub.bytes.toArray}".update.run
            )
          _                     <- store.deleteContact(session.peerUserPub, 2L, 0L)
          tombstone             <- store.loadContact(session.peerUserPub)
          _                     <- store.transact(
                                     sql"UPDATE contacts SET sealed = ${contactV2._1}, version = ${contactV2._2} WHERE user_pub = ${session.peerUserPub.bytes.toArray}".update.run
                                   )
          resurrected           <- store.loadContact(session.peerUserPub)
          invitationV1          <- rawInvitation(store, invitation)
          _                     <- store.updateInvitation(invitation, invitationData().copy(peerAccount = Some("bob@example.test")), 1L, 0L)
          invitationV2          <- rawInvitation(store, invitation)
          _                     <-
            store.transact(
              sql"UPDATE invitations SET sealed = ${invitationV1._1}, version = ${invitationV1._2} WHERE id = ${invitation.toArray}".update.run
            )
          invitationRolled      <- store.loadInvitation(invitation)
          _                     <-
            store.transact(
              sql"UPDATE invitations SET sealed = ${invitationV2._1}, version = ${invitationV2._2} WHERE id = ${invitation.toArray}".update.run
            )
          _                     <- store.deleteInvitation(invitation, 2L, 0L)
          reinserted            <- store.insertInvitation(invitation, invitationData(), 0L)
          _                     <- store.transact(
                                     sql"""INSERT INTO invitations(id, sealed, version, created_at)
                               |VALUES (${invitation.toArray}, ${invitationV2._1}, ${invitationV2._2}, ${invitationV2._3})""".stripMargin.update.run
                                   )
          invitationResurrected <- store.loadInvitation(invitation)
        yield
          assertEquals(codec.map(_.state), Right(InvitationState.Pending))
          assertEquals(positive.map(_.version), Right(1L))
          assertEquals(StoreTestSupport.left(rolled), StorageError.StaleGeneration)
          assertEquals(StoreTestSupport.left(tombstone), StorageError.NotFound("contact"))
          assertEquals(StoreTestSupport.left(resurrected), StorageError.StaleGeneration)
          assertEquals(StoreTestSupport.left(invitationRolled), StorageError.StaleGeneration)
          assertEquals(StoreTestSupport.left(reinserted), StorageError.WriteConflict)
          assertEquals(StoreTestSupport.left(invitationResurrected), StorageError.StaleGeneration)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G11: contact safety is authoritative, mismatch fails closed, and rekey inherits it") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-safety-")
    try
      val session = StoreTestSupport.state(safetyVerified = false)
      StoreTestSupport.withStore(directory) { store =>
        for
          _             <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _             <- store.insertSession(session, 0L)
          contactOn     <- store.setSafetyVerified(session.peerUserPub, verified = true, expectedVersion = 1L, expectedLockGeneration = 0L)
          sessionOn     <- store.loadSession(session.sessionId)
          unsafeRekey   <- store.insertSession(
                             StoreTestSupport.stateWith(session.peerUserPub, session.peerDevicePub, 2, safetyVerified = false),
                             0L
                           )
          safeRekey     <- store.insertSession(
                             StoreTestSupport.stateWith(session.peerUserPub, session.peerDevicePub, 3, safetyVerified = true),
                             0L
                           )
          contactData    = StoreTestSupport.contact(safetyVerified = false)
          encoded       <- IO.pure(StoreTestSupport.right(StoreDataCodec.encodeContact(contactData)))
          contactSealed <- IO.pure(
                             StoreTestSupport.right(
                               StoreCrypto.seal(
                                 store.runtime.keys.contactKey,
                                 StoreCrypto.randomNonceUnsafe(),
                                 StoreCrypto.contactAad(session.peerUserPub.bytes, 3L),
                                 encoded
                               )
                             )
                           )
          _             <- store.transact(
                             sql"UPDATE contacts SET sealed = $contactSealed, version = 3 WHERE user_pub = ${session.peerUserPub.bytes.toArray}".update.run
                           )
          _             <- store.transact(
                             sql"UPDATE meta SET value = 3 WHERE key = ${Sql.contactVersionKey(session.peerUserPub.bytes)}".update.run
                           )
          mismatch      <- store.loadSession(session.sessionId)
        yield
          assertEquals(StoreTestSupport.right(contactOn).version, 2L)
          assertEquals(StoreTestSupport.right(sessionOn).state.safetyVerified, true)
          assertEquals(StoreTestSupport.left(unsafeRekey), StorageError.InvalidSafetyState)
          assertEquals(StoreTestSupport.right(safeRekey).version, 1L)
          assertEquals(StoreTestSupport.left(mismatch), StorageError.InvalidSafetyState)
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  private def invitationData(): InvitationData =
    InvitationData(
      ephemeralPrivate = Some(StoreTestSupport.xpair().privateKey),
      ratchetPrivate = None,
      peerAccount = Some("bob@example.test"),
      state = InvitationState.Pending,
      confirmationState = ContactConfirmation.Confirmed,
      payload = ByteVector.view("invitation".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
      expiresAt = 1_760_000_000_000L
    )

  private def rawContact(store: Store, userPub: ByteVector): IO[(Array[Byte], Long, Long)] =
    store
      .transact(
        sql"SELECT sealed, version, created_at FROM contacts WHERE user_pub = ${userPub.toArray}"
          .query[(Array[Byte], Long, Long)]
          .unique
      )
      .map(StoreTestSupport.right)

  private def rawInvitation(store: Store, id: ByteVector): IO[(Array[Byte], Long, Long)] =
    store
      .transact(
        sql"SELECT sealed, version, created_at FROM invitations WHERE id = ${id.toArray}"
          .query[(Array[Byte], Long, Long)]
          .unique
      )
      .map(StoreTestSupport.right)

  private def assertPlan(plan: List[(Int, Int, Int, String)]): Unit =
    val details = plan.map(_._4)
    assert(details.nonEmpty)
    assert(details.forall(detail => !detail.toUpperCase.contains("SCAN")), s"query plan scans: $details")
