package yochat.server

import java.nio.file.Path

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer

object Main extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    PairingTool.run(args).flatMap {
      case Some(code) => IO.pure(code)
      case None       =>
        CompanionPairingMain.parse(args) match
          case Some(Left(message)) => IO.println(s"yochat: $message").as(ExitCode.Error)
          case Some(Right(config)) => CompanionPairingMain.serve(config)
          case None                =>
            CompanionMain.parse(args) match
              case None                => serve(args)
              case Some(Left(message)) => IO.println(s"yochat: $message").as(ExitCode.Error)
              case Some(Right(config)) => CompanionMain.serve(config)
    }

  private def serve(args: List[String]): IO[ExitCode] =
    Config.parse(args) match
      case Left(message) => IO.println(s"yochat: $message") *> IO.pure(ExitCode.Error)
      case Right(config) =>
        Resource
          .make(IO(ActorSystem("yochat-server"))) { system =>
            IO.fromFuture(IO(system.terminate())).void
          }
          .use { system =>
            given ActorSystem  = system
            given Materializer = Materializer(system)
            given IORuntime    = IORuntime.global
            val server         = for
              hub     <- Resource.eval(IO(new EventHub))
              service <- Resource.make(AppService.create(config, hub))(_.close)
              binding <- Resource.make(HttpApi.start(service, hub, config))(value => IO.fromFuture(IO(value.unbind())).void)
            yield (service, binding)
            server.use { case (_, binding) =>
              IO.println(s"YoChat web: http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}") *>
                IO.println("Keep this process running; Electron or a browser can open the URL.") *>
                IO.never
            }
          }

private object Config:

  private val DefaultPort: Int         = 8787
  private val DefaultRelay: String     = "127.0.0.1:7654"
  private val DefaultIdleMinutes: Long = 24L * 60L

  def parse(args: List[String]): Either[String, ServiceConfig] =
    def loop(
      remaining: List[String],
      port: Int,
      dataDir: Path,
      relay: String,
      idleMinutes: Long
    ): Either[String, ServiceConfig] =
      remaining match
        case Nil                                       =>
          for
            endpoint <- parseRelay(relay)
            _        <- if port >= 1 && port <= 65_535 then Right(()) else Left("--port must be 1..65535")
            _        <- if idleMinutes >= 1L && idleMinutes <= 7L * 24L * 60L then Right(())
                        else Left("--idle-timeout-minutes must be 1..10080")
          yield ServiceConfig(port, dataDir, endpoint._1, endpoint._2, idleMinutes * 60_000L)
        case "--port" :: value :: tail                 =>
          value.toIntOption match
            case Some(parsed) => loop(tail, parsed, dataDir, relay, idleMinutes)
            case None         => Left("--port needs an integer")
        case "--data-dir" :: value :: tail             =>
          loop(tail, port, Path.of(value), relay, idleMinutes)
        case "--relay" :: value :: tail                =>
          loop(tail, port, dataDir, value, idleMinutes)
        case "--idle-timeout-minutes" :: value :: tail =>
          value.toLongOption match
            case Some(parsed) => loop(tail, port, dataDir, relay, parsed)
            case None         => Left("--idle-timeout-minutes needs an integer")
        case "--help" :: _                             =>
          Left("usage: yochat-server [--port 8787] [--data-dir ~/.yochat] [--relay host:port] [--idle-timeout-minutes 1440]")
        case unknown :: _                              =>
          Left(s"unknown argument: $unknown")

    val home = Path.of(System.getProperty("user.home"), ".yochat")
    loop(args, DefaultPort, home, DefaultRelay, DefaultIdleMinutes)

  private def parseRelay(value: String): Either[String, (String, Int)] =
    val index = value.lastIndexOf(':')
    if index <= 0 || index == value.length - 1 then Left("--relay must be host:port")
    else
      value.drop(index + 1).toIntOption match
        case Some(port) if port >= 1 && port <= 65_535 => Right((value.take(index), port))
        case _                                         => Left("--relay port must be 1..65535")
