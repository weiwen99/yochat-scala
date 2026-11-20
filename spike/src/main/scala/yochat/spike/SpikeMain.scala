package yochat.spike

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.{Clock, Instant}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.Arrays

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object SpikeMain:
  private val Utf8 = StandardCharsets.UTF_8

  def main(args: Array[String]): Unit =
    val clock = Clock.systemUTC()
    args.toList match
      case "crypto" :: Nil => CryptoChecks.run(clock)
      case "relay" :: rest => runRelay(parseArgs(rest))
      case "peer" :: rest  => runPeer(parseArgs(rest), clock)
      case "demo" :: rest  => runDemo(parseArgs(rest), clock)
      case _               =>
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
      case key :: Nil if key.startsWith("--")           =>
        parseArgs(Nil, acc + (key.drop(2) -> "true"))
      case _ :: tail                                    =>
        parseArgs(tail, acc)
      case Nil                                          =>
        acc

  private def runRelay(args: Map[String, String]): Unit =
    val relay = new RelayServer(args.get("port").map(_.toInt).getOrElse(0))
    relay.start()
    println(s"RELAY_READY port=${relay.port}")
    new CountDownLatch(1).await()

  private def runPeer(args: Map[String, String], clock: Clock): Unit =
    val role       = args.getOrElse("role", throw new IllegalArgumentException("--role is required"))
    val port       = args("port").toInt
    val messages   = args.getOrElse("messages", "0").toInt
    val invitation = Wire.Invitation.parse(readFile(args("invitation")), Instant.now(clock).toEpochMilli())

    role match
      case "bob"   => runBob(args, port, messages, invitation)
      case "alice" => runAlice(args, port, messages, invitation)
      case other   => throw new IllegalArgumentException(s"unknown role $other")

  private def runBob(args: Map[String, String], port: Int, messages: Int, invitation: Wire.Invitation): Unit =
    val user      = Crypto.ed25519KeyPair()
    val device    = Crypto.ed25519KeyPair()
    val ephemeral = Crypto.x25519KeyPair()
    val reply     = Reply.create(invitation, user, device, ephemeral, "wechat", "bob", "spike")
    writeFile(args("reply-out"), reply.text)

    val sender      = SenderChain(reply.session.b2a)
    val receiver    = ReceiverChain(reply.session.a2b)
    val senderRef   = new AtomicReference[SenderChain](sender)
    val receiverRef = new AtomicReference[ReceiverChain](receiver)
    val received    = new AtomicInteger(0)
    val errors      = new AtomicReference[Option[String]](None)
    val ref         = new AtomicReference[Option[RelayClient]](None)

    val client = RelayClient.connect(
      "127.0.0.1",
      port,
      Crypto.publicBytes(device.getPublic),
      messageCallback(
        receiverRef,
        errors,
        (from, text) =>
          if text.startsWith("MSG:") then
            received.incrementAndGet()
            sendAck(senderRef, ref, from, text.split(":")(1))
      )
    )
    ref.set(Some(client))
    writeFile(args("ready"), "ready")
    waitFor(() => received.get() >= messages || errors.get().nonEmpty, 30000L, "bob messages")
    Thread.sleep(200)
    writeFile(
      args("result"),
      s"received=${received.get()}\nerrors=${errors.get().getOrElse("")}\n"
    )
    client.close()
    if errors.get().nonEmpty || received.get() < messages then sys.exit(1)

  private def runAlice(args: Map[String, String], port: Int, messages: Int, invitation: Wire.Invitation): Unit =
    val ephemeralPrivate = Crypto.x25519Private(Files.readAllBytes(Paths.get(args("ephemeral-key"))))
    val reply            = Reply.accept(invitation, readFile(args("reply")), PrivateKeyLike(ephemeralPrivate))
    val sender           = SenderChain(reply.session.a2b)
    val receiver         = ReceiverChain(reply.session.b2a)
    val receiverRef      = new AtomicReference[ReceiverChain](receiver)
    val acked            = new AtomicInteger(0)
    val errors           = new AtomicReference[Option[String]](None)
    val ref              = new AtomicReference[Option[RelayClient]](None)

    val client = RelayClient.connect(
      "127.0.0.1",
      port,
      invitation.fields.devicePub,
      messageCallback(
        receiverRef,
        errors,
        (_, text) => if text.startsWith("ACK:") then acked.incrementAndGet()
      )
    )
    ref.set(Some(client))
    writeFile(args("ready"), "ready")

    val reconnect     = args.get("reconnect").contains("true")
    val started       = System.nanoTime()
    val afterMessages = (1 to messages).foldLeft(sender) { (chain, index) =>
      val encrypted = MessageCrypto.encrypt(chain, s"MSG:$index:hello".getBytes(Utf8))
      ref.get() match
        case Some(client) => client.send(reply.fields.devicePub, encrypted._2.bytes)
        case None         => throw new IllegalStateException("alice client is not connected")
      encrypted._1
    }

    waitFor(() => acked.get() >= messages || errors.get().nonEmpty, 30000L, "alice acks")
    val elapsedMs   = (System.nanoTime() - started) / 1000000L
    val totalSent   = if reconnect then messages + 1 else messages
    val reconnectMs =
      if reconnect then
        client.close()
        val reconnected      = RelayClient.connect(
          "127.0.0.1",
          port,
          invitation.fields.devicePub,
          messageCallback(
            receiverRef,
            errors,
            (_, text) => if text.startsWith("ACK:") then acked.incrementAndGet()
          )
        )
        ref.set(Some(reconnected))
        val reconnectStarted = System.nanoTime()
        val extra            = MessageCrypto.encrypt(afterMessages, s"MSG:$totalSent:after-reconnect".getBytes(Utf8))
        ref.get().foreach(_.send(reply.fields.devicePub, extra._2.bytes))
        waitFor(() => acked.get() >= totalSent || errors.get().nonEmpty, 30000L, "alice reconnect ack")
        val elapsed          = (System.nanoTime() - reconnectStarted) / 1000000L
        reconnected.close()
        elapsed
      else 0L
    writeFile(
      args("result"),
      s"sent=$totalSent\nacked=${acked.get()}\nelapsedMs=$elapsedMs\nreconnectMs=$reconnectMs\n" +
        s"errors=${errors.get().getOrElse("")}\n"
    )
    client.close()
    if errors.get().nonEmpty || acked.get() < totalSent then sys.exit(1)

  private def messageCallback(
    receiverRef: AtomicReference[ReceiverChain],
    errors: AtomicReference[Option[String]],
    onText: (Array[Byte], String) => Unit
  ): (Array[Byte], Array[Byte]) => Unit =
    (from, payload) =>
      try
        val message = Wire.Message.decode(payload)
        decryptIncoming(receiverRef, message) match
          case Right(text) => onText(from, text)
          case Left(error) => errors.compareAndSet(None, Some(error.toString))
      catch case NonFatal(error) => errors.compareAndSet(None, Some(error.toString))

  private def decryptIncoming(
    receiverRef: AtomicReference[ReceiverChain],
    message: Wire.Message
  ): Either[MessageError, String] =
    @tailrec
    def attempt(): Either[MessageError, String] =
      val current = receiverRef.get()
      MessageCrypto.decrypt(current, message) match
        case Left(error)   => Left(error)
        case Right(result) =>
          if receiverRef.compareAndSet(current, result._1) then Right(new String(result._2, Utf8))
          else attempt()
    attempt()

  private def sendAck(
    senderRef: AtomicReference[SenderChain],
    ref: AtomicReference[Option[RelayClient]],
    from: Array[Byte],
    seq: String
  ): Unit =
    @tailrec
    def attempt(): Unit =
      val current   = senderRef.get()
      val encrypted = MessageCrypto.encrypt(current, s"ACK:$seq".getBytes(Utf8))
      if senderRef.compareAndSet(current, encrypted._1) then ref.get().foreach(_.send(from, encrypted._2.bytes))
      else attempt()
    attempt()

  private def runDemo(args: Map[String, String], clock: Clock): Unit =
    val messages  = args.get("messages").map(_.toInt).getOrElse(1000)
    val reconnect = args.get("reconnect").contains("true")
    val expected  = if reconnect then messages + 1 else messages
    CryptoChecks.run(clock)

    val directory = Files.createTempDirectory("yochat-spike-")
    val relay     = new RelayServer(0)
    relay.start()
    val port      = relay.port
    try
      val aliceUser      = Crypto.ed25519KeyPair()
      val aliceDevice    = Crypto.ed25519KeyPair()
      val aliceEphemeral = Crypto.x25519KeyPair()
      val invitation     = Wire.Invitation.create(
        aliceUser,
        aliceDevice,
        aliceEphemeral,
        "wechat",
        "alice",
        Instant.now(clock).toEpochMilli() + 3600_000L,
        "spike"
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
          "--role",
          "bob",
          "--port",
          port.toString,
          "--invitation",
          invitationFile.toString,
          "--reply-out",
          replyFile.toString,
          "--messages",
          expected.toString,
          "--ready",
          bobReady.toString,
          "--result",
          bobResult.toString
        )
      )
      waitFor(() => Files.exists(replyFile) && Files.exists(bobReady), 30000L, "bob reply and ready")

      val alice = startPeer(
        directory.resolve("alice.log"),
        List(
          "--role",
          "alice",
          "--port",
          port.toString,
          "--invitation",
          invitationFile.toString,
          "--ephemeral-key",
          ephemeralFile.toString,
          "--reply",
          replyFile.toString,
          "--messages",
          messages.toString,
          "--ready",
          aliceReady.toString,
          "--result",
          aliceResult.toString
        ) ++ (if reconnect then List("--reconnect", "true") else List.empty)
      )

      val bobFinished   = bob.waitFor(60, TimeUnit.SECONDS)
      val aliceFinished = alice.waitFor(60, TimeUnit.SECONDS)
      if !bobFinished then bob.destroyForcibly()
      if !aliceFinished then alice.destroyForcibly()
      if !bobFinished || !aliceFinished then throw new RuntimeException(s"peer timeout; logs: ${directory}")

      val bobValues   = readResult(bobResult)
      val aliceValues = readResult(aliceResult)
      val ok          =
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
          s"bobReceived=${bobValues.getOrElse("received", "?")} ok=$ok"
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
    val deadline     = System.nanoTime() + timeoutMs * 1000000L
    @tailrec
    def loop(): Unit =
      if condition() then ()
      else if System.nanoTime() >= deadline then throw new RuntimeException(s"timeout waiting for $label")
      else
        Thread.sleep(25)
        loop()
    loop()

  private def readFile(path: String): String =
    Files.readString(Paths.get(path)).trim

  private def writeFile(path: Path, text: String): Unit =
    Files.writeString(path, text)

  private def writeFile(path: String, text: String): Unit =
    writeFile(Paths.get(path), text)

  private def readFileOr(path: Path, fallback: String): String =
    if Files.exists(path) then Files.readString(path) else fallback

  private def readResult(path: Path): Map[String, String] =
    Files
      .readAllLines(path)
      .asScala
      .flatMap { line =>
        line.split("=", 2) match
          case Array(key, value) => Some(key -> value)
          case _                 => None
      }
      .toMap

