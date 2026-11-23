package yochat.server.e2e

import java.net.{InetAddress, ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ujson.Value

import yochat.transport.RelayServer

final case class HttpResult(status: Int, body: String):
  def ok: Boolean = status >= 200 && status < 300

  def json: Value =
    try ujson.read(body)
    catch case error: Exception => throw new AssertionError(s"invalid JSON (${error.getClass.getSimpleName}): $body")

final class Http:
  private val client =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  def get(url: String): HttpResult = send("GET", url, None)

  def post(url: String, body: Value): HttpResult = send("POST", url, Some(ujson.write(body)))

  def postEmpty(url: String): HttpResult = send("POST", url, Some("{}"))

  private def send(method: String, url: String, body: Option[String]): HttpResult =
    try
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
      val request = body match
        case Some(value) => builder.method(method, HttpRequest.BodyPublishers.ofString(value)).build()
        case None        => builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      HttpResult(response.statusCode(), response.body())
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        HttpResult(0, "interrupted")
      case error: Exception        => HttpResult(0, s"${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}")

object E2E:
  val Passphrase: String = "correct horse battery staple"

  def freePort(): Int =
    val socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    try socket.getLocalPort
    finally socket.close()

  def await[A](label: String, timeout: Duration, interval: Duration = Duration.ofMillis(100))(probe: => Option[A]): A =
    val deadline = System.nanoTime() + timeout.toNanos
    var result   = probe
    while result.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(interval.toMillis)
      result = probe
    result match
      case Some(value) => value
      case None        => throw new AssertionError(s"timeout waiting for $label")

  def awaitTrue(label: String, timeout: Duration, interval: Duration = Duration.ofMillis(100))(probe: => Boolean): Unit =
    await(label, timeout, interval)(if probe then Some(()) else None)

  def deleteTree(directory: Path): Unit =
    if Files.exists(directory) then
      Using.resource(Files.walk(directory)) { stream =>
        stream.sorted(Comparator.reverseOrder[Path]()).forEach(path => Files.delete(path))
      }

  def longValue(value: Value): Long =
    value match
      case ujson.Num(number) => number.toLong
      case ujson.Str(text)   => text.toLong
      case other             => throw new AssertionError(s"expected integer, got $other")

  def messageIds(value: Value): Vector[(Long, String, String, String)] =
    value.arr.toVector.map { message =>
      (
        longValue(message("seq")),
        message("author").str,
        message("state").str,
        message("body").str
      )
    }

  def contactUserIds(value: Value): Vector[String] =
    value("contacts").arr.toVector.map(_.obj("userPub").str)

  def conversationIds(value: Value): Vector[String] =
    value("conversations").arr.toVector.map(_.obj("id").str)

  def conversationIdFor(value: Value, peerUserPub: String): String =
    value("conversations").arr.toVector
      .find(_.obj("peerUserPub").str == peerUserPub)
      .map(_.obj("id").str)
      .getOrElse(throw new AssertionError(s"conversation for $peerUserPub not found"))

final class ServiceProcess(
  val name: String,
  val port: Int,
  val dataDir: Path,
  val relayPort: Int,
  val log: Path
):
  private val http = new Http
  private val processRef = new AtomicReference[Option[Process]](None)

  def base: String = s"http://127.0.0.1:$port"

  def start(expectExisting: Boolean = false): Unit =
    if processRef.get().isDefined then throw new AssertionError(s"$name already started")
    Files.createDirectories(dataDir)
    val java       = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val classpath  = System.getProperty("java.class.path")
    val command    =
      List(
        java,
        "-cp",
        classpath,
        "yochat.server.Main",
        "--port",
        port.toString,
        "--data-dir",
        dataDir.toString,
        "--relay",
        s"127.0.0.1:$relayPort",
        "--idle-timeout-minutes",
        "60"
      )
    val builder = new ProcessBuilder(command*)
    builder.redirectErrorStream(true)
    builder.redirectOutput(log.toFile)
    val process = builder.start()
    processRef.set(Some(process))
    E2E.awaitTrue(s"$name process bind", Duration.ofSeconds(20)) {
      process.isAlive && Files.exists(log) && Files.readAllLines(log).asScala.exists(_.contains(s"YoChat web: http://127.0.0.1:$port"))
    }
    E2E.awaitTrue(s"$name HTTP session", Duration.ofSeconds(20))(sessionStatus.status == 200)
    val current = session
    if current("unlocked").bool then throw new AssertionError(s"$name started unlocked")
    if expectExisting && !current("instanceExists").bool then throw new AssertionError(s"$name instance missing after restart")

  def stop(assertLocked: Boolean = true): Unit =
    processRef.get().foreach { process =>
      try
        if process.isAlive then
          if assertLocked then
            val locked = lock()
            if !locked then throw new AssertionError(s"$name failed to lock before stop: ${summary}")
        else if assertLocked then
          throw new AssertionError(s"$name exited before lock; ${summary}")
      catch
        case NonFatal(error) =>
          terminate(process)
          processRef.set(None)
          throw error
      terminate(process)
      processRef.set(None)
    }

  private def terminate(process: Process): Unit =
    if process.isAlive then
      process.destroy()
      if !process.waitFor(10, TimeUnit.SECONDS) then
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
    if process.isAlive then throw new AssertionError(s"$name did not stop")

  def restart(): Unit =
    val expectedUser   = identityUser
    val expectedDevice = identityDevice
    stop(assertLocked = true)
    start(expectExisting = true)
    unlock()
    if identityUser != expectedUser || identityDevice != expectedDevice then
      throw new AssertionError(s"$name identity changed across restart")
    awaitOnline(online = true)

  def tryUnlock(passphrase: String): HttpResult =
    http.post(s"$base/api/unlock", ujson.Obj("passphrase" -> passphrase))

  def unlock(): Unit =
    val response = tryUnlock(E2E.Passphrase)
    requireStatus(s"$name unlock", response, 200)
    val state = session
    if !state("unlocked").bool then throw new AssertionError(s"$name did not unlock: $state")

  def lock(): Boolean =
    val response = http.postEmpty(s"$base/api/lock")
    response.status == 200

  def sessionStatus: HttpResult = http.get(s"$base/api/session")

  def session: Value = getJson("/api/session")

  def state: Value = getJson("/api/state")

  def tryState: HttpResult = http.get(s"$base/api/state")

  def tryMessages(conversationId: String, limit: Int = 200): HttpResult =
    http.get(s"$base/api/conversations/$conversationId/messages?limit=$limit")

  def activeSessionIds(conversationId: String): Vector[String] =
    state("sessions").arr.toVector
      .filter(session => session.obj("conversationId").str == conversationId && session.obj("phase").str == "active")
      .map(_.obj("id").str)

  def activeSessionId(conversationId: String): String =
    activeSessionIds(conversationId).headOption.getOrElse(
      throw new AssertionError(s"$name missing active session for $conversationId")
    )

  def awaitActiveSessionChanged(conversationId: String, previous: String, timeout: Duration = Duration.ofSeconds(30)): String =
    E2E.await(s"$name active session changed", timeout) {
      activeSessionIds(conversationId).find(_ != previous)
    }

  def setAlias(userPub: String, alias: String): Unit =
    val response = http.post(
      s"$base/api/contacts/$userPub/alias",
      ujson.Obj("alias" -> alias)
    )
    requireStatus(s"$name set alias", response, 200)

  def identityUser: String = session("identity")("userId").str

  def identityDevice: String = session("identity")("deviceId").str

  def contacts: Vector[String] = E2E.contactUserIds(state)

  def conversations: Vector[String] = E2E.conversationIds(state)

  def conversationId(peerUserPub: String): String = E2E.conversationIdFor(state, peerUserPub)

  def messages(conversationId: String, limit: Int = 200): Vector[(Long, String, String, String)] =
    val response = http.get(s"$base/api/conversations/$conversationId/messages?limit=$limit")
    requireStatus(s"$name messages", response, 200)
    E2E.messageIds(response.json)

  def createInvitation(expectedAccount: Option[String]): (String, String) =
    val response = http.post(
      s"$base/api/invitations",
      ujson.Obj(
        "channelLabel"    -> "test",
        "channelAccount"  -> s"@$name",
        "expectedAccount" -> expectedAccount.map(value => ujson.Str(value)).getOrElse(ujson.Null),
        "ttlHours"        -> 24,
        "note"            -> s"invite-$name"
      )
    )
    requireStatus(s"$name create invitation", response, 200)
    val json = response.json
    (json("id").str, json("text").str)

  def tryReceiveInvitation(text: String, expectedAccount: Option[String]): HttpResult =
    http.post(
      s"$base/api/invitations/receive",
      ujson.Obj(
        "text"            -> text,
        "expectedAccount" -> expectedAccount.map(value => ujson.Str(value)).getOrElse(ujson.Null)
      )
    )

  def receiveInvitation(text: String, expectedAccount: Option[String]): String =
    val response = tryReceiveInvitation(text, expectedAccount)
    requireStatus(s"$name receive invitation", response, 200)
    response.json("id").str

  def tryAcceptInvitation(invitationId: String): HttpResult =
    http.post(
      s"$base/api/invitations/$invitationId/accept",
      ujson.Obj(
        "channelLabel"   -> "test",
        "channelAccount" -> s"@$name",
        "note"           -> s"reply-$name"
      )
    )

  def acceptInvitation(invitationId: String): String =
    val response = tryAcceptInvitation(invitationId)
    requireStatus(s"$name accept invitation", response, 200)
    response.json("replyText").str

  def verifySafety(userPub: String): Unit =
    val response = http.postEmpty(s"$base/api/contacts/$userPub/safety")
    requireStatus(s"$name verify safety", response, 200)

  def trySendMessage(conversationId: String, body: String): HttpResult =
    http.post(s"$base/api/conversations/$conversationId/messages", ujson.Obj("body" -> body))

  def sendMessage(conversationId: String, body: String): Long =
    val response = trySendMessage(conversationId, body)
    requireStatus(s"$name send message", response, 200)
    E2E.longValue(response.json("messageId"))

  def awaitContacts(peer: String, timeout: Duration = Duration.ofSeconds(20)): Value =
    E2E.await(s"$name contact $peer", timeout) {
      val current = state
      if E2E.contactUserIds(current).contains(peer) then Some(current) else None
    }

  def awaitSafety(userPub: String, verified: Boolean, timeout: Duration = Duration.ofSeconds(20)): Unit =
    E2E.awaitTrue(s"$name safety $userPub=$verified", timeout) {
      state("contacts").arr.toVector
        .find(_.obj("userPub").str == userPub)
        .exists(_.obj("safetyVerified").bool == verified)
    }

  def awaitOnline(online: Boolean, timeout: Duration = Duration.ofSeconds(20)): Unit =
    E2E.awaitTrue(s"$name relay online=$online", timeout) {
      val current = state
      current("relay")("online").bool == online
    }

  def awaitMessage(
    conversationId: String,
    predicate: Vector[(Long, String, String, String)] => Boolean,
    label: String,
    timeout: Duration = Duration.ofSeconds(20)
  ): Vector[(Long, String, String, String)] =
    E2E.await(s"$name $label", timeout) {
      val current = messages(conversationId)
      if predicate(current) then Some(current) else None
    }

  def logText: String =
    if Files.exists(log) then Files.readAllLines(log).asScala.mkString("\n") else ""

  def logTail(lines: Int = 200): String =
    if Files.exists(log) then
      val all = Files.readAllLines(log).asScala.toVector
      all.takeRight(lines).mkString("\n")
    else ""

  def summary: String =
    val current = stateOption
    val relay   = current.flatMap(value => field(value, "relay", "online")).getOrElse(false)
    val contacts = current.map(value => value("contacts").arr.size).getOrElse(-1)
    val conversations = current.map(value => value("conversations").arr.size).getOrElse(-1)
    val fatal   = current.flatMap(value => value.obj.get("fatal")).collect { case ujson.Str(value) => value }.getOrElse("")
    s"$name port=$port relayOnline=$relay contacts=$contacts conversations=$conversations fatal=$fatal"

  def diagnostics: String =
    s"$summary\ndata=$dataDir\nlog=$log"

  private def stateOption: Option[Value] =
    val response = http.get(s"$base/api/state")
    if response.status == 200 then
      val json = response.json
      if json.obj.contains("contacts") then Some(json) else None
    else None

  private def field(value: Value, parent: String, child: String): Option[Boolean] =
    value.obj
      .get(parent)
      .flatMap(_.obj.get(child))
      .collect { case ujson.Bool(value) => value }

  private def getJson(path: String): Value =
    val response = http.get(s"$base$path")
    requireStatus(s"$name GET $path", response, 200)
    response.json

  private def requireStatus(label: String, response: HttpResult, expected: Int): Unit =
    if response.status != expected then
      throw new AssertionError(s"$label returned ${response.status}: ${response.body}\n${logTail()}")

final class RelayHandle extends RelayControl:
  private val current = new AtomicReference[Option[(RelayServer, IO[Unit])]](None)
  private val portRef = new AtomicInteger(0)

  def port: Int = portRef.get

  def start(requestedPort: Int): Int =
    if current.get().isDefined then throw new AssertionError("relay already started")
    val (server, release) = RelayServer.start(requestedPort).allocated.unsafeRunSync()
    current.set(Some((server, release)))
    portRef.set(server.port)
    server.port

  def stop(): Unit =
    current.getAndSet(None).foreach { case (_, release) => release.unsafeRunSync() }

final case class Pair(a: ServiceProcess, b: ServiceProcess, relay: RelayControl):
  def close(): Unit =
    try a.stop(assertLocked = false)
    catch case NonFatal(_) => ()
    try b.stop(assertLocked = false)
    catch case NonFatal(_) => ()
    try relay.stop()
    catch case NonFatal(_) => ()

  def diagnostics: String = s"${a.diagnostics}\n\n${b.diagnostics}"

object E2EEnvironment:
  def withPair[A](body: Pair => A): A =
    val relay = new RelayHandle
    relay.start(0)
    try withProcesses(relay)(body)
    finally relay.stop()

  def withFaultPair[A](body: (Pair, FaultRelay) => A): A =
    val fault = FaultRelay.start()
    try withProcesses(fault)(pair => body(pair, fault))
    finally fault.stop()

  private def withProcesses[A](relay: RelayControl)(body: Pair => A): A =
    val dirA = Files.createTempDirectory("yochat-e2e-a-")
    val dirB = Files.createTempDirectory("yochat-e2e-b-")
    val a    = new ServiceProcess("A", E2E.freePort(), dirA, relay.port, dirA.resolve("server.log"))
    val b    = new ServiceProcess("B", E2E.freePort(), dirB, relay.port, dirB.resolve("server.log"))
    val pair = Pair(a, b, relay)
    try
      a.start()
      b.start()
      a.unlock()
      b.unlock()
      a.awaitOnline(online = true)
      b.awaitOnline(online = true)
      body(pair)
    catch
      case NonFatal(error) =>
        throw new AssertionError(s"pair failed: ${error.getMessage}\n${pair.diagnostics}", error)
    finally
      pair.close()
      E2E.deleteTree(dirA)
      E2E.deleteTree(dirB)

  def handshake(pair: Pair): Handshake =
    val aUser = pair.a.identityUser
    val bUser = pair.b.identityUser
    val (invitationId, invitationText) = pair.a.createInvitation(Some("@B"))
    val receivedId                     = pair.b.receiveInvitation(invitationText, Some("@A"))
    if receivedId != invitationId then
      throw new AssertionError(s"invitation id mismatch: issued=$invitationId received=$receivedId")
    pair.b.acceptInvitation(invitationId)
    pair.a.awaitContacts(bUser)
    pair.b.awaitContacts(aUser)
    Handshake(aUser, bUser, pair.a.conversationId(bUser), pair.b.conversationId(aUser))

final case class Handshake(
  aUser: String,
  bUser: String,
  aConversation: String,
  bConversation: String
)
