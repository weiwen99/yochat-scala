package yochat.server.e2e

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import cats.effect.unsafe.implicits.global
import ujson.Value

import yochat.server.PairingTool

/** Fixed S2-A contract: old-history exclusion, 200 post-join messages, A2 restart catch-up, A1 restart, local revoke. */
object S2E2E:

  private val passphrase = E2E.Passphrase

  def main(args: Array[String]): Unit =
    val started = System.nanoTime()
    val evidence =
      try run()
      catch
        case NonFatal(error) =>
          println(s"S2E2E FAIL ${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}")
          System.exit(1)
          ""
    val elapsed = (System.nanoTime() - started) / 1_000_000L
    println(s"S2E2E PASS duration_ms=$elapsed evidence=$evidence")

  private def run(): String =
    E2EEnvironment.withPair { pair =>
      val a2Directory = Files.createTempDirectory("yochat-e2e-a2-")
      val a2          = new CompanionProcess("A2", a2Directory, E2E.freePort(), pair.relay.port)
      try
        val handshake = E2EEnvironment.handshake(pair)
        pair.a.verifySafety(handshake.bUser)
        pair.b.verifySafety(handshake.aUser)
        pair.a.awaitSafety(handshake.bUser, verified = true)
        pair.b.awaitSafety(handshake.aUser, verified = true)
        (1 to 50).foreach(index => pair.a.sendMessage(handshake.aConversation, s"old-$index"))

        val inviteResponse = new Http().post(s"${pair.a.base}/api/devices/pairing", ujson.Obj("ttlHours" -> 1))
        ensure(inviteResponse.ok, s"A1 pairing invite failed: ${inviteResponse.status} ${inviteResponse.body}")
        val invite     = inviteResponse.json("code").str
        val inviteFile = a2Directory.resolve("invite.txt")
        Files.writeString(inviteFile, invite, StandardCharsets.UTF_8)
        runPairing(pair.a, a2, handshake.aUser, inviteFile)
        a2.start()
        awaitCompanionContact(a2, handshake.bUser)
        val oldCount = a2.messages(handshake.aConversation).size
        ensure(oldCount == 0, s"A2 received pre-join messages: $oldCount")

        (1 to 100).foreach(index => pair.a.sendMessage(handshake.aConversation, s"a-post-$index"))
        (1 to 100).foreach(index => pair.b.sendMessage(handshake.bConversation, s"b-post-$index"))
        awaitCompanionMessages(a2, handshake.aConversation, 200)

        a2.stop()
        (1 to 10).foreach(index => pair.a.sendMessage(handshake.aConversation, s"offline-a-$index"))
        (1 to 10).foreach(index => pair.b.sendMessage(handshake.bConversation, s"offline-b-$index"))
        a2.start()
        awaitCompanionMessages(a2, handshake.aConversation, 220)

        pair.a.restart()
        pair.a.sendMessage(handshake.aConversation, "after-a1-restart")
        awaitCompanionMessages(a2, handshake.aConversation, 221)

        val a2Device = a2.state("identity")("deviceId").str
        val revoked   = new Http().post(s"${pair.a.base}/api/devices/$a2Device/revoke", ujson.Obj())
        ensure(revoked.ok, s"A1 revoke failed: ${revoked.status} ${revoked.body}")
        pair.a.sendMessage(handshake.aConversation, "after-revoke")
        pair.b.sendMessage(handshake.bConversation, "after-revoke-b")
        Thread.sleep(2_000L)
        val afterRevoke = a2.messages(handshake.aConversation).size
        ensure(afterRevoke == 221, s"A2 received after revoke: $afterRevoke")
        val send = new Http().post(
          s"${a2.base}/api/conversations/${handshake.aConversation}/messages",
          ujson.Obj("body" -> "forbidden")
        )
        ensure(send.status == 404 || send.status == 405 || send.status == 400, s"A2 mutation accepted: ${send.status}")

        s"old=0 postJoin=200 offline=220 restart=221 revoked=221 a2=${a2Device.take(12)}"
      finally
        a2.stop()
        E2E.deleteTree(a2Directory)
    }

  private def runPairing(a1: ServiceProcess, a2: CompanionProcess, user: String, invite: Path): Unit =
    Files.writeString(a2.dataDir.resolve("user.txt"), user, StandardCharsets.UTF_8)
    val requestExit = PairingTool
      .run(
        List(
          "pair-request",
          "--data-dir", a2.dataDir.toString,
          "--user", user,
          "--invite", invite.toString,
          "--name", "phone",
          "--passphrase", passphrase
        )
      )
      .unsafeRunSync()
    ensure(requestExit.contains(cats.effect.ExitCode.Success), "A2 pair-request failed")
    val pending     = ujson.read(Files.readString(a2.dataDir.resolve("pairing.json"), StandardCharsets.UTF_8))
    val requestCode = pending("requestCode").str
    val confirmation = pending("confirmation").str
    val http        = new Http()
    val inspected   = http.post(s"${a1.base}/api/devices/pairing/inspect", ujson.Obj("requestCode" -> requestCode))
    ensure(inspected.ok, s"A1 inspect failed: ${inspected.status} ${inspected.body}")
    ensure(inspected.json("confirmation").str == confirmation, "two-screen confirmation mismatch")
    val confirmed = http.post(
      s"${a1.base}/api/devices/pairing/confirm",
      ujson.Obj("requestCode" -> requestCode, "confirmation" -> confirmation)
    )
    ensure(confirmed.ok, s"A1 confirm failed: ${confirmed.status} ${confirmed.body}")
    val authFile = a2.dataDir.resolve("auth.txt")
    Files.writeString(authFile, confirmed.json("authCode").str, StandardCharsets.UTF_8)
    val acceptExit = PairingTool
      .run(List("pair-accept", "--data-dir", a2.dataDir.toString, "--auth", authFile.toString, "--passphrase", passphrase))
      .unsafeRunSync()
    ensure(acceptExit.contains(cats.effect.ExitCode.Success), "A2 pair-accept failed")

  private def awaitCompanionContact(a2: CompanionProcess, user: String): Unit =
    E2E.awaitTrue(s"A2 contact $user", Duration.ofSeconds(20)) {
      a2.state("contacts").arr.exists(_.obj("userPub").str == user)
    }

  private def awaitCompanionMessages(a2: CompanionProcess, conversation: String, expected: Int): Unit =
    E2E.awaitTrue(s"A2 messages=$expected", Duration.ofSeconds(30)) {
      a2.messages(conversation).size >= expected
    }

  private def ensure(condition: Boolean, message: => String): Unit =
    if !condition then throw new AssertionError(message)

  private final class CompanionProcess(val name: String, val dataDir: Path, val port: Int, relayPort: Int):
    private val http                 = new Http
    private val log                  = dataDir.resolve("companion.log")
    private var process: Option[Process] = None

    def base: String = s"http://127.0.0.1:$port"

    def start(): Unit =
      if process.exists(_.isAlive) then return
      val java      = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val classpath = System.getProperty("java.class.path")
      val user      = Files.readString(dataDir.resolve("user.txt"), StandardCharsets.UTF_8).trim
      val builder   = new ProcessBuilder(
        java,
        "-cp",
        classpath,
        "yochat.server.Main",
        "--companion-user",
        user,
        "--data-dir",
        dataDir.toString,
        "--passphrase",
        passphrase,
        "--port",
        port.toString,
        "--relay",
        s"127.0.0.1:$relayPort"
      )
      builder.redirectErrorStream(true)
      builder.redirectOutput(log.toFile)
      val startedProcess = builder.start()
      process = Some(startedProcess)
      E2E.awaitTrue(s"$name bind", Duration.ofSeconds(20)) {
        startedProcess.isAlive && Files.exists(log) && Files.readAllLines(log).asScala.exists(_.contains(s"YoChat read-only web: http://127.0.0.1:$port"))
      }
      E2E.awaitTrue(s"$name session", Duration.ofSeconds(20)) {
        http.get(s"$base/api/session").status == 200
      }

    def stop(): Unit =
      process.foreach { value =>
        if value.isAlive then
          value.destroy()
          if !value.waitFor(10, TimeUnit.SECONDS) then value.destroyForcibly()
      }
      process = None

    def state: Value = http.get(s"$base/api/session").json

    def messages(conversation: String): Vector[Value] =
      http.get(s"$base/api/conversations/$conversation/messages?limit=1000").json.arr.toVector
