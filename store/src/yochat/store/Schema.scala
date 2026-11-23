package yochat.store

import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*

/** Schema v1. There are no foreign keys; message rows intentionally outlive their session rows. */
private[store] object Schema:

  val Version: Long = 2L

  private val Tables = Vector("meta", "sessions", "messages", "contacts", "invitations", "conversations")

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
      _ <- sql"CREATE INDEX messages_conversation_order ON messages(conversation_id, author_pub, seq)".update.run
      _ <- sql"CREATE INDEX sessions_peer_user ON sessions(peer_user_pub)".update.run
      _ <- writeMeta("schema_version", Version)
      _ <- writeMeta("lock-generation", 0L)
    yield ()

  private def validateSchema: ConnectionIO[Unit] =
    for
      required <- requiredTableCount
      _        <- if required == Tables.size.toLong then FC.unit else abort(StorageError.UnsupportedSchema)
      version  <- sql"SELECT value FROM meta WHERE key = 'schema_version'".query[Long].option
      _        <- version match
                    case Some(value) if value == 1L      => migrateFromOne
                    case Some(value) if value == Version => FC.unit
                    case Some(_)                         => abort(StorageError.UnsupportedSchema)
                    case None                            => abort(StorageError.UnsupportedSchema)
      lock     <- sql"SELECT value FROM meta WHERE key = 'lock-generation'".query[Long].option
      _        <- lock match
                    case Some(_) => FC.unit
                    case None    => abort(StorageError.MissingHighWater("lock-generation"))
    yield ()

  private def migrateFromOne: ConnectionIO[Unit] =
    for
      _ <- sql"ALTER TABLE messages ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0".update.run
      _ <- writeMeta("schema_version", Version)
    yield ()

  private def tableCount: ConnectionIO[Long] =
    sql"SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'".query[Long].unique

  private def requiredTableCount: ConnectionIO[Long] =
    sql"""SELECT count(*) FROM sqlite_master
         |WHERE type = 'table' AND name IN ('meta', 'sessions', 'messages', 'contacts', 'invitations', 'conversations')""".stripMargin
      .query[Long]
      .unique

  private def writeMeta(key: String, value: Long): ConnectionIO[Unit] =
    sql"INSERT INTO meta(key, value) VALUES ($key, $value) ON CONFLICT(key) DO UPDATE SET value = excluded.value".update.run.flatMap { changed =>
      if changed == 1 then FC.unit else abort(StorageError.StorageFailure)
    }

  private def abort[A](error: StorageError): ConnectionIO[A] =
    FC.raiseError(new StoreAbort(error))
