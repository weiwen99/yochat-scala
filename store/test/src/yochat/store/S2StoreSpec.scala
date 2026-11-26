package yochat.store

import java.sql.DriverManager

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scodec.bits.ByteVector

import munit.FunSuite

final class S2StoreSpec extends FunSuite:

  private def ok[A](value: IO[Either[StorageError, A]]): A =
    StoreTestSupport.right(value.unsafeRunSync())

  private def bad[A](value: IO[Either[StorageError, A]]): StorageError =
    StoreTestSupport.left(value.unsafeRunSync())

  test("device record round-trips, seals its name and cannot reinsert after revoke") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2-device-")
    try
      StoreTestSupport.withStore(directory) { store =>
        val lock   = ok(store.lockGeneration)
        val user   = StoreTestSupport.edPublic()
        val device = StoreTestSupport.edPublic()
        val record = DeviceRecord(
          devicePub = device,
          userPub = user,
          role = DeviceRole.Companion,
          name = "书房里的只读设备",
          authorization = ByteVector.fill(64)(0x11.toByte),
          version = 0L,
          createdAt = 0L,
          revokedAt = None,
          joinEpoch = 7L,
          joinManifest = ByteVector.fill(32)(0x22.toByte),
          joinManifestHash = ByteVector.fill(32)(0x33.toByte),
          joinCursorSyncSeq = 0L,
          pairingNonce = Some(ByteVector.fill(16)(0x44.toByte)),
          requestHash = Some(ByteVector.fill(32)(0x55.toByte))
        )
        val inserted = ok(store.insertDevice(record, lock))
        assertEquals(inserted.version, 1L)
        val loaded = ok(store.loadDevice(device))
        assertEquals(loaded.name, "书房里的只读设备")
        assertEquals(loaded.joinManifest, record.joinManifest)
        assertEquals(loaded.role, DeviceRole.Companion)
        val revoked = ok(store.revokeDevice(device, 1L, lock))
        assertEquals(revoked.version, 2L)
        assert(revoked.isRevoked)
        assertEquals(bad(store.insertDevice(record, lock)), StorageError.StaleGeneration)
        IO.unit
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("peer device list round-trips, seals names and rejects revocation rollback") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2-peer-list-")
    try
      StoreTestSupport.withStore(directory) { store =>
        val user      = StoreTestSupport.edPair()
        val primary   = StoreTestSupport.edPair()
        val companion = StoreTestSupport.edPair()
        val first     = yochat.core.s2.PeerDeviceList
          .create(
            user.privateKey,
            user.publicKey,
            1L,
            1_000L,
            Vector(
              yochat.core.s2.PeerDeviceEntry(primary.publicKey, yochat.core.s2.PeerDeviceRole.Primary, "laptop", 1_000L, None),
              yochat.core.s2.PeerDeviceEntry(companion.publicKey, yochat.core.s2.PeerDeviceRole.Companion, "phone", 1_100L, None)
            )
          )
          .fold(error => fail(error.toString), identity)
        ok(store.upsertPeerDeviceList(first, 1_100L))
        assertEquals(ok(store.loadPeerDeviceList(user.publicKey)), first)

        val revoked = yochat.core.s2.PeerDeviceList
          .create(
            user.privateKey,
            user.publicKey,
            2L,
            2_000L,
            first.entries.map(entry => if entry.devicePub == companion.publicKey then entry.copy(revokedAt = Some(2_000L)) else entry)
          )
          .fold(error => fail(error.toString), identity)
        ok(store.upsertPeerDeviceList(revoked, 2_100L))
        assertEquals(ok(store.loadPeerDeviceList(user.publicKey)).revoked.keySet, Set(companion.publicKey))

        val rollback = yochat.core.s2.PeerDeviceList
          .create(user.privateKey, user.publicKey, 3L, 3_000L, first.entries)
          .fold(error => fail(error.toString), identity)
        IO(assertEquals(bad(store.upsertPeerDeviceList(rollback, 3_100L)), StorageError.InvalidArgument("revocation-rollback")))
      }
      val database = StorePaths.under(directory).database
      val check    = DriverManager.getConnection(s"jdbc:sqlite:${database.toAbsolutePath}")
      try
        val statement = check.createStatement()
        try
          val result = statement.executeQuery("SELECT payload FROM peer_device_lists")
          assert(result.next())
          val payload = new String(result.getBytes(1), java.nio.charset.StandardCharsets.UTF_8)
          assert(!payload.contains("phone"))
          assert(!payload.contains("laptop"))
        finally statement.close()
      finally check.close()
    finally StoreTestSupport.deleteTree(directory)
  }

  test("B2 logical key dedupes A1 sync with direct fanout and receipts are per recipient") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2-dedupe-")
    try
      StoreTestSupport.withStore(directory) { store =>
        val conversation = StoreTestSupport.conversationId(13)
        val author       = StoreTestSupport.edPair()
        val recipientA   = StoreTestSupport.edPair()
        val recipientB   = StoreTestSupport.edPair()
        val key          = yochat.core.s2.LogicalKey(conversation, author.publicKey, 1L)
        val first        = CompanionMessageRecord(
          sourceId = 7L,
          conversationId = conversation,
          authorPub = StoreTestSupport.edPublic(),
          devicePub = author.publicKey,
          seq = 1L,
          body = StoreTestSupport.body("once"),
          state = MessageState.Received,
          createdAt = 100L,
          syncSeq = 1L,
          logicalKey = Some(key)
        )
        val direct       = first.copy(sourceId = 0L, body = StoreTestSupport.body("once"), syncSeq = 0L, createdAt = 200L)
        ok(store.upsertCompanionMessage(first))
        val deduped = ok(store.upsertCompanionMessage(direct))
        assertEquals(deduped.sourceId, 7L)
        assertEquals(ok(store.countCompanionMessages(conversation)), 1L)
        assertEquals(ok(store.loadCompanionMessage(7L)).logicalKey, Some(key))

        val stamp = yochat.core.s2.OrderStamp(3L, Vector(yochat.core.s2.OrderDependency(author.publicKey, 1L)))
        ok(store.upsertMessageOrder(key, stamp, 200L, 0L))
        assertEquals(ok(store.listMessageOrders(conversation)).map(_._2), List(stamp))

        val lock = ok(store.lockGeneration)
        val receipt = DeliveryReceipt(
          conversationId = conversation,
          authorDevice = author.publicKey,
          authorSeq = 1L,
          recipientDevice = recipientA.publicKey,
          logicalKey = key,
          state = 1,
          createdAt = 200L,
          updatedAt = 200L,
          attempts = 1
        )
        ok(store.upsertDeliveryReceipt(receipt, lock))
        assert(ok(store.markDeliveryAcked(key, recipientA.publicKey, 300L, lock)))
        assertEquals(ok(store.loadDeliveryReceipt(key, recipientA.publicKey)).state, 2)
        assert(!ok(store.markDeliveryAcked(key, recipientB.publicKey, 300L, lock)))
        IO.unit
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("S2-B capability marker is durable and starts disabled") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2b-capability-")
    try
      StoreTestSupport.withStore(directory) { store =>
        assertEquals(ok(store.s2bSeen), false)
        ok(store.markS2bSeen)
        assertEquals(ok(store.s2bSeen), true)
        IO.unit
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("device sync root round-trips and advances its checkpoint") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2-sync-")
    try
      StoreTestSupport.withStore(directory) { store =>
        val lock   = ok(store.lockGeneration)
        val device = StoreTestSupport.edPublic()
        val record = DeviceSyncRecord(
          peerDevicePub = device,
          syncRoot = ByteVector.fill(32)(0x61.toByte),
          lastAckedSyncSeq = 0L,
          lastBatchSeq = 0L,
          lastBatchHash = ByteVector.fill(32)(0x62.toByte),
          version = 0L,
          updatedAt = 0L
        )
        val inserted = ok(store.insertDeviceSync(record, lock))
        assertEquals(inserted.version, 1L)
        val loaded = ok(store.loadDeviceSync(device))
        assertEquals(loaded.syncRoot, record.syncRoot)
        val advanced = ok(
          store.updateDeviceSync(loaded.copy(lastAckedSyncSeq = 10L, lastBatchSeq = 3L), 1L, lock)
        )
        assertEquals(advanced.version, 2L)
        assertEquals(ok(store.loadDeviceSync(device)).lastAckedSyncSeq, 10L)
        IO.unit
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("companion messages are a sealed read-only projection with state upsert") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2-companion-")
    try
      StoreTestSupport.withStore(directory) { store =>
        val conversation = StoreTestSupport.conversationId(9)
        val author       = StoreTestSupport.edPublic()
        val device       = StoreTestSupport.edPublic()
        val first        = CompanionMessageRecord(
          sourceId = 41L,
          conversationId = conversation,
          authorPub = author,
          devicePub = device,
          seq = 1L,
          body = StoreTestSupport.body("只读副本"),
          state = MessageState.Received,
          createdAt = 100L,
          syncSeq = 1L
        )
        ok(store.upsertCompanionMessage(first))
        val loaded = ok(store.loadCompanionMessage(41L))
        assertEquals(loaded.body, first.body)
        assertEquals(loaded.state, MessageState.Received)
        ok(
          store.upsertCompanionMessage(first.copy(state = MessageState.Delivered, syncSeq = 2L))
        )
        val updated = ok(store.loadCompanionMessage(41L))
        assertEquals(updated.state, MessageState.Delivered)
        assertEquals(updated.syncSeq, 2L)
        assertEquals(ok(store.countCompanionMessages(conversation)), 1L)
        assertEquals(ok(store.listCompanionMessages(conversation, 10)).map(_.sourceId), List(41L))
        IO.unit
      }
    finally StoreTestSupport.deleteTree(directory)
  }

  test("schema v2 with a message migrates to v3 without losing data") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2-migrate-")
    try
      StoreTestSupport.withStore(directory)(_ => IO.unit)
      val database = StorePaths.under(directory).database
      val setup    = DriverManager.getConnection(s"jdbc:sqlite:${database.toAbsolutePath}")
      try
        val statement = setup.createStatement()
        try
          statement.executeUpdate(
            """INSERT INTO messages(conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts)
              |VALUES (zeroblob(32), zeroblob(32), zeroblob(32), zeroblob(32), 1, x'00', x'00', 3, 1, 0)""".stripMargin
          )
          statement.executeUpdate("DROP TABLE companion_messages")
          statement.executeUpdate("DROP TABLE device_sync")
          statement.executeUpdate("DROP TABLE devices")
          statement.executeUpdate("DROP INDEX messages_sync_seq")
          statement.executeUpdate("ALTER TABLE messages DROP COLUMN sync_seq")
          statement.executeUpdate("UPDATE meta SET value = 2 WHERE key = 'schema_version'")
        finally statement.close()
      finally setup.close()

      assertEquals(StoreTestSupport.openResult(StoreTestSupport.config(directory)), Right(()))
      val check = DriverManager.getConnection(s"jdbc:sqlite:${database.toAbsolutePath}")
      try
        val statement = check.createStatement()
        try
          val version = statement.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")
          assert(version.next())
          assertEquals(version.getLong(1), 5L)
          val count = statement.executeQuery("SELECT count(*) FROM messages")
          assert(count.next())
          assertEquals(count.getLong(1), 1L)
          val tables = statement.executeQuery(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('devices','device_sync','companion_messages','peer_device_lists')"
          )
          assert(tables.next())
          assertEquals(tables.getLong(1), 4L)
          val column = statement.executeQuery("SELECT count(*) FROM pragma_table_info('messages') WHERE name = 'sync_seq'")
          assert(column.next())
          assertEquals(column.getLong(1), 1L)
        finally statement.close()
      finally check.close()
    finally StoreTestSupport.deleteTree(directory)
  }

  test("schema v3 migrates to v4 by adding the peer device list table") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2b-migrate-")
    try
      StoreTestSupport.withStore(directory)(_ => IO.unit)
      val database = StorePaths.under(directory).database
      val setup    = DriverManager.getConnection(s"jdbc:sqlite:${database.toAbsolutePath}")
      try
        val statement = setup.createStatement()
        try
          statement.executeUpdate("DROP TABLE peer_device_lists")
          statement.executeUpdate("UPDATE meta SET value = 3 WHERE key = 'schema_version'")
        finally statement.close()
      finally setup.close()

      assertEquals(StoreTestSupport.openResult(StoreTestSupport.config(directory)), Right(()))
      val check = DriverManager.getConnection(s"jdbc:sqlite:${database.toAbsolutePath}")
      try
        val statement = check.createStatement()
        try
          val version = statement.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")
          assert(version.next())
          assertEquals(version.getLong(1), 5L)
          val table = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'peer_device_lists'")
          assert(table.next())
          assertEquals(table.getLong(1), 1L)
        finally statement.close()
      finally check.close()
    finally StoreTestSupport.deleteTree(directory)
  }

  test("companion store opens without a user private key and rejects another user") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-s2-companion-store-")
    try
      val user = StoreTestSupport.edPublic()
      Store
        .openCompanion(StoreTestSupport.config(directory), user)
        .use {
          case Left(error)  => IO.raiseError(new AssertionError(s"companion open failed: ${error.code}"))
          case Right(store) =>
            IO {
              assertEquals(store.identity.role, IdentityRole.Companion)
              assert(!store.identity.hasUserPrivate)
              assertEquals(store.identity.userPublic, user)
              val reopened = Store
                .openCompanion(StoreTestSupport.config(directory), StoreTestSupport.edPublic())
                .use(value => IO.pure(value.map(_ => ())))
                .unsafeRunSync()
              assertEquals(reopened, Left(StorageError.InvalidEnvelope))
            }
        }
        .unsafeRunSync()
    finally StoreTestSupport.deleteTree(directory)
  }
