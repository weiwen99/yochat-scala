package yochat.spike

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.KeyPair
import java.util.Arrays
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object SpikeMain:
  private val Utf8 = StandardCharsets.UTF_8

  def main(args: Array[String]): Unit =
    args.toList match
      case "crypto" :: Nil => CryptoChecks.run()
      case "relay" :: rest  => runRelay(parseArgs(rest))
      case "peer" :: rest   => runPeer(parseArgs(rest))
      case "demo" :: rest   => runDemo(parseArgs(rest))
      case _ =>
        println(usage)
        sys.exit(2)

  private def usage: String =
    """Usage:
      |  SpikeMain crypto
      |  SpikeMain relay [--port N]
      |  SpikeMain peer --role alice|bob --port N --invitation PATH --messages N ...
      |  SpikeMain demo [--messages N]
      |""".stripMargin

  @tailrec
  private def parseArgs(rest: List[String], acc: Map[String, String] = Map.empty): Map[String, String] =
    rest match
      case key :: value :: tail if key.startsWith("--") =>
        parseArgs(tail, acc + (key.drop(2) -> value))
      case key :: Nil if key.startsWith("--") =>
        parseArgs(Nil, acc + (key.drop(2) -> "true"))
      case _ :: tail =>
        parseArgs(tail, acc)
      case Nil =>
        acc

  private def runRelay(args: Map[String, String]): Unit =
    val relay = new RelayServer(args.get("port").map(_.toInt).getOrElse(0))
    relay.start()
    println(s"RELAY_READY port=${relay.port}")
    while true do Thread.sleep(3600_000)

  private def runPeer(args: Map[String, String]): Unit =
    val role       = args.getOrElse("role", throw new IllegalArgumentException("--role is required"))
    val port       = args("port").toInt
    val messages   = args.getOrElse("messages", "0").toInt
    val invitation = Wire.Invitation.parse(readFile(args("invitation")), System.currentTimeMillis())

    role match
      case "bob" =>
        val user      = Crypto.ed25519KeyPair()
        val device    = Crypto.ed25519KeyPair()
        val ephemeral = Crypto.x25519KeyPair()
        val reply     = Reply.create(invitation, user, device, ephemeral, "wechat", "bob", "spike")
        writeFile(args("reply-out"), reply.text)

        val receiver = new ReceiverChain(reply.session.a2b)
        val sender   = new SenderChain(reply.session.b2a)
        val received = new AtomicInteger(0)
        val errors   = new AtomicReference[String](null)
        val ref      = new ClientRef

        val client = RelayClient.connect(
          "127.0.0.1",
          port,
          Crypto.publicBytes(device.getPublic),
          (from, payload) =>
            try
              val message = Wire.Message.decode(payload)
              val text    = new String(MessageCrypto.decrypt(receiver, message), Utf8)
              if text.startsWith("MSG:") then
                received.incrementAndGet()
                val seq = text.split(":")(1)
                val ack = MessageCrypto.encrypt(sender, s"ACK:$seq".getBytes(Utf8))
                if ref.client != null then ref.client.send(from, ack.bytes)
            catch
              case NonFatal(error) => errors.compareAndSet(null, error.toString)
        )
        ref.client = client
        writeFile(args("ready"), "ready")
        waitFor(() => received.get >= messages || errors.get != null, 30000L, "bob messages")
        Thread.sleep(200)
        writeFile(
          args("result"),
          s"received=${received.get}\nerrors=${Option(errors.get).getOrElse("")}\n",
        )
        client.close()
        if errors.get != null || received.get < messages then sys.exit(1)

      case "alice" =>
        val ephemeralPrivate = Crypto.x25519Private(Files.readAllBytes(Paths.get(args("ephemeral-key"))))
        val reply            = Reply.accept(invitation, readFile(args("reply")), PrivateKeyLike(ephemeralPrivate))
        val receiver         = new ReceiverChain(reply.session.b2a)
        val sender           = new SenderChain(reply.session.a2b)
        val acked            = new AtomicInteger(0)
        val errors           = new AtomicReference[String](null)
        val ref              = new ClientRef

        val client = RelayClient.connect(
          "127.0.0.1",
          port,
          invitation.fields.devicePub,
          (_from, payload) =>
            try
              val message = Wire.Message.decode(payload)
              val text    = new String(MessageCrypto.decrypt(receiver, message), Utf8)
              if text.startsWith("ACK:") then acked.incrementAndGet()
            catch
              case NonFatal(error) => errors.compareAndSet(null, error.toString)
        )
        ref.client = client
        writeFile(args("ready"), "ready")

        val reconnect = args.get("reconnect").contains("true")
        val started   = System.nanoTime()
        var index     = 1
        while index <= messages do
          val message = MessageCrypto.encrypt(sender, s"MSG:$index:hello".getBytes(Utf8))
          ref.client.send(reply.fields.devicePub, message.bytes)
          index += 1

        waitFor(() => acked.get >= messages || errors.get != null, 30000L, "alice acks")
        val elapsedMs     = (System.nanoTime() - started) / 1000000L
        val totalSent     = if reconnect then messages + 1 else messages
        var reconnectMs   = 0L
        if reconnect then
          client.close()
          val reconnected = RelayClient.connect(
            "127.0.0.1",
            port,
            invitation.fields.devicePub,
            (_from, payload) =>
              try
                val message = Wire.Message.decode(payload)
                val text    = new String(MessageCrypto.decrypt(receiver, message), Utf8)
                if text.startsWith("ACK:") then acked.incrementAndGet()
              catch
                case NonFatal(error) => errors.compareAndSet(null, error.toString)
          )
          ref.client = reconnected
          val reconnectStarted = System.nanoTime()
          val extra            = MessageCrypto.encrypt(sender, s"MSG:$totalSent:after-reconnect".getBytes(Utf8))
          ref.client.send(reply.fields.devicePub, extra.bytes)
          waitFor(() => acked.get >= totalSent || errors.get != null, 30000L, "alice reconnect ack")
          reconnectMs = (System.nanoTime() - reconnectStarted) / 1000000L
          reconnected.close()
        writeFile(
          args("result"),
          s"sent=$totalSent\nacked=${acked.get}\nelapsedMs=$elapsedMs\nreconnectMs=$reconnectMs\n" +
            s"errors=${Option(errors.get).getOrElse("")}\n",
        )
        client.close()
        if errors.get != null || acked.get < totalSent then sys.exit(1)

      case other =>
        throw new IllegalArgumentException(s"unknown role $other")

  private def runDemo(args: Map[String, String]): Unit =
    val messages = args.get("messages").map(_.toInt).getOrElse(1000)
    val reconnect = args.get("reconnect").contains("true")
    val expected  = if reconnect then messages + 1 else messages
    CryptoChecks.run()

    val directory = Files.createTempDirectory("yochat-spike-")
    val relay     = new RelayServer(0)
    relay.start()
    val port = relay.port
    try
      val aliceUser      = Crypto.ed25519KeyPair()
      val aliceDevice    = Crypto.ed25519KeyPair()
      val aliceEphemeral = Crypto.x25519KeyPair()
      val invitation = Wire.Invitation.create(
        aliceUser,
        aliceDevice,
        aliceEphemeral,
        "wechat",
        "alice",
        System.currentTimeMillis() + 3600_000L,
        "spike",
      )

      val invitationFile = directory.resolve("invitation.txt")
      val ephemeralFile  = directory.resolve("alice-ephemeral.key")
      val replyFile      = directory.resolve("reply.txt")
      val bobReady       = directory.resolve("bob.ready")
      val bobResult      = directory.resolve("bob.result")
      val aliceReady     = directory.resolve("alice.ready")
      val aliceResult    = directory.resolve("alice.result")

      writeFile(invitationFile, invitation.text)
      Files.write(ephemeralFile, aliceEphemeral.getPrivate.getEncoded)

      val bob = startPeer(
        directory.resolve("bob.log"),
        List(
          "--role", "bob",
          "--port", port.toString,
          "--invitation", invitationFile.toString,
          "--reply-out", replyFile.toString,
          "--messages", expected.toString,
          "--ready", bobReady.toString,
          "--result", bobResult.toString,
        ),
      )
      waitFor(() => Files.exists(replyFile) && Files.exists(bobReady), 30000L, "bob reply and ready")

      val alice = startPeer(
        directory.resolve("alice.log"),
        List(
          "--role", "alice",
          "--port", port.toString,
          "--invitation", invitationFile.toString,
          "--ephemeral-key", ephemeralFile.toString,
          "--reply", replyFile.toString,
          "--messages", messages.toString,
          "--ready", aliceReady.toString,
          "--result", aliceResult.toString,
        ) ++ (if reconnect then List("--reconnect", "true") else List.empty),
      )

      val bobFinished   = bob.waitFor(60, TimeUnit.SECONDS)
      val aliceFinished = alice.waitFor(60, TimeUnit.SECONDS)
      if !bobFinished then bob.destroyForcibly()
      if !aliceFinished then alice.destroyForcibly()
      if !bobFinished || !aliceFinished then
        throw new RuntimeException(s"peer timeout; logs: ${directory}")

      val bobValues   = readResult(bobResult)
      val aliceValues = readResult(aliceResult)
      val ok =
        aliceValues.get("sent").contains(expected.toString) &&
          aliceValues.get("acked").contains(expected.toString) &&
          bobValues.get("received").contains(expected.toString) &&
          aliceValues.get("errors").forall(_.isEmpty) &&
          bobValues.get("errors").forall(_.isEmpty)
      println(
        s"SPIKE messages=$messages total=$expected reconnect=$reconnect " +
          s"aliceSent=${aliceValues.getOrElse("sent", "?")} " +
          s"aliceAcked=${aliceValues.getOrElse("acked", "?")} " +
          s"elapsedMs=${aliceValues.getOrElse("elapsedMs", "?")} " +
          s"reconnectMs=${aliceValues.getOrElse("reconnectMs", "?")} " +
          s"bobReceived=${bobValues.getOrElse("received", "?")} ok=$ok",
      )
      if !ok then
        println(s"bob log:\n${readFileOr(directory.resolve("bob.log"), "")}")
        println(s"alice log:\n${readFileOr(directory.resolve("alice.log"), "")}")
        sys.exit(1)
    finally relay.close()

  private def startPeer(log: Path, args: List[String]): Process =
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command = (List(javaBin, "-cp", System.getProperty("java.class.path"), "yochat.spike.SpikeMain", "peer") ++ args).asJava
    val builder = new ProcessBuilder(command)
    builder.directory(log.getParent.toFile)
    builder.redirectErrorStream(true)
    builder.redirectOutput(log.toFile)
    builder.start()

  private def waitFor(condition: () => Boolean, timeoutMs: Long, label: String): Unit =
    val deadline = System.nanoTime() + timeoutMs * 1000000L
    while !condition() && System.nanoTime() < deadline do Thread.sleep(25)
    if !condition() then throw new RuntimeException(s"timeout waiting for $label")

  private def readFile(path: String): String =
    Files.readString(Paths.get(path)).trim

  private def writeFile(path: Path, text: String): Unit =
    Files.writeString(path, text)

  private def writeFile(path: String, text: String): Unit =
    writeFile(Paths.get(path), text)

  private def readFileOr(path: Path, fallback: String): String =
    if Files.exists(path) then Files.readString(path) else fallback

  private def readResult(path: Path): Map[String, String] =
    Files.readAllLines(path).asScala.flatMap { line =>
      line.split("=", 2) match
        case Array(key, value) => Some(key -> value)
        case _                 => None
    }.toMap

  private final class ClientRef:
    @volatile var client: RelayClient = null

