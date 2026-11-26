package yochat.server

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.X25519Private

/** A2's long-lived fanout private key. It is separate from identity.bin and is only written after the auth code is accepted. */
object FanoutKeyFile:
  val FileName: String = "fanout.key"

  def path(dataDir: Path): Path = dataDir.resolve(FileName)

  def read(dataDir: Path): IO[Option[X25519Private]] =
    IO.blocking {
      val target = path(dataDir)
      if !Files.isRegularFile(target) then None
      else
        val bytes = Files.readAllBytes(target)
        if bytes.length != 32 then None
        else X25519Private.from(ByteVector.view(bytes)).toOption
    }

  def write(dataDir: Path, key: X25519Private): IO[Unit] =
    IO.blocking {
      Files.createDirectories(dataDir)
      val target = path(dataDir)
      Files.write(target, key.bytes.toArray)
      try Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
      catch case _: UnsupportedOperationException => ()
      ()
    }
