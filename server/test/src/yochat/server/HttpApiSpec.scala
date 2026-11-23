package yochat.server

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.stream.Materializer
import ujson.Value

import yochat.transport.RelayServer

class HttpApiSpec extends FunSuite:

  override def munitTimeout: Duration = 90.seconds

  test("local service unlocks, serves state, creates an invitation, and locks") {
    val directory = Files.createTempDirectory("yochat-http-")
    try
      val program = for
        relay <- RelayServer.start(0)
        server <- HttpServerFixture.start(directory, relay.port)
      yield (relay, server)
      program.use { case (_, server) =>
        val base = s"http://127.0.0.1:${server.port}"
        for
          locked <- getJson(s"$base/api/session")
          _       = assertEquals(locked("unlocked").bool, false)
          _       = assertEquals(locked("instanceExists").bool, false)
          opened <- postJson(s"$base/api/unlock", ujson.Obj("passphrase" -> "correct horse battery staple"))
          _       = assertEquals(opened("unlocked").bool, true)
          state  <- getJson(s"$base/api/session")
          _       = assertEquals(state("identity").obj.nonEmpty, true)
          created <- postJson(
                       s"$base/api/invitations",
                       ujson.Obj(
                         "channelLabel" -> "test",
                         "channelAccount" -> "@alice",
                         "note" -> "hello",
                         "ttlHours" -> 24
                       )
                     )
          _       = assert(created("text").str.nonEmpty)
          after   <- getJson(s"$base/api/session")
          _       = assertEquals(after("invitations").arr.size, 1)
          locked  <- postJson(s"$base/api/lock", ujson.Obj())
          _       = assertEquals(locked("locked").bool, true)
          finalState <- getJson(s"$base/api/session")
          _          = assertEquals(finalState("unlocked").bool, false)
          _          = assertEquals(finalState("instanceExists").bool, true)
        yield ()
      }.unsafeRunSync()
    finally deleteTree(directory)
  }

  private def getJson(url: String): IO[Value] =
    request("GET", url, None)

  private def postJson(url: String, body: Value): IO[Value] =
    request("POST", url, Some(ujson.write(body)))

  private def request(method: String, url: String, body: Option[String]): IO[Value] =
    IO.blocking {
      val builder = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
      body match
        case Some(value) => builder.method(method, HttpRequest.BodyPublishers.ofString(value))
        case None        => builder.method(method, HttpRequest.BodyPublishers.noBody())
      val response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
      assertEquals(response.statusCode(), 200, response.body())
      ujson.read(response.body())
    }

  private def deleteTree(directory: Path): Unit =
    ExecutorTestSupport.deleteTree(directory)

private object HttpServerFixture:

  final case class Running(binding: ServerBinding):
    def port: Int = binding.localAddress.getPort

  def start(directory: Path, relayPort: Int): Resource[IO, Running] =
    Resource.make(IO(ActorSystem("yochat-http-test")))(value => IO.fromFuture(IO(value.terminate())).void).flatMap { system =>
      given ActorSystem = system
      given Materializer = Materializer(system)
      for
        hub     <- Resource.eval(IO(new EventHub))
        config   = ServiceConfig(0, directory, "127.0.0.1", relayPort, 60_000L)
        service <- Resource.make(AppService.create(config, hub))(_.close)
        binding <- Resource.make(HttpApi.start(service, hub, config))(value => IO.fromFuture(IO(value.unbind())).void)
      yield Running(binding)
    }