object CryptoChecks:
  private val Utf8 = StandardCharsets.UTF_8

  def run(): Unit =
    val now = System.currentTimeMillis()
    val aliceUser      = Crypto.ed25519KeyPair()
    val aliceDevice    = Crypto.ed25519KeyPair()
    val aliceEphemeral = Crypto.x25519KeyPair()
    val invitation = Wire.Invitation.create(
      aliceUser,
      aliceDevice,
      aliceEphemeral,
      "wechat",
      "alice",
      now + 3600_000L,
      "spike",
    )
    val parsed = Wire.Invitation.parse(invitation.text, now)
    check("invitation roundtrip") {
      require(Arrays.equals(parsed.hash, invitation.hash), "invitation hash changed")
      require(Arrays.equals(parsed.fields.devicePub, Crypto.publicBytes(aliceDevice.getPublic)), "device key changed")
    }

    val bobUser      = Crypto.ed25519KeyPair()
    val bobDevice    = Crypto.ed25519KeyPair()
    val bobEphemeral = Crypto.x25519KeyPair()
    val bobReply     = Reply.create(parsed, bobUser, bobDevice, bobEphemeral, "wechat", "bob", "reply")
    val aliceReply   = Reply.accept(parsed, bobReply.text, PrivateKeyLike(aliceEphemeral.getPrivate))
    check("session root") {
      require(Arrays.equals(aliceReply.session.root, bobReply.session.root), "session roots differ")
      require(Arrays.equals(aliceReply.fields.userPub, Crypto.publicBytes(bobUser.getPublic)), "reply user changed")
    }
    check("safety number") {
      require(
        SafetyNumber.of(Crypto.publicBytes(aliceUser.getPublic), Crypto.publicBytes(bobUser.getPublic)) ==
          SafetyNumber.of(Crypto.publicBytes(bobUser.getPublic), Crypto.publicBytes(aliceUser.getPublic)),
        "safety number is not symmetric",
      )
    }

    val aSend = new SenderChain(aliceReply.session.a2b)
    val bRecv = new ReceiverChain(bobReply.session.a2b)
    val bSend = new SenderChain(bobReply.session.b2a)
    val aRecv = new ReceiverChain(aliceReply.session.b2a)
    check("message roundtrip") {
      for index <- 1 to 10 do
        val msg = MessageCrypto.encrypt(aSend, s"a-$index".getBytes(Utf8))
        require(new String(MessageCrypto.decrypt(bRecv, msg), Utf8) == s"a-$index", "alice to bob")
        val reply = MessageCrypto.encrypt(bSend, s"b-$index".getBytes(Utf8))
        require(new String(MessageCrypto.decrypt(aRecv, reply), Utf8) == s"b-$index", "bob to alice")
    }

    check("tampered ciphertext") {
      val sender   = new SenderChain(aliceReply.session.a2b)
      val receiver = new ReceiverChain(bobReply.session.a2b)
      val msg      = MessageCrypto.encrypt(sender, "secret".getBytes(Utf8))
      val flipped  = msg.copy(ciphertext = flip(msg.ciphertext))
      expectFailure("tampered ciphertext") {
        MessageCrypto.decrypt(receiver, flipped)
      }
    }

    check("replayed message") {
      val sender   = new SenderChain(aliceReply.session.a2b)
      val receiver = new ReceiverChain(bobReply.session.a2b)
      val msg      = MessageCrypto.encrypt(sender, "once".getBytes(Utf8))
      MessageCrypto.decrypt(receiver, msg)
      expectFailure("replay") {
        MessageCrypto.decrypt(receiver, msg)
      }
    }

    check("out of order") {
      val sender   = new SenderChain(aliceReply.session.a2b)
      val receiver = new ReceiverChain(bobReply.session.a2b)
      val first    = MessageCrypto.encrypt(sender, "1".getBytes(Utf8))
      val second   = MessageCrypto.encrypt(sender, "2".getBytes(Utf8))
      val third    = MessageCrypto.encrypt(sender, "3".getBytes(Utf8))
      require(new String(MessageCrypto.decrypt(receiver, first), Utf8) == "1", "first")
      require(new String(MessageCrypto.decrypt(receiver, third), Utf8) == "3", "third")
      require(new String(MessageCrypto.decrypt(receiver, second), Utf8) == "2", "second")
      expectFailure("replayed skipped message") {
        MessageCrypto.decrypt(receiver, second)
      }
    }

    check("wrong key") {
      val sender   = new SenderChain(aliceReply.session.a2b)
      val wrongKey = Crypto.hkdf(aliceReply.session.root, Array.emptyByteArray, "wrong", 32)
      val receiver = new ReceiverChain(wrongKey)
      val msg      = MessageCrypto.encrypt(sender, "wrong".getBytes(Utf8))
      expectFailure("wrong key") {
        MessageCrypto.decrypt(receiver, msg)
      }
    }

    check("expired invitation") {
      expectFailure("expired invitation") {
        Wire.Invitation.parse(invitation.text, now + 7200_000L)
      }
    }

    check("tampered invitation signature") {
      val decoded = Crypto.unbase64(invitation.text)
      val flipped = flipLast(decoded)
      expectFailure("tampered invitation") {
        Wire.Invitation.parse(Crypto.base64(flipped), now)
      }
    }

    check("tampered reply ciphertext") {
      val flipped = bobReply.envelope.copy(ciphertext = flip(bobReply.envelope.ciphertext))
      expectFailure("tampered reply") {
        Reply.accept(parsed, flipped.text, PrivateKeyLike(aliceEphemeral.getPrivate))
      }
    }

    println("CRYPTO_CHECKS PASS")

  private def check(name: String)(body: => Unit): Unit =
    try body
    catch
      case NonFatal(error) =>
        println(s"CRYPTO_CHECK FAIL: $name: $error")
        throw error

  private def expectFailure(name: String)(body: => Any): Unit =
    var failed = false
    try
      body
      ()
    catch
      case NonFatal(_) => failed = true
    if !failed then throw new AssertionError(s"$name did not fail")

  private def flip(bytes: Array[Byte]): Array[Byte] =
    require(bytes.nonEmpty, "cannot flip an empty array")
    val copy = bytes.clone()
    copy(0) = (copy(0) ^ 0x01).toByte
    copy

  private def flipLast(bytes: Array[Byte]): Array[Byte] =
    require(bytes.nonEmpty, "cannot flip an empty array")
    val copy = bytes.clone()
    copy(copy.length - 1) = (copy(copy.length - 1) ^ 0x01).toByte
    copy
