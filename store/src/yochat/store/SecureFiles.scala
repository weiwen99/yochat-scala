package yochat.store

import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}

import scala.jdk.CollectionConverters.*

import cats.effect.IO

/** Filesystem boundary for instance keys. Data is written through a same-directory temporary file and owner-only permissions. */
private[store] object SecureFiles:

  private val FilePermissions: Set[PosixFilePermission]      = PosixFilePermissions.fromString("rw-------").asScala.toSet
  private val DirectoryPermissions: Set[PosixFilePermission] = PosixFilePermissions.fromString("rwx------").asScala.toSet

  def ensureDirectory(path: Path): IO[Unit] =
    IO.blocking {
      Files.createDirectories(path)
      setPermissions(path, DirectoryPermissions)
    }

  def writePrivate(path: Path, bytes: Array[Byte]): IO[Unit] =
    IO.blocking {
      val parent    = path.getParent
      Files.createDirectories(parent)
      setPermissions(parent, DirectoryPermissions)
      val temporary = Files.createTempFile(parent, ".yochat-", ".part")
      try
        Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        setPermissions(temporary, FilePermissions)
        move(temporary, path)
      finally
        Files.deleteIfExists(temporary)
        ()
    }

  def read(path: Path): IO[Array[Byte]] =
    IO.blocking(Files.readAllBytes(path))

  def exists(path: Path): Boolean =
    Files.isRegularFile(path)

  private def move(source: Path, target: Path): Unit =
    try Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch case _: AtomicMoveNotSupportedException => Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)

  private def setPermissions(path: Path, permissions: Set[PosixFilePermission]): Unit =
    try Files.setPosixFilePermissions(path, permissions.asJava)
    catch case _: UnsupportedOperationException => ()
