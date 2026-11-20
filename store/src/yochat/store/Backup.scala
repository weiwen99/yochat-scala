package yochat.store

import java.nio.file.Path

import cats.effect.IO
import org.typelevel.doobie.free.connection as FC

/** Consistent online copy: SQLite `VACUUM INTO` outside a transaction, then identity and key-source artifacts. */
private[store] object Backup:

  def create(runtime: StoreRuntime, target: Path): IO[Either[StorageError, Unit]] =
    val destination = StorePaths.under(target)
    if SecureFiles.exists(destination.database) then IO.pure(Left(StorageError.WriteConflict))
    else
      SecureFiles.ensureDirectory(target).attempt.flatMap {
        case Left(_)  => IO.pure(Left(StorageError.StorageFailure))
        case Right(_) =>
          runtime.raw(FC.raw(connection => vacuum(connection, destination.database))).flatMap {
            case Left(error) => IO.pure(Left(error))
            case Right(_)    =>
              copyArtifacts(runtime.paths, destination).attempt.map {
                case Right(_) => Right(())
                case Left(_)  => Left(StorageError.StorageFailure)
              }
          }
      }

  private def vacuum(connection: java.sql.Connection, database: Path): Unit =
    val statement = connection.createStatement()
    try
      statement.execute(s"VACUUM INTO '${database.toAbsolutePath.toString.replace("'", "''")}'")
      ()
    finally statement.close()

  private def copyArtifacts(source: StorePaths, target: StorePaths): IO[Unit] =
    val keyFile =
      if SecureFiles.exists(source.keyFile) then
        SecureFiles.read(source.keyFile).flatMap(bytes => SecureFiles.writePrivate(target.keyFile, bytes))
      else IO.unit
    for
      identity <- SecureFiles.read(source.identityFile)
      _        <- SecureFiles.writePrivate(target.identityFile, identity)
      _        <- keyFile
    yield ()
