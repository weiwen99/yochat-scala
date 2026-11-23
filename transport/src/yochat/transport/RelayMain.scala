package yochat.transport

import cats.effect.{ExitCode, IO, IOApp}

/** Development relay entry point. Production deployments should run the relay as a separate bounded service. */
object RelayMain extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(7654)
    RelayServer.start(port).use { server =>
      IO.println(s"Yochat relay listening on 127.0.0.1:${server.port}") *> IO.never
    }
