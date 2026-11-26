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
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.app.ContactConfirmation
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

  test("device pairing endpoints create, list, and revoke a companion") {
    val directory    = Files.createTempDirectory("yochat-http-devices-")
    val companionKey = ExecutorTestSupport.edPair()
    try
      val program = for
        relay  <- RelayServer.start(0)
        server <- HttpServerFixture.start(directory, relay.port)
      yield (relay, server)
      program.use { case (_, server) =>
        val base = s"http://127.0.0.1:${server.port}"
        for
          _       <- postJson(s"$base/api/unlock", ujson.Obj("passphrase" -> "correct horse battery staple"))
          invite  <- postJson(s"$base/api/devices/pairing", ujson.Obj("ttlHours" -> 1))
          request <- DevicePairing.request(
                       invite("code").str,
                       companionKey.privateKey,
                       companionKey.publicKey,
                       "phone"
                     )
          requestCode = request.fold(error => throw new AssertionError(error), value => value._1)
          inspected <- postJson(s"$base/api/devices/pairing/inspect", ujson.Obj("requestCode" -> requestCode))
          _          = assert(inspected("confirmation").str.nonEmpty)
          confirm   <- postJson(
                         s"$base/api/devices/pairing/confirm",
                         ujson.Obj("requestCode" -> requestCode, "confirmation" -> inspected("confirmation").str)
                       )
          _          = assert(confirm("authCode").str.nonEmpty)
          devices <- getJson(s"$base/api/devices")
          _       = assertEquals(devices("devices").arr.size, 1)
          _       = assertEquals(devices("devices").arr.head("role").str, "companion")
          _       <- postJson(s"$base/api/devices/${companionKey.publicKey.bytes.toHex}/revoke", ujson.Obj())
          after   <- getJson(s"$base/api/devices")
          _       = assert(after("devices").arr.head("revokedAt") != ujson.Null)
        yield ()
      }.unsafeRunSync()
    finally deleteTree(directory)
  }

  test("idle auto-lock gates state and message reads without closing the service") {
    val directory = Files.createTempDirectory("yochat-http-idle-")
    try
      val program = for
        relay <- RelayServer.start(0)
        server <- HttpServerFixture.start(directory, relay.port, idleTimeoutMs = 100L)
      yield (relay, server)
      program.use { case (_, server) =>
        val base = s"http://127.0.0.1:${server.port}"
        for
          _       <- postJson(s"$base/api/unlock", ujson.Obj("passphrase" -> "correct horse battery staple"))
          _       <- IO.sleep(350.millis)
          session <- getJson(s"$base/api/session")
          _        = assertEquals(session("unlocked").bool, false)
          _        = assert(!session.obj.contains("contacts"))
          _        = assert(!session.obj.contains("conversations"))
          _        = assert(!session.obj.contains("identity"))
          state   <- getStatus(s"$base/api/state")
          _        = assertEquals(state, 401)
          messages <- getStatus(s"$base/api/conversations/${"00" * 32}/messages")
          _        = assertEquals(messages, 401)
          invite   <- postStatus(
                        s"$base/api/invitations",
                        ujson.Obj("channelLabel" -> "test", "channelAccount" -> "@alice", "note" -> "locked", "ttlHours" -> 24)
                      )
          _        = assertEquals(invite, 401)
          after    <- getJson(s"$base/api/session")
          _        = assertEquals(after("unlocked").bool, false)
          _        = assert(!after.obj.contains("invitations"))
        yield ()
      }.unsafeRunSync()
    finally deleteTree(directory)
  }

  test("M6: a second invitation accept fails closed without marking AccountMismatch") {
    val aliceDir = Files.createTempDirectory("yochat-http-accept-a-")
    val bobDir = Files.createTempDirectory("yochat-http-accept-b-")
    try
      val program = for
        relay  <- RelayServer.start(0)
        alice  <- HttpServerFixture.start(aliceDir, relay.port)
        bob    <- HttpServerFixture.start(bobDir, relay.port, idleTimeoutMs = 5_000L)
      yield (relay, alice, bob)
      program.use { case (_, alice, bob) =>
        for
          _          <- alice.service.unlock("correct horse battery staple".toCharArray)
          _          <- bob.service.unlock("correct horse battery staple".toCharArray)
          created    <- alice.service.createInvitation("test", "@alice", None, 60_000L, "hello")
          text        = created.toOption.map(_("text").str).getOrElse(fail(created.left.map(_.code).toString))
          received   <- bob.service.receiveInvitation(text, None)
          invitation <- received match
                          case Right(value) => IO.pure(ByteVector.fromValidHex(value("id").str))
                          case Left(error)  => IO.raiseError(new AssertionError(error.code))
          results    <- IO.both(
                          bob.service.acceptInvitation(invitation, "test", "@bob", "reply"),
                          bob.service.acceptInvitation(invitation, "test", "@bob", "reply")
                        )
          state      <- bob.service.state
          contact    <- state.toOption.flatMap(_.contacts.keys.headOption) match
                          case Some(value) => IO.pure(value)
                          case None        => IO.raiseError(new AssertionError("missing contact"))
          _          <- IO.sleep(5_200.millis)
          safety     <- postStatus(s"http://127.0.0.1:${bob.port}/api/contacts/${contact.bytes.toHex}/safety", ujson.Obj())
        yield
          val (first, second) = results
          assertEquals(Vector(first.isRight, second.isRight).count(identity), 1)
          assertEquals(state.toOption.flatMap(_.contacts.values.headOption).map(_.confirmation), Some(ContactConfirmation.Confirmed))
          assertEquals(safety, 401)
      }.unsafeRunSync()
    finally
      deleteTree(aliceDir)
      deleteTree(bobDir)
  }

  private def getStatus(url: String): IO[Int] =
    IO.blocking {
      val request  = HttpRequest.newBuilder(URI.create(url)).GET().build()
      val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode()
    }

  private def postStatus(url: String, body: Value): IO[Int] =
    IO.blocking {
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
        .build()
      HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
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

  final case class Running(binding: ServerBinding, service: AppService):
    def port: Int = binding.localAddress.getPort

  def start(directory: Path, relayPort: Int, idleTimeoutMs: Long = 60_000L): Resource[IO, Running] =
    Resource.make(IO(ActorSystem("yochat-http-test")))(value => IO.fromFuture(IO(value.terminate())).void).flatMap { system =>
      given ActorSystem = system
      given Materializer = Materializer(system)
      for
        hub     <- Resource.eval(IO(new EventHub))
        config   = ServiceConfig(0, directory, "127.0.0.1", relayPort, idleTimeoutMs)
        service <- Resource.make(AppService.create(config, hub))(_.close)
        binding <- Resource.make(HttpApi.start(service, hub, config))(value => IO.fromFuture(IO(value.unbind())).void)
      yield Running(binding, service)
    }
