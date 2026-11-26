package yochat.server

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.ActorSystem
import scodec.bits.ByteVector

import yochat.core.Ed25519Public
import yochat.store.{KeySource, Store, StoreConfig}

/** A2 browser bootstrap: open a companion store, show the request/confirmation values, accept the A1 auth code, then stop. The normal read-only
  * process is started with `--companion-user` after this command succeeds.
  */
object CompanionPairingMain:

  final case class Config(
    service: ServiceConfig,
    userPub: Ed25519Public,
    passphrase: Array[Char],
    inviteFile: Path,
    name: String
  )

  def parse(args: List[String]): Option[Either[String, Config]] =
    if !args.contains("--pair-only") then None
    else
      def loop(
        remaining: List[String],
        port: Int,
        dataDir: Path,
        user: Option[String],
        passphrase: Option[String],
        invite: Option[Path],
        name: String
      ): Either[String, Config] =
        remaining match
          case Nil                                 =>
            for
              userHex <- user.toRight("--companion-user is required")
              public  <- parsePublic(userHex)
              secret  <- passphrase.toRight("--passphrase is required")
              invite  <- invite.toRight("--invite is required")
            yield Config(ServiceConfig(port, dataDir, "127.0.0.1", 7654, 0L), public, secret.toCharArray, invite, name)
          case "--pair-only" :: tail               => loop(tail, port, dataDir, user, passphrase, invite, name)
          case "--port" :: value :: tail           =>
            value.toIntOption.map(loop(tail, _, dataDir, user, passphrase, invite, name)).getOrElse(Left("--port needs an integer"))
          case "--data-dir" :: value :: tail       => loop(tail, port, Path.of(value), user, passphrase, invite, name)
          case "--companion-user" :: value :: tail => loop(tail, port, dataDir, Some(value), passphrase, invite, name)
          case "--passphrase" :: value :: tail     => loop(tail, port, dataDir, user, Some(value), invite, name)
          case "--invite" :: value :: tail         => loop(tail, port, dataDir, user, passphrase, Some(Path.of(value)), name)
          case "--name" :: value :: tail           => loop(tail, port, dataDir, user, passphrase, invite, value)
          case unknown :: _                        => Left(s"unknown pairing argument: $unknown")
      Some(loop(args, 8787, Path.of(System.getProperty("user.home"), ".yochat"), None, None, None, "companion"))

  def serve(config: Config): IO[ExitCode] =
    Resource
      .make(IO(ActorSystem("yochat-pairing")))(system => IO.fromFuture(IO(system.terminate())).void)
      .use { system =>
        given ActorSystem = system
        given IORuntime   = IORuntime.global
        val resources     = for
          storeResult <- Store.openCompanion(StoreConfig.default(config.service.dataDir, KeySource.KeyFile(config.passphrase)), config.userPub)
          store       <- storeResult match
                           case Left(error)  => Resource.eval(IO.raiseError(new IllegalStateException(error.code)))
                           case Right(value) => Resource.pure(value)
          invite      <- Resource.eval(IO.blocking(java.nio.file.Files.readString(config.inviteFile, StandardCharsets.UTF_8)))
          service     <- Resource.eval(CompanionPairingService.create(store, config.service.dataDir, invite, config.name))
          binding     <- Resource.make(CompanionPairingHttpApi.start(service, config.service))(value => IO.fromFuture(IO(value.unbind())).void)
        yield (service, binding)
        resources.use { case (service, binding) =>
          IO.println(s"YoChat pairing web: http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}") *>
            IO.println("Open the page, compare the confirmation code with A1, and paste the auth code.") *>
            service.awaitPaired *>
            IO.println("Pairing complete; start the read-only process with --companion-user.").as(ExitCode.Success)
        }
      }

  private def parsePublic(value: String): Either[String, Ed25519Public] =
    ByteVector.fromHex(value).flatMap(Ed25519Public.from(_).toOption).toRight("--companion-user must be 32-byte hex")
