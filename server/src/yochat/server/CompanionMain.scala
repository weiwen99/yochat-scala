package yochat.server

import java.nio.file.Path

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

/** A2-only process. It serves the read-only projection and keeps the device-sync relay connected. */
object CompanionMain:

  final case class Config(
    service: ServiceConfig,
    userPub: Ed25519Public,
    passphrase: Array[Char]
  )

  def parse(args: List[String]): Option[Either[String, Config]] =
    if !args.contains("--companion-user") then None
    else
      def loop(
        remaining: List[String],
        port: Int,
        dataDir: Path,
        relay: String,
        user: Option[String],
        passphrase: Option[String]
      ): Either[String, Config] =
        remaining match
          case Nil                                 =>
            for
              endpoint <- relayEndpoint(relay)
              userHex  <- user.toRight("--companion-user is required")
              public   <- parsePublic(userHex)
              secret   <- passphrase.toRight("--passphrase is required")
            yield Config(ServiceConfig(port, dataDir, endpoint._1, endpoint._2, 0L), public, secret.toCharArray)
          case "--port" :: value :: tail           =>
            value.toIntOption.map(loop(tail, _, dataDir, relay, user, passphrase)).getOrElse(Left("--port needs an integer"))
          case "--data-dir" :: value :: tail       => loop(tail, port, Path.of(value), relay, user, passphrase)
          case "--relay" :: value :: tail          => loop(tail, port, dataDir, value, user, passphrase)
          case "--companion-user" :: value :: tail => loop(tail, port, dataDir, relay, Some(value), passphrase)
          case "--passphrase" :: value :: tail     => loop(tail, port, dataDir, relay, user, Some(value))
          case unknown :: _                        => Left(s"unknown companion argument: $unknown")
      Some(loop(args, 8787, Path.of(System.getProperty("user.home"), ".yochat"), "127.0.0.1:7654", None, None))

  def serve(config: Config): IO[ExitCode] =
    Resource
      .make(IO(ActorSystem("yochat-companion"))) { system =>
        IO.fromFuture(IO(system.terminate())).void
      }
      .use { system =>
        given ActorSystem  = system
        given Materializer = Materializer(system)
        given IORuntime    = IORuntime.global
        val resources      = for
          service <- CompanionService.create(config.service, config.userPub, config.passphrase)
          binding <- Resource.make(CompanionHttpApi.start(service, config.service))(value => IO.fromFuture(IO(value.unbind())).void)
        yield (service, binding)
        resources.use { case (_, binding) =>
          IO.println(s"YoChat read-only web: http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}") *>
            IO.println("A2 is read-only; messages appear only after A1 sends a snapshot.") *>
            IO.never
        }
      }

  private def parsePublic(value: String): Either[String, Ed25519Public] =
    ByteVector.fromHex(value).flatMap(Ed25519Public.from(_).toOption).toRight("--companion-user must be 32-byte hex")

  private def relayEndpoint(value: String): Either[String, (String, Int)] =
    val index = value.lastIndexOf(':')
    if index <= 0 || index == value.length - 1 then Left("--relay must be host:port")
    else
      value.drop(index + 1).toIntOption match
        case Some(port) if port >= 1 && port <= 65_535 => Right((value.take(index), port))
        case _                                         => Left("--relay port must be 1..65535")
