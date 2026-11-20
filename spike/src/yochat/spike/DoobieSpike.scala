package yochat.spike

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator

import scala.util.Using

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.zaxxer.hikari.HikariConfig
import org.sqlite.{SQLiteConfig, SQLiteDataSource}
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.hikari.HikariTransactor
import org.typelevel.doobie.implicits.*

/** Doobie 1.0 + Hikari(max 1) + sqlite-jdbc smoke test.
  *
  * Proves only the access-layer boundary: Hikari resource, `Transactor[IO]`, a transaction that rolls back, typed reads, and SQLite
  * configuration. The encryption/query-plan evidence lives in StorageSpike.
  */
object DoobieSpike:

  private val Utf8 = StandardCharsets.UTF_8

  def run(): Unit =
    val directory = Files.createTempDirectory("yochat-doobie-spike-")
    val database  = directory.resolve("yochat.db")
    try
      val result = transactor(database)
        .use { transactor =>
          val id      = Array[Byte](1, 2, 3, 4)
          val payload = "DOOBIE_PAYLOAD".getBytes(Utf8)
          val setup   =
            for
              _ <- sql"""CREATE TABLE IF NOT EXISTS probe (
                     |  id BLOB PRIMARY KEY NOT NULL,
                     |  payload BLOB NOT NULL,
                     |  version INTEGER NOT NULL
                     |)""".stripMargin.update.run
              _ <- sql"INSERT INTO probe(id, payload, version) VALUES ($id, $payload, 1)".update.run
            yield ()
          val read    = sql"SELECT payload FROM probe WHERE id = $id".query[Array[Byte]].unique
          val pragmas =
            for
              journal     <- sql"PRAGMA journal_mode".query[String].unique
              synchronous <- sql"PRAGMA synchronous".query[Int].unique
            yield (journal, synchronous)
          val plan    =
            sql"EXPLAIN QUERY PLAN SELECT payload FROM probe WHERE id = $id"
              .query[(Int, Int, Int, String)]
              .to[List]
          val failing =
            for
              changed <- sql"UPDATE probe SET payload = ${"BROKEN".getBytes(Utf8)} WHERE id = $id".update.run
              _       <-
                if changed == 1 then FC.raiseError[Unit](new IllegalStateException("forced rollback"))
                else FC.raiseError[Unit](new IllegalStateException(s"rollback update changed $changed rows"))
            yield ()
          for
            _             <- transactor.trans.apply(setup)
            loaded        <- transactor.trans.apply(read)
            pragmaValues  <- transactor.trans.apply(pragmas)
            queryPlan     <- transactor.trans.apply(plan)
            rollbackError <- transactor.trans.apply(failing).attempt
            afterRollback <- transactor.trans.apply(read)
            _             <- IO.raiseWhen(!isForcedRollback(rollbackError))(
                               new IllegalStateException(s"unexpected rollback result: $rollbackError")
                             )
            _             <- IO.raiseWhen(!java.util.Arrays.equals(loaded, payload))(new IllegalStateException("doobie read mismatch"))
            _             <- IO.raiseWhen(!java.util.Arrays.equals(afterRollback, payload))(
                               new IllegalStateException("doobie rollback did not restore payload")
                             )
            _             <- IO.raiseWhen(pragmaValues != ("wal", 2))(new IllegalStateException("doobie SQLite PRAGMAs were not applied"))
            _             <- IO.raiseWhen(!queryPlan.exists(row => row._4.contains("SEARCH")))(
                               new IllegalStateException("doobie query plan does not use an index")
                             )
          yield (loaded, pragmaValues, queryPlan)
        }
        .unsafeRunSync()
      println(s"DOOBIE payload=${new String(result._1, Utf8)} journal=${result._2._1} synchronous=${result._2._2}")
      println(s"DOOBIE plan=${result._3.map(row => row._4).mkString(" | ")}")
      println("DOOBIE_CHECKS PASS")
    finally deleteTree(directory)

  private def isForcedRollback(result: Either[Throwable, Unit]): Boolean =
    result match
      case Left(error: IllegalStateException) => error.getMessage == "forced rollback"
      case _                                  => false

  private def transactor(database: Path): Resource[IO, HikariTransactor[IO]] =
    val sqlite     = new SQLiteConfig()
    sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL)
    sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL)
    sqlite.setBusyTimeout(5_000)
    sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
    val dataSource = new SQLiteDataSource(sqlite)
    dataSource.setUrl(s"jdbc:sqlite:${database.toAbsolutePath}")
    val hikari     = new HikariConfig()
    hikari.setDataSource(dataSource)
    hikari.setMaximumPoolSize(1)
    hikari.setMinimumIdle(1)
    hikari.setAutoCommit(true)
    HikariTransactor.fromHikariConfig[IO](hikari)

  private def deleteTree(directory: Path): Unit =
    if Files.exists(directory) then
      Using.resource(Files.walk(directory)) { stream =>
        stream.sorted(Comparator.reverseOrder[Path]()).forEach(path => Files.delete(path))
      }