object CryptoChecks:
  private val Utf8 = StandardCharsets.UTF_8

  final private case class Directions(
    aSend: SenderChain,
    bRecv: ReceiverChain,
    bSend: SenderChain,
    aRecv: ReceiverChain
  )

  def run(clock: Clock): Unit =
    val now            = Instant.now(clock).toEpochMilli()
    val aliceUser      = Crypto.ed25519KeyPair()
    val aliceDevice    = Crypto.ed25519KeyPair()
    val aliceEphemeral = Crypto.x25519KeyPair()
    val invitation     = Wire.Invitation.create(
      aliceUser,
      aliceDevice,
      aliceEphemeral,
      "wechat",
      "alice",
      now + 3600_000L,
      "spike"
    )
    val parsed         = Wire.Invitation.parse(invitation.text, now)
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
        "safety number is not symmetric"
      )
    }

    val aSend = SenderChain(aliceReply.session.a2b)
    val bRecv = ReceiverChain(bobReply.session.a2b)
    val bSend = SenderChain(bobReply.session.b2a)
    val aRecv = ReceiverChain(aliceReply.session.b2a)
    check("message roundtrip") {
      (1 to 10).foldLeft(Directions(aSend, bRecv, bSend, aRecv)) { (state, index) =>
        val a = MessageCrypto.encrypt(state.aSend, s"a-$index".getBytes(Utf8))
        val b = MessageCrypto.decrypt(state.bRecv, a._2).getOrElse(throw new AssertionError("alice to bob failed"))
        require(new String(b._2, Utf8) == s"a-$index", "alice to bob")
        val c = MessageCrypto.encrypt(state.bSend, s"b-$index".getBytes(Utf8))
        val d = MessageCrypto.decrypt(state.aRecv, c._2).getOrElse(throw new AssertionError("bob to alice failed"))
        require(new String(d._2, Utf8) == s"b-$index", "bob to alice")
        Directions(a._1, b._1, c._1, d._1)
      }
    }

    check("tampered ciphertext") {
      val sender   = SenderChain(aliceReply.session.a2b)
      val receiver = ReceiverChain(bobReply.session.a2b)
      val message  = MessageCrypto.encrypt(sender, "secret".getBytes(Utf8))
      val flipped  = message._2.copy(ciphertext = flip(message._2.ciphertext))
      require(MessageCrypto.decrypt(receiver, flipped).isLeft, "tampered ciphertext must fail")
    }

    check("replayed message") {
      val sender   = SenderChain(aliceReply.session.a2b)
      val receiver = ReceiverChain(bobReply.session.a2b)
      val message  = MessageCrypto.encrypt(sender, "once".getBytes(Utf8))
      val first    = MessageCrypto.decrypt(receiver, message._2)
      require(first.isRight, "first decrypt must succeed")
      val next     = first.toOption.map(_._1).getOrElse(throw new AssertionError("missing receiver state"))
      require(MessageCrypto.decrypt(next, message._2).isLeft, "replay must fail")
    }

    check("out of order") {
      val sender   = SenderChain(aliceReply.session.a2b)
      val receiver = ReceiverChain(bobReply.session.a2b)
      val first    = MessageCrypto.encrypt(sender, "1".getBytes(Utf8))
      val second   = MessageCrypto.encrypt(first._1, "2".getBytes(Utf8))
      val third    = MessageCrypto.encrypt(second._1, "3".getBytes(Utf8))
      val opened1  = MessageCrypto.decrypt(receiver, first._2).getOrElse(throw new AssertionError("first"))
      require(new String(opened1._2, Utf8) == "1", "first")
      val opened3  = MessageCrypto.decrypt(opened1._1, third._2).getOrElse(throw new AssertionError("third"))
      require(new String(opened3._2, Utf8) == "3", "third")
      val opened2  = MessageCrypto.decrypt(opened3._1, second._2).getOrElse(throw new AssertionError("second"))
      require(new String(opened2._2, Utf8) == "2", "second")
      require(MessageCrypto.decrypt(opened2._1, second._2).isLeft, "replayed skipped message must fail")
    }

    check("wrong key") {
      val sender   = SenderChain(aliceReply.session.a2b)
      val wrongKey = Crypto.hkdf(aliceReply.session.root, Array.emptyByteArray, "wrong", 32)
      val receiver = ReceiverChain(wrongKey)
      val message  = MessageCrypto.encrypt(sender, "wrong".getBytes(Utf8))
      require(MessageCrypto.decrypt(receiver, message._2).isLeft, "wrong key must fail")
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
    val failed =
      try
        body
        false
      catch case NonFatal(_) => true
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
