package yochat.spike

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import java.util.Comparator

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}
import scala.util.control.NonFatal

/** Real SQLite + application-layer AEAD feasibility harness for ADR-0007.
  *
  * The database stores only ciphertext for sensitive values. This file is a spike, not production code; it deliberately keeps the schema small
  * and asserts the storage gates in one process.
  */
object StorageSpike:

  private inline val MessageCount  = 1_000
  private inline val EnvelopeMagic = "YCE1"
  private inline val NonceLength   = 12
  private inline val TagLength     = 16

  private val Utf8             = StandardCharsets.UTF_8
  private val Iso              = StandardCharsets.ISO_8859_1
  private val StateDomain      = "yochat-store/session-state/v1"
  private val MessageDomain    = "yochat-store/message-body/v1"
  private val ContactDomain    = "yochat-store/contact/v1"
  private val InvitationDomain = "yochat-store/invitation/v1"

  private case class SessionRow(
    state: Array[Byte],
    version: Long,
    generation: Long,
    peerUserPub: Array[Byte],
    peerDevicePub: Array[Byte]
  )

  private case class MessageRow(
    id: Long,
    sessionId: Array[Byte],
    authorPub: Array[Byte],
    devicePub: Array[Byte],
    seq: Long,
    body: Array[Byte],
    wire: Array[Byte]
  )

  def run(): Unit =
    val directory = Files.createTempDirectory("yochat-storage-spike-")
    val database  = directory.resolve("yochat.db")
    try
      val master           = Crypto.randomBytes(32)
      val databaseKey      = Crypto.hkdf(master, Array.emptyByteArray, "yochat-store/database/v1", 32)
      val sessionA         = Crypto.randomBytes(32)
      val sessionB         = Crypto.randomBytes(32)
      val peerUserA        = Crypto.randomBytes(32)
      val peerDeviceA      = Crypto.randomBytes(32)
      val peerUserB        = Crypto.randomBytes(32)
      val peerDeviceB      = Crypto.randomBytes(32)
      val devicePub        = Crypto.randomBytes(32)
      val authorPub        = Crypto.randomBytes(32)
      val conversation     = Crypto.randomBytes(32)
      val stateMarkerA     = "STATE_MARKER_7f3a9c"
      val stateMarkerB     = "STATE_MARKER_b81d4e"
      val bodyMarker       = "BODY_MARKER_42e7a1"
      val contactMarker    = "CONTACT_MARKER_19c3f8"
      val invitationMarker = "INVITATION_MARKER_5b2d90"
      val ephemeralMarker  = "EPHEMERAL_PRIVATE_MARKER_a4c17e"
      val now              = 1_760_000_000_000L
      val markers          = Vector(
        stateMarkerA,
        stateMarkerB,
        bodyMarker,
        contactMarker,
        invitationMarker,
        ephemeralMarker,
        Crypto.hex(master),
        Crypto.base64(master)
      )
      val inputHash        = Crypto.hex(
        Crypto.sha256(
          Vector(
            s"messages=$MessageCount",
            s"state=$stateMarkerA",
            s"state_b=$stateMarkerB",
            s"body=$bodyMarker",
            s"contact=$contactMarker",
            s"invitation=$invitationMarker",
            s"ephemeral=$ephemeralMarker"
          ).mkString("\n").getBytes(Utf8)
        )
      )

      Using.resource(connect(database)) { connection =>
        initialise(connection)
        println(
          s"STORAGE runtime java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} " +
            s"arch=${System.getProperty("os.arch")} sqlite=${queryString(connection, "SELECT sqlite_version()")}"
        )
        println(s"STORAGE pragmas journal_mode=${pragma(connection, "journal_mode")} synchronous=${pragma(connection, "synchronous")}")
        insertSession(
          connection,
          databaseKey,
          sessionA,
          peerUserA,
          peerDeviceA,
          generation = 1L,
          version = 1L,
          plaintext = stateMarkerA.getBytes(Utf8),
          now = now
        )
        insertSession(
          connection,
          databaseKey,
          sessionB,
          peerUserB,
          peerDeviceB,
          generation = 1L,
          version = 1L,
          plaintext = stateMarkerB.getBytes(Utf8),
          now = now
        )
        insertContact(connection, databaseKey, sessionA, 1L, contactMarker.getBytes(Utf8), now)
        insertInvitation(connection, databaseKey, sessionB, 1L, invitationMarker, ephemeralMarker, now)

        val insertTimings = (1 to MessageCount).toVector.map { index =>
          val (_, elapsed) = measure {
            insertMessage(
              connection,
              databaseKey,
              conversation,
              sessionA,
              authorPub,
              devicePub,
              index.toLong,
              s"$bodyMarker-$index".getBytes(Utf8),
              Crypto.randomBytes(64),
              now + index
            )
          }
          elapsed
        }

        val readTimings = (1 to MessageCount).toVector.map { index =>
          val (bodyResult, elapsed) = measure {
            readMessage(connection, databaseKey, conversation, sessionA, authorPub, devicePub, index.toLong)
          }
          val body                  = bodyResult.getOrElse(throw new IllegalStateException(s"message $index round-trip failed: $bodyResult"))
          val expected              = s"$bodyMarker-$index".getBytes(Utf8)
          require(java.util.Arrays.equals(body, expected), s"message $index round-trip mismatch")
          elapsed
        }

        val sessionPlan = queryPlan(connection, "SELECT state, state_version, generation FROM sessions WHERE id = ?") { statement =>
          statement.setBytes(1, sessionA)
        }
        val messagePlan = queryPlan(
          connection,
          "SELECT body FROM messages WHERE conversation_id = ? AND session_id = ? AND author_pub = ? AND device_pub = ? AND seq = ?"
        ) { statement =>
          statement.setBytes(1, conversation)
          statement.setBytes(2, sessionA)
          statement.setBytes(3, authorPub)
          statement.setBytes(4, devicePub)
          statement.setLong(5, 1L)
        }
        val listPlan    = queryPlan(
          connection,
          "SELECT id, body FROM messages WHERE conversation_id = ? AND author_pub = ? ORDER BY seq"
        ) { statement =>
          statement.setBytes(1, conversation)
          statement.setBytes(2, authorPub)
        }
        println(s"STORAGE session_plan=${sessionPlan.mkString(" | ")}")
        println(s"STORAGE message_plan=${messagePlan.mkString(" | ")}")
        println(s"STORAGE list_plan=${listPlan.mkString(" | ")}")
        require(sessionPlan.nonEmpty && sessionPlan.forall(detail => !detail.toUpperCase.contains("SCAN")), "session lookup scans")
        require(messagePlan.nonEmpty && messagePlan.forall(detail => !detail.toUpperCase.contains("SCAN")), "message lookup scans")
        require(
          listPlan.nonEmpty && listPlan.forall(detail => !detail.toUpperCase.contains("SCAN") && !detail.toUpperCase.contains("TEMP B-TREE")),
          "message list scans"
        )

        val openFiles = filesUnder(directory).map(_.getFileName.toString)
        println(s"STORAGE open_files=${openFiles.mkString(",")}")
        require(openFiles.exists(_.endsWith("-wal")), "WAL file is missing while the connection is open")
        require(openFiles.exists(_.endsWith("-shm")), "SHM file is missing while the connection is open")
        val foundOpen = findMarkers(directory, markers)
        require(foundOpen.isEmpty, s"plaintext marker found while WAL is open: ${foundOpen.mkString(", ")}")
        println(s"STORAGE at_rest_open markers=${markers.size} found=0")

        checkpoint(connection)
        val totalNs = insertTimings.sum + readTimings.sum
        println(
          s"STORAGE inserts=$MessageCount insert_total_ms=${insertTimings.sum / 1_000_000L} " +
            s"insert_p95_us=${p95(insertTimings) / 1_000L} reads=$MessageCount " +
            s"read_total_ms=${readTimings.sum / 1_000_000L} read_p95_us=${p95(readTimings) / 1_000L} " +
            s"total_ms=${totalNs / 1_000_000L} input_hash=$inputHash"
        )
        require(p95(insertTimings) < 2_000_000_000L, "insert p95 exceeds 2s")
        require(p95(readTimings) < 2_000_000_000L, "read p95 exceeds 2s")
        require(totalNs < 2_000_000_000L, "1,000 inserts + 1,000 reads exceed 2s")
      }

      Using.resource(connect(database)) { connection =>
        val loaded       = loadSession(connection, databaseKey, sessionA)
        loaded match
          case Left(error)  => throw new IllegalStateException(s"valid state did not reopen: $error")
          case Right(value) =>
            require(java.util.Arrays.equals(value, stateMarkerA.getBytes(Utf8)), "reopened state mismatch")
        val reopenedBody = readMessage(connection, databaseKey, conversation, sessionA, authorPub, devicePub, 1L)
          .getOrElse(throw new IllegalStateException("reopened message missing"))
        require(java.util.Arrays.equals(reopenedBody, s"$bodyMarker-1".getBytes(Utf8)), "reopened message mismatch")

        val wrongKey      = Crypto.hkdf(Crypto.randomBytes(32), Array.emptyByteArray, "yochat-store/database/v1", 32)
        val wrongInstance = loadSession(connection, wrongKey, sessionA)
        require(wrongInstance == Left("InvalidCiphertext"), s"wrong instance opened session: $wrongInstance")

        val sessionBRow = readSessionRow(connection, sessionB).getOrElse(throw new IllegalStateException("session B missing"))
        val sessionARow = readSessionRow(connection, sessionA).getOrElse(throw new IllegalStateException("session A missing"))
        swapStateForTest(connection, sessionB, sessionARow.state)
        val swapped     = loadSession(connection, databaseKey, sessionB)
        require(swapped == Left("InvalidCiphertext"), s"swapped state was accepted: $swapped")

        val messageOne   = readMessageRow(connection, 1L).getOrElse(throw new IllegalStateException("message 1 missing"))
        val messageTwo   = readMessageRow(connection, 2L).getOrElse(throw new IllegalStateException("message 2 missing"))
        swapMessageForTest(connection, 1L, messageTwo.body)
        val swappedBody  = readMessage(
          connection,
          databaseKey,
          conversation,
          sessionA,
          authorPub,
          devicePub,
          1L
        )
        require(swappedBody == Left("InvalidCiphertext"), s"swapped message body was accepted: $swappedBody")
        swapMessageForTest(connection, 1L, messageOne.body)
        val bodyRestored = readMessage(connection, databaseKey, conversation, sessionA, authorPub, devicePub, 1L)
        require(bodyRestored.exists(value => java.util.Arrays.equals(value, s"$bodyMarker-1".getBytes(Utf8))), "body restore failed")
        swapWireForTest(connection, 1L, messageTwo.wire)
        val swappedWire  = readMessage(connection, databaseKey, conversation, sessionA, authorPub, devicePub, 1L)
        require(swappedWire == Left("InvalidCiphertext"), s"swapped message wire was accepted: $swappedWire")
        swapWireForTest(connection, 1L, messageOne.wire)

        val updated         = updateSession(
          connection,
          databaseKey,
          sessionA,
          peerUserA,
          peerDeviceA,
          sessionARow.generation,
          s"${stateMarkerA}_v2".getBytes(Utf8),
          now + 2
        )
        require(updated == 2L, s"unexpected state version $updated")
        restoreOldState(connection, sessionA, sessionARow)
        val stale           = loadSession(connection, databaseKey, sessionA)
        require(stale == Left("StaleGeneration"), s"rollback was accepted: $stale")
        val staleWrite      = Try(
          updateSession(
            connection,
            databaseKey,
            sessionA,
            peerUserA,
            peerDeviceA,
            sessionARow.generation,
            s"${stateMarkerA}_v3".getBytes(Utf8),
            now + 3
          )
        )
        require(staleWrite.isFailure, "stale write was accepted")
        val afterStaleWrite = readSessionRow(connection, sessionA).getOrElse(throw new IllegalStateException("session A missing"))
        require(afterStaleWrite.version == sessionARow.version, "stale write changed the row version")
        require(readMeta(connection, sessionVersionKey(sessionA)).contains(2L), "stale write changed the high-water")

        deleteSession(connection, sessionB, sessionBRow.version)
        resurrectOldState(connection, sessionB, sessionBRow)
        val resurrected = loadSession(connection, databaseKey, sessionB)
        require(resurrected == Left("StaleGeneration"), s"deleted session was resurrected: $resurrected")

        swapMessageForTest(connection, 1L, messageOne.body)
        val restored        = readMessage(connection, databaseKey, conversation, sessionA, authorPub, devicePub, 1L)
        require(restored.isRight, "restore of message body failed")
        val restoredSession = loadSession(connection, databaseKey, sessionA)
        require(restoredSession == Left("StaleGeneration"), s"restored old state was accepted: $restoredSession")
      }

      val foundClosed = findMarkers(directory, markers)
      require(foundClosed.isEmpty, s"plaintext marker found after close: ${foundClosed.mkString(", ")}")
      println(s"STORAGE at_rest_closed markers=${markers.size} found=0")

      println("STORAGE_CHECKS PASS")
    finally deleteTree(directory)

  private def connect(database: Path): Connection =
    DriverManager.getConnection(s"jdbc:sqlite:${database.toAbsolutePath}")

  private def initialise(connection: Connection): Unit =
    execute(connection, "PRAGMA journal_mode = WAL")
    execute(connection, "PRAGMA synchronous = FULL")
    execute(connection, "PRAGMA wal_autocheckpoint = 0")
    execute(connection, "PRAGMA foreign_keys = ON")
    execute(connection, "PRAGMA busy_timeout = 5000")
    execute(connection, "PRAGMA secure_delete = ON")
    execute(
      connection,
      """CREATE TABLE IF NOT EXISTS meta (
        |  key TEXT PRIMARY KEY NOT NULL,
        |  value INTEGER NOT NULL
        |)""".stripMargin
    )
    execute(
      connection,
      """CREATE TABLE IF NOT EXISTS sessions (
        |  id BLOB PRIMARY KEY NOT NULL,
        |  peer_user_pub BLOB NOT NULL,
        |  peer_device_pub BLOB NOT NULL,
        |  state BLOB NOT NULL,
        |  state_version INTEGER NOT NULL,
        |  generation INTEGER NOT NULL,
        |  updated_at INTEGER NOT NULL
        |)""".stripMargin
    )
    execute(
      connection,
      """CREATE TABLE IF NOT EXISTS messages (
        |  id INTEGER PRIMARY KEY NOT NULL,
        |  conversation_id BLOB NOT NULL,
        |  session_id BLOB NOT NULL,
        |  author_pub BLOB NOT NULL,
        |  device_pub BLOB NOT NULL,
        |  seq INTEGER NOT NULL,
        |  body BLOB NOT NULL,
        |  wire BLOB NOT NULL,
        |  state INTEGER NOT NULL,
        |  created_at INTEGER NOT NULL,
        |  UNIQUE(session_id, device_pub, seq)
        |)""".stripMargin
    )
    execute(
      connection,
      """CREATE TABLE IF NOT EXISTS contacts (
        |  user_pub BLOB PRIMARY KEY NOT NULL,
        |  sealed BLOB NOT NULL,
        |  version INTEGER NOT NULL,
        |  created_at INTEGER NOT NULL
        |)""".stripMargin
    )
    execute(
      connection,
      """CREATE TABLE IF NOT EXISTS invitations (
        |  id BLOB PRIMARY KEY NOT NULL,
        |  sealed BLOB NOT NULL,
        |  version INTEGER NOT NULL,
        |  created_at INTEGER NOT NULL
        |)""".stripMargin
    )
    execute(
      connection,
      "CREATE INDEX IF NOT EXISTS messages_conversation_order ON messages(conversation_id, author_pub, seq)"
    )
    writeMeta(connection, "schema_version", 1L)

  private def insertSession(
    connection: Connection,
    databaseKey: Array[Byte],
    sessionId: Array[Byte],
    peerUserPub: Array[Byte],
    peerDevicePub: Array[Byte],
    generation: Long,
    version: Long,
    plaintext: Array[Byte],
    now: Long
  ): Unit =
    transaction(connection) {
      val state = seal(sessionStateKey(databaseKey, sessionId), stateAad(sessionId, peerUserPub, peerDevicePub, generation, version), plaintext)
      withStatement(
        connection,
        "INSERT INTO sessions(id, peer_user_pub, peer_device_pub, state, state_version, generation, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
      ) { statement =>
        statement.setBytes(1, sessionId)
        statement.setBytes(2, peerUserPub)
        statement.setBytes(3, peerDevicePub)
        statement.setBytes(4, state)
        statement.setLong(5, version)
        statement.setLong(6, generation)
        statement.setLong(7, now)
        statement.executeUpdate()
        ()
      }
      writeMeta(connection, sessionVersionKey(sessionId), version)
    }

  private def updateSession(
    connection: Connection,
    databaseKey: Array[Byte],
    sessionId: Array[Byte],
    peerUserPub: Array[Byte],
    peerDevicePub: Array[Byte],
    generation: Long,
    plaintext: Array[Byte],
    now: Long
  ): Long =
    transaction(connection) {
      val current = readSessionRow(connection, sessionId).getOrElse(throw new IllegalStateException("session missing"))
      val high    = readMeta(connection, sessionVersionKey(sessionId)).getOrElse(throw new IllegalStateException("missing high-water"))
      require(current.version == high, s"stale write: row=${current.version} high=$high")
      val next    = current.version + 1
      val state   = seal(sessionStateKey(databaseKey, sessionId), stateAad(sessionId, peerUserPub, peerDevicePub, generation, next), plaintext)
      val changed = withStatement(
        connection,
        "UPDATE sessions SET state = ?, state_version = ?, updated_at = ? WHERE id = ? AND state_version = ? AND generation = ?"
      ) { statement =>
        statement.setBytes(1, state)
        statement.setLong(2, next)
        statement.setLong(3, now)
        statement.setBytes(4, sessionId)
        statement.setLong(5, current.version)
        statement.setLong(6, generation)
        statement.executeUpdate()
      }
      require(changed == 1, s"session update changed $changed rows")
      writeMeta(connection, sessionVersionKey(sessionId), next)
      next
    }

  private def insertMessage(
    connection: Connection,
    databaseKey: Array[Byte],
    conversationId: Array[Byte],
    sessionId: Array[Byte],
    authorPub: Array[Byte],
    devicePub: Array[Byte],
    seq: Long,
    body: Array[Byte],
    wire: Array[Byte],
    now: Long
  ): Unit =
    transaction(connection) {
      val sealedBody = seal(
        messageBodyKey(databaseKey, sessionId),
        messageAad(conversationId, sessionId, authorPub, devicePub, seq, Crypto.sha256(wire)),
        body
      )
      withStatement(
        connection,
        """INSERT INTO messages(id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      ) { statement =>
        statement.setLong(1, seq)
        statement.setBytes(2, conversationId)
        statement.setBytes(3, sessionId)
        statement.setBytes(4, authorPub)
        statement.setBytes(5, devicePub)
        statement.setLong(6, seq)
        statement.setBytes(7, sealedBody)
        statement.setBytes(8, wire)
        statement.setInt(9, 1)
        statement.setLong(10, now)
        statement.executeUpdate()
        ()
      }
    }

  private def insertContact(
    connection: Connection,
    databaseKey: Array[Byte],
    userPub: Array[Byte],
    version: Long,
    plaintext: Array[Byte],
    now: Long
  ): Unit =
    transaction(connection) {
      val sealedContact = seal(contactKey(databaseKey), contactAad(userPub, version), plaintext)
      withStatement(connection, "INSERT INTO contacts(user_pub, sealed, version, created_at) VALUES (?, ?, ?, ?)") { statement =>
        statement.setBytes(1, userPub)
        statement.setBytes(2, sealedContact)
        statement.setLong(3, version)
        statement.setLong(4, now)
        statement.executeUpdate()
        ()
      }
      writeMeta(connection, contactVersionKey(userPub), version)
    }

  private def insertInvitation(
    connection: Connection,
    databaseKey: Array[Byte],
    invitationId: Array[Byte],
    version: Long,
    payloadMarker: String,
    ephemeralMarker: String,
    now: Long
  ): Unit =
    transaction(connection) {
      val plaintext        = s"$payloadMarker|$ephemeralMarker".getBytes(Utf8)
      val sealedInvitation = seal(invitationKey(databaseKey), invitationAad(invitationId, version), plaintext)
      withStatement(connection, "INSERT INTO invitations(id, sealed, version, created_at) VALUES (?, ?, ?, ?)") { statement =>
        statement.setBytes(1, invitationId)
        statement.setBytes(2, sealedInvitation)
        statement.setLong(3, version)
        statement.setLong(4, now)
        statement.executeUpdate()
        ()
      }
      writeMeta(connection, invitationVersionKey(invitationId), version)
    }

  private def readMessage(
    connection: Connection,
    databaseKey: Array[Byte],
    conversationId: Array[Byte],
    sessionId: Array[Byte],
    authorPub: Array[Byte],
    devicePub: Array[Byte],
    seq: Long
  ): Either[String, Array[Byte]] =
    withStatement(
      connection,
      """SELECT id, session_id, author_pub, device_pub, seq, body, wire
        |FROM messages
        |WHERE conversation_id = ? AND session_id = ? AND author_pub = ? AND device_pub = ? AND seq = ?""".stripMargin
    ) { statement =>
      statement.setBytes(1, conversationId)
      statement.setBytes(2, sessionId)
      statement.setBytes(3, authorPub)
      statement.setBytes(4, devicePub)
      statement.setLong(5, seq)
      Using.resource(statement.executeQuery()) { result =>
        if !result.next() then Left("NotFound")
        else
          val row = MessageRow(
            id = result.getLong("id"),
            sessionId = result.getBytes("session_id"),
            authorPub = result.getBytes("author_pub"),
            devicePub = result.getBytes("device_pub"),
            seq = result.getLong("seq"),
            body = result.getBytes("body"),
            wire = result.getBytes("wire")
          )
          val aad = messageAad(
            conversationId,
            row.sessionId,
            row.authorPub,
            row.devicePub,
            row.seq,
            Crypto.sha256(row.wire)
          )
          val key = messageBodyKey(databaseKey, row.sessionId)
          open(key, aad, row.body)
      }
    }

  private def readMessageRow(connection: Connection, id: Long): Option[MessageRow] =
    withStatement(
      connection,
      "SELECT id, session_id, author_pub, device_pub, seq, body, wire FROM messages WHERE id = ?"
    ) { statement =>
      statement.setLong(1, id)
      Using.resource(statement.executeQuery()) { result =>
        if result.next() then
          Some(
            MessageRow(
              id = result.getLong("id"),
              sessionId = result.getBytes("session_id"),
              authorPub = result.getBytes("author_pub"),
              devicePub = result.getBytes("device_pub"),
              seq = result.getLong("seq"),
              body = result.getBytes("body"),
              wire = result.getBytes("wire")
            )
          )
        else None
      }
    }

  private def readSessionRow(connection: Connection, sessionId: Array[Byte]): Option[SessionRow] =
    withStatement(
      connection,
      "SELECT state, state_version, generation, peer_user_pub, peer_device_pub FROM sessions WHERE id = ?"
    ) { statement =>
      statement.setBytes(1, sessionId)
      Using.resource(statement.executeQuery()) { result =>
        if result.next() then
          Some(
            SessionRow(
              state = result.getBytes("state"),
              version = result.getLong("state_version"),
              generation = result.getLong("generation"),
              peerUserPub = result.getBytes("peer_user_pub"),
              peerDevicePub = result.getBytes("peer_device_pub")
            )
          )
        else None
      }
    }

  private def loadSession(connection: Connection, databaseKey: Array[Byte], sessionId: Array[Byte]): Either[String, Array[Byte]] =
    readSessionRow(connection, sessionId) match
      case None      => Left("NotFound")
      case Some(row) =>
        readMeta(connection, sessionVersionKey(sessionId)) match
          case None                             => Left("MissingHighWater")
          case Some(high) if row.version < high => Left("StaleGeneration")
          case Some(high) if row.version > high => Left("InvalidStateVersion")
          case Some(_)                          =>
            open(
              sessionStateKey(databaseKey, sessionId),
              stateAad(sessionId, row.peerUserPub, row.peerDevicePub, row.generation, row.version),
              row.state
            )

  private def restoreOldState(connection: Connection, sessionId: Array[Byte], old: SessionRow): Unit =
    withStatement(
      connection,
      "UPDATE sessions SET state = ?, state_version = ?, generation = ? WHERE id = ?"
    ) { statement =>
      statement.setBytes(1, old.state)
      statement.setLong(2, old.version)
      statement.setLong(3, old.generation)
      statement.setBytes(4, sessionId)
      statement.executeUpdate()
      ()
    }

  private def deleteSession(connection: Connection, sessionId: Array[Byte], version: Long): Unit =
    transaction(connection) {
      writeMeta(connection, sessionVersionKey(sessionId), version + 1)
      val deleted = withStatement(connection, "DELETE FROM sessions WHERE id = ? AND state_version = ?") { statement =>
        statement.setBytes(1, sessionId)
        statement.setLong(2, version)
        statement.executeUpdate()
      }
      require(deleted == 1, s"session delete changed $deleted rows")
    }

  private def resurrectOldState(connection: Connection, sessionId: Array[Byte], old: SessionRow): Unit =
    withStatement(
      connection,
      "INSERT INTO sessions(id, peer_user_pub, peer_device_pub, state, state_version, generation, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
    ) { statement =>
      statement.setBytes(1, sessionId)
      statement.setBytes(2, old.peerUserPub)
      statement.setBytes(3, old.peerDevicePub)
      statement.setBytes(4, old.state)
      statement.setLong(5, old.version)
      statement.setLong(6, old.generation)
      statement.setLong(7, 1_760_000_000_000L)
      statement.executeUpdate()
      ()
    }

  private def swapStateForTest(connection: Connection, sessionId: Array[Byte], otherState: Array[Byte]): Unit =
    withStatement(connection, "UPDATE sessions SET state = ? WHERE id = ?") { statement =>
      statement.setBytes(1, otherState)
      statement.setBytes(2, sessionId)
      statement.executeUpdate()
      ()
    }

  private def swapMessageForTest(connection: Connection, id: Long, otherBody: Array[Byte]): Unit =
    withStatement(connection, "UPDATE messages SET body = ? WHERE id = ?") { statement =>
      statement.setBytes(1, otherBody)
      statement.setLong(2, id)
      statement.executeUpdate()
      ()
    }

  private def swapWireForTest(connection: Connection, id: Long, otherWire: Array[Byte]): Unit =
    withStatement(connection, "UPDATE messages SET wire = ? WHERE id = ?") { statement =>
      statement.setBytes(1, otherWire)
      statement.setLong(2, id)
      statement.executeUpdate()
      ()
    }

  private def queryPlan(connection: Connection, sql: String)(bind: PreparedStatement => Unit): Vector[String] =
    withStatement(connection, s"EXPLAIN QUERY PLAN $sql") { statement =>
      bind(statement)
      Using.resource(statement.executeQuery()) { result =>
        readPlan(result, Vector.empty)
      }
    }

  @tailrec
  private def readPlan(result: ResultSet, acc: Vector[String]): Vector[String] =
    if result.next() then readPlan(result, acc :+ result.getString("detail")) else acc

  private def checkpoint(connection: Connection): Unit =
    execute(connection, "PRAGMA wal_checkpoint(TRUNCATE)")

  private def pragma(connection: Connection, name: String): String =
    withStatement(connection, s"PRAGMA $name") { statement =>
      Using.resource(statement.executeQuery()) { result =>
        if result.next() then result.getString(1) else "missing"
      }
    }

  private def queryString(connection: Connection, sql: String): String =
    withStatement(connection, sql) { statement =>
      Using.resource(statement.executeQuery()) { result =>
        if result.next() then result.getString(1) else "missing"
      }
    }

  private def readMeta(connection: Connection, key: String): Option[Long] =
    withStatement(connection, "SELECT value FROM meta WHERE key = ?") { statement =>
      statement.setString(1, key)
      Using.resource(statement.executeQuery()) { result =>
        if result.next() then Some(result.getLong("value")) else None
      }
    }

  private def writeMeta(connection: Connection, key: String, value: Long): Unit =
    withStatement(
      connection,
      "INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"
    ) { statement =>
      statement.setString(1, key)
      statement.setLong(2, value)
      val changed = statement.executeUpdate()
      require(changed == 1, s"meta write changed $changed rows for $key")
    }

  private def sessionVersionKey(sessionId: Array[Byte]): String =
    s"session-state-version/${Crypto.hex(sessionId)}"

  private def contactVersionKey(userPub: Array[Byte]): String =
    s"contact-version/${Crypto.hex(userPub)}"

  private def invitationVersionKey(invitationId: Array[Byte]): String =
    s"invitation-version/${Crypto.hex(invitationId)}"

  private def sessionStateKey(databaseKey: Array[Byte], sessionId: Array[Byte]): Array[Byte] =
    Crypto.hkdf(databaseKey, sessionId, "yochat-store/session-state/v1", 32)

  private def messageBodyKey(databaseKey: Array[Byte], sessionId: Array[Byte]): Array[Byte] =
    Crypto.hkdf(databaseKey, sessionId, "yochat-store/message-body/v1", 32)

  private def contactKey(databaseKey: Array[Byte]): Array[Byte] =
    Crypto.hkdf(databaseKey, Array.emptyByteArray, "yochat-store/contact/v1", 32)

  private def invitationKey(databaseKey: Array[Byte]): Array[Byte] =
    Crypto.hkdf(databaseKey, Array.emptyByteArray, "yochat-store/invitation/v1", 32)

  private def stateAad(
    sessionId: Array[Byte],
    peerUserPub: Array[Byte],
    peerDevicePub: Array[Byte],
    generation: Long,
    version: Long
  ): Array[Byte] =
    aad(StateDomain, sessionId, peerUserPub, peerDevicePub, u64(generation), u64(version))

  private def messageAad(
    conversationId: Array[Byte],
    sessionId: Array[Byte],
    authorPub: Array[Byte],
    devicePub: Array[Byte],
    seq: Long,
    wireHash: Array[Byte]
  ): Array[Byte] =
    aad(MessageDomain, conversationId, sessionId, authorPub, devicePub, u64(seq), wireHash)

  private def contactAad(userPub: Array[Byte], version: Long): Array[Byte] =
    aad(ContactDomain, userPub, u64(version))

  private def invitationAad(invitationId: Array[Byte], version: Long): Array[Byte] =
    aad(InvitationDomain, invitationId, u64(version))

  private def aad(domain: String, fields: Array[Byte]*): Array[Byte] =
    val out = new ByteArrayOutputStream()
    out.write(domain.getBytes(Utf8))
    out.write(0)
    fields.foreach(out.write)
    out.toByteArray

  private def seal(key: Array[Byte], aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    val nonce = Crypto.randomBytes(NonceLength)
    EnvelopeMagic.getBytes(Utf8) ++ nonce ++ Crypto.aeadEncrypt(key, nonce, aad, plaintext)

  private def open(key: Array[Byte], aad: Array[Byte], envelope: Array[Byte]): Either[String, Array[Byte]] =
    val magic = EnvelopeMagic.getBytes(Utf8)
    if envelope.length < magic.length + NonceLength + TagLength then Left("InvalidEnvelope")
    else if !java.util.Arrays.equals(envelope.take(magic.length), magic) then Left("InvalidEnvelope")
    else
      val nonce      = envelope.slice(magic.length, magic.length + NonceLength)
      val ciphertext = envelope.drop(magic.length + NonceLength)
      try Right(Crypto.aeadDecrypt(key, nonce, aad, ciphertext))
      catch case _: GeneralSecurityException => Left("InvalidCiphertext")

  private def u64(value: Long): Array[Byte] =
    ByteBuffer.allocate(8).putLong(value).array()

  private def transaction[A](connection: Connection)(body: => A): A =
    connection.setAutoCommit(false)
    try
      val result = body
      connection.commit()
      result
    catch
      case NonFatal(error) =>
        connection.rollback()
        throw error
    finally connection.setAutoCommit(true)

  private def execute(connection: Connection, sql: String): Unit =
    withStatement(connection, sql) { statement =>
      statement.execute()
      ()
    }

  private def withStatement[A](connection: Connection, sql: String)(body: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(sql))(body)

  private def measure[A](body: => A): (A, Long) =
    val started = System.nanoTime()
    val result  = body
    (result, System.nanoTime() - started)

  private def p95(values: Vector[Long]): Long =
    val sorted = values.sorted
    val index  = math.max(0, math.ceil(sorted.size * 0.95).toInt - 1)
    sorted(index)

  private def filesUnder(directory: Path): Vector[Path] =
    Using.resource(Files.walk(directory)) { stream =>
      stream.iterator.asScala.toVector.filter(Files.isRegularFile(_))
    }

  private def findMarkers(directory: Path, markers: Vector[String]): Vector[String] =
    for
      file   <- filesUnder(directory)
      marker <- markers
      bytes   = Files.readAllBytes(file)
      text    = new String(bytes, Iso)
      if text.contains(marker)
    yield s"${file.getFileName}:$marker"

  private def deleteTree(directory: Path): Unit =
    if Files.exists(directory) then
      Using.resource(Files.walk(directory)) { stream =>
        stream.sorted(Comparator.reverseOrder[Path]()).forEach(path => Files.delete(path))
      }

  private def require(condition: Boolean, message: String): Unit =
    if !condition then throw new IllegalStateException(message)
