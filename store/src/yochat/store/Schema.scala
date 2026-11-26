package yochat.store

import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*

/** Schema v4: S1/S2-A plus the S2-B contact-visible signed device-list snapshot. */
private[store] object Schema:

  val Version: Long = 4L

  private val BaseTables = Vector("meta", "sessions", "messages", "contacts", "invitations", "conversations")
  private val V3Tables   = BaseTables ++ Vector("devices", "device_sync", "companion_messages")
  private val V4Tables   = V3Tables :+ "peer_device_lists"

  def initialize: ConnectionIO[Unit] =
    for
      tables <- tableCount
      _      <- if tables == 0L then createSchema else validateSchema
    yield ()

  private def createSchema: ConnectionIO[Unit] =
    for
      _ <- sql"CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)".update.run
      _ <- sql"""CREATE TABLE sessions (
                 |  id BLOB PRIMARY KEY NOT NULL,
                 |  peer_user_pub BLOB NOT NULL,
                 |  peer_device_pub BLOB NOT NULL,
                 |  state BLOB NOT NULL,
                 |  state_version INTEGER NOT NULL,
                 |  generation INTEGER NOT NULL,
                 |  updated_at INTEGER NOT NULL
                 |)""".stripMargin.update.run
      _ <- sql"""CREATE TABLE messages (
                 |  id INTEGER PRIMARY KEY AUTOINCREMENT,
                 |  conversation_id BLOB NOT NULL,
                 |  session_id BLOB NOT NULL,
                 |  author_pub BLOB NOT NULL,
                 |  device_pub BLOB NOT NULL,
                 |  seq INTEGER NOT NULL,
                 |  body BLOB NOT NULL,
                 |  wire BLOB NOT NULL,
                 |  state INTEGER NOT NULL,
                 |  created_at INTEGER NOT NULL,
                 |  attempts INTEGER NOT NULL DEFAULT 0,
                 |  sync_seq INTEGER NOT NULL DEFAULT 0,
                 |  UNIQUE(session_id, device_pub, seq)
                 |)""".stripMargin.update.run
      _ <- sql"""CREATE TABLE contacts (
                 |  user_pub BLOB PRIMARY KEY NOT NULL,
                 |  sealed BLOB NOT NULL,
                 |  version INTEGER NOT NULL,
                 |  created_at INTEGER NOT NULL
                 |)""".stripMargin.update.run
      _ <- sql"""CREATE TABLE invitations (
                 |  id BLOB PRIMARY KEY NOT NULL,
                 |  sealed BLOB NOT NULL,
                 |  version INTEGER NOT NULL,
                 |  created_at INTEGER NOT NULL
                 |)""".stripMargin.update.run
      _ <- sql"""CREATE TABLE conversations (
                 |  id BLOB PRIMARY KEY NOT NULL,
                 |  peer_user_pub BLOB NOT NULL,
                 |  created_at INTEGER NOT NULL,
                 |  last_applied_seq INTEGER NOT NULL
                 |)""".stripMargin.update.run
      _ <- createV3Tables
      _ <- createV4Tables
      _ <- sql"CREATE INDEX messages_conversation_order ON messages(conversation_id, author_pub, seq)".update.run
      _ <- sql"CREATE INDEX sessions_peer_user ON sessions(peer_user_pub)".update.run
      _ <- sql"CREATE INDEX messages_sync_seq ON messages(sync_seq)".update.run
      _ <- writeMeta("schema_version", Version)
      _ <- writeMeta("lock-generation", 0L)
    yield ()

  private def validateSchema: ConnectionIO[Unit] =
    for
      version <- sql"SELECT value FROM meta WHERE key = 'schema_version'".query[Long].option
      _       <- version match
                   case Some(1L) => baseTablesPresent.flatMap(_ => migrateFromOne)
                   case Some(2L) => baseTablesPresent.flatMap(_ => migrateFromTwo)
                   case Some(3L) => v3TablesPresent.flatMap(_ => migrateFromThree)
                   case Some(4L) => v4TablesPresent
                   case Some(_)  => abort(StorageError.UnsupportedSchema)
                   case None     => abort(StorageError.UnsupportedSchema)
      lock    <- sql"SELECT value FROM meta WHERE key = 'lock-generation'".query[Long].option
      _       <- lock match
                   case Some(_) => FC.unit
                   case None    => abort(StorageError.MissingHighWater("lock-generation"))
    yield ()

  private def migrateFromOne: ConnectionIO[Unit] =
    for
      _ <- sql"ALTER TABLE messages ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0".update.run
      _ <- migrateFromTwo
    yield ()

  private def migrateFromTwo: ConnectionIO[Unit] =
    for
      _ <- createV3Tables
      _ <- createV4Tables
      _ <- sql"ALTER TABLE messages ADD COLUMN sync_seq INTEGER NOT NULL DEFAULT 0".update.run
      _ <- sql"CREATE INDEX messages_sync_seq ON messages(sync_seq)".update.run
      _ <- writeMeta("schema_version", Version)
    yield ()

  private def createV3Tables: ConnectionIO[Unit] =
    for
      _ <- sql"""CREATE TABLE devices (
                 |  device_pub BLOB PRIMARY KEY NOT NULL,
                 |  user_pub BLOB NOT NULL,
                 |  role INTEGER NOT NULL,
                 |  name BLOB NOT NULL,
                 |  authorization BLOB NOT NULL,
                 |  version INTEGER NOT NULL,
                 |  created_at INTEGER NOT NULL,
                 |  revoked_at INTEGER,
                 |  join_epoch INTEGER NOT NULL,
                 |  join_manifest BLOB NOT NULL,
                 |  join_manifest_hash BLOB NOT NULL,
                 |  join_cursor_sync_seq INTEGER NOT NULL,
                 |  pairing_nonce BLOB,
                 |  request_hash BLOB
                 |)""".stripMargin.update.run
      _ <- sql"""CREATE TABLE device_sync (
                 |  peer_device_pub BLOB PRIMARY KEY NOT NULL,
                 |  sync_root BLOB NOT NULL,
                 |  last_acked_sync_seq INTEGER NOT NULL,
                 |  last_batch_seq INTEGER NOT NULL,
                 |  last_batch_hash BLOB NOT NULL,
                 |  version INTEGER NOT NULL,
                 |  updated_at INTEGER NOT NULL
                 |)""".stripMargin.update.run
      _ <- sql"""CREATE TABLE companion_messages (
                 |  source_id INTEGER PRIMARY KEY NOT NULL,
                 |  conversation_id BLOB NOT NULL,
                 |  author_pub BLOB NOT NULL,
                 |  device_pub BLOB NOT NULL,
                 |  seq INTEGER NOT NULL,
                 |  body BLOB NOT NULL,
                 |  state INTEGER NOT NULL,
                 |  created_at INTEGER NOT NULL,
                 |  sync_seq INTEGER NOT NULL
                 |)""".stripMargin.update.run
      _ <- sql"CREATE INDEX companion_messages_conversation_order ON companion_messages(conversation_id, seq, source_id)".update.run
      _ <- sql"CREATE INDEX companion_messages_sync_seq ON companion_messages(sync_seq)".update.run
    yield ()

  private def baseTablesPresent: ConnectionIO[Unit] =
    requiredTableCount(BaseTables).flatMap { count =>
      if count == BaseTables.size.toLong then FC.unit else abort(StorageError.UnsupportedSchema)
    }

  private def v3TablesPresent: ConnectionIO[Unit] =
    requiredTableCount(V3Tables).flatMap { count =>
      if count == V3Tables.size.toLong then FC.unit else abort(StorageError.UnsupportedSchema)
    }

  private def migrateFromThree: ConnectionIO[Unit] =
    for
      _ <- createV4Tables
      _ <- writeMeta("schema_version", Version)
    yield ()

  private def v4TablesPresent: ConnectionIO[Unit] =
    requiredTableCount(V4Tables).flatMap { count =>
      if count == V4Tables.size.toLong then FC.unit else abort(StorageError.UnsupportedSchema)
    }

  private def createV4Tables: ConnectionIO[Unit] =
    sql"""CREATE TABLE IF NOT EXISTS peer_device_lists (
       |  user_pub BLOB PRIMARY KEY NOT NULL,
       |  version INTEGER NOT NULL,
       |  payload BLOB NOT NULL,
       |  received_at INTEGER NOT NULL
       |)""".stripMargin.update.run.flatMap(_ => FC.unit)

  private def tableCount: ConnectionIO[Long] =
    sql"SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'".query[Long].unique

  private def requiredTableCount(names: Vector[String]): ConnectionIO[Long] =
    names.foldLeft(FC.pure(0L)) { (acc, name) =>
      acc.flatMap { total =>
        sql"SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = $name".query[Long].unique.map(total + _)
      }
    }

  private def writeMeta(key: String, value: Long): ConnectionIO[Unit] =
    sql"INSERT INTO meta(key, value) VALUES ($key, $value) ON CONFLICT(key) DO UPDATE SET value = excluded.value".update.run.flatMap { changed =>
      if changed == 1 then FC.unit else abort(StorageError.StorageFailure)
    }

  private def abort[A](error: StorageError): ConnectionIO[A] =
    FC.raiseError(new StoreAbort(error))
