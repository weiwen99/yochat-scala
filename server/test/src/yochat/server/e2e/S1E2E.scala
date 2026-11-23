package yochat.server.e2e

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.{Duration, Instant}

import scala.util.control.NonFatal

import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Ed25519Public, SafetyNumber}

final case class Contract(id: String, description: String, run: () => String)

object S1E2E:

  def main(args: Array[String]): Unit =
    val selected = select(args).getOrElse {
      System.err.println("usage: server.e2e [--only E2E#3]")
      System.exit(2)
      Vector.empty
    }
    if selected.isEmpty then
      System.err.println(s"unknown contract: ${args.mkString(" ")}")
      System.exit(2)
    val receipt = new Receipt(receiptRoot.resolve("tmp").resolve("checks").resolve(System.currentTimeMillis().toString))
    val results = selected.map(contract => run(contract, receipt))
    val failed  = results.count(!_)
    receipt.write(s"summary total=${results.size} passed=${results.size - failed} failed=$failed")
    println(s"E2E_SUMMARY total=${results.size} passed=${results.size - failed} failed=$failed")
    println(s"E2E_RECEIPT path=${receipt.path}")
    if failed > 0 then System.exit(1)

  private def receiptRoot: Path =
    val current = Path.of(System.getProperty("user.dir")).toAbsolutePath
    if current.getFileName.toString == "server" then current.getParent else current

  private def select(args: Array[String]): Option[Vector[Contract]] =
    val all = Vector(
      Contract("E2E#1", "first invitation and reply", () => scenario1()),
      Contract("E2E#2", "add contact and show it on both sides", () => scenario2()),
      Contract("E2E#3", "one message, both sides, safety gate before body", () => scenario3()),
      Contract("E2E#4", "restart the sender, message remains", () => scenario4()),
      Contract("E2E#5", "restart the receiver, message remains", () => scenario5()),
      Contract("E2E#6", "disconnect and reconnect, pending message arrives", () => scenario6()),
      Contract("E2E#7", "100 messages in order, no duplicates", () => scenario7())
    )
    args.toList match
      case Nil         =>
        sys.props.get("e2e.only").orElse(sys.env.get("E2E_ONLY")) match
          case Some(id) => Some(all.filter(_.id == id))
          case None     => Some(all)
      case "--only" :: id :: Nil => Some(all.filter(_.id == id))
      case _                     => None

  private def run(contract: Contract, receipt: Receipt): Boolean =
    println(s"${contract.id} START ${contract.description}")
    val started = System.nanoTime()
    try
      val evidence = contract.run()
      val elapsed  = (System.nanoTime() - started) / 1_000_000L
      println(s"${contract.id} PASS $evidence")
      receipt.write(s"${contract.id} PASS duration_ms=$elapsed evidence=$evidence")
      true
    catch
      case NonFatal(error) =>
        val elapsed  = (System.nanoTime() - started) / 1_000_000L
        val location = failureLocation(error)
        println(s"${contract.id} FAIL ${error.getClass.getSimpleName} at $location")
        receipt.write(s"${contract.id} FAIL duration_ms=$elapsed failure=${error.getClass.getSimpleName} at=$location")
        false

  private def failureLocation(error: Throwable): String =
    val candidates = error.getStackTrace.toVector ++ Option(error.getCause).toVector.flatMap(_.getStackTrace.toVector)
    candidates
      .find(frame => frame.getClassName.startsWith("yochat.server.e2e.S1E2E"))
      .orElse(candidates.headOption)
      .map(frame => s"${frame.getFileName}:${frame.getLineNumber}")
      .getOrElse("unknown")

  private def scenario1(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      ensure(pair.a.contacts.contains(handshake.bUser), s"A missing B contact: ${pair.a.state}")
      ensure(pair.b.contacts.contains(handshake.aUser), s"B missing A contact: ${pair.b.state}")
      val contactA = contact(pair.a.state, handshake.bUser)
      val contactB = contact(pair.b.state, handshake.aUser)
      ensure(!contactA("safetyVerified").bool, s"A safety verified before out-of-band check: $contactA")
      ensure(!contactB("safetyVerified").bool, s"B safety verified before out-of-band check: $contactB")
      ensure(contactA("confirmation").str == "pending", s"A confirmation=${contactA("confirmation")}")
      ensure(contactB("confirmation").str == "confirmed", s"B confirmation=${contactB("confirmation")}")
      ensure(contactA("safetyCode").str == expectedSafety(handshake.aUser, handshake.bUser), "A safety code")
      ensure(contactB("safetyCode").str == expectedSafety(handshake.bUser, handshake.aUser), "B safety code")
      assertNoPreSafetyBody(pair, handshake, "E2E#1")
      "contacts=1/1 safety=unverified confirmation=pending/confirmed preSafety=no-body"
    }

  private def scenario2(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      val aState    = pair.a.state
      val bState    = pair.b.state
      ensure(contact(aState, handshake.bUser)("peerChannelAccount").str == "@B", s"A contact peer account: $aState")
      ensure(contact(bState, handshake.aUser)("peerChannelAccount").str == "@A", s"B contact peer account: $bState")
      ensure(pair.a.conversationId(handshake.bUser) == handshake.aConversation, "A conversation changed")
      ensure(pair.b.conversationId(handshake.aUser) == handshake.bConversation, "B conversation changed")
      assertNoPreSafetyBody(pair, handshake, "E2E#2")
      s"aContacts=${pair.a.contacts.size} bContacts=${pair.b.contacts.size} conversations=1/1"
    }

  private def scenario3(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      assertNoPreSafetyBody(pair, handshake, "E2E#3")

      verifySafety(pair, handshake)
      pair.a.sendMessage(handshake.aConversation, "hello")

      val onB = pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(message => message._1 == 1L && message._2 == "peer" && message._4 == "hello"),
        "hello received"
      )
      val onA = pair.a.awaitMessage(
        handshake.aConversation,
        messages => messages.exists(message => message._1 == 1L && message._2 == "me" && message._3 == "delivered" && message._4 == "hello"),
        "hello delivered"
      )
      ensure(onB.count(_._1 == 1L) == 1, s"B did not show exactly one first message: $onB")
      ensure(onB.head._3 == "received", s"B first message not received: $onB")
      ensure(onA.count(_._1 == 1L) == 1, s"A did not show exactly one first message: $onA")
      "preSafety=empty aSeq=1 state=delivered bSeq=1 state=received"
    }

  private def scenario4(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      val aConversation = pair.a.conversationId(handshake.bUser)
      val bConversation = pair.b.conversationId(handshake.aUser)
      pair.a.sendMessage(aConversation, "before-sender-restart")
      pair.b.awaitMessage(bConversation, _.nonEmpty, "initial message")
      pair.a.awaitMessage(aConversation, _.exists(_._3 == "delivered"), "initial delivered")
      ensure(pair.a.messages(aConversation) == Vector((1L, "me", "delivered", "before-sender-restart")), "A initial history")
      ensure(pair.b.messages(bConversation) == Vector((1L, "peer", "received", "before-sender-restart")), "B initial history")

      pair.a.restart()
      val aStateAfter = pair.a.state
      ensure(contact(aStateAfter, handshake.bUser)("userPub").str == handshake.bUser, "A contact missing after restart")
      val aConversationAfter = pair.a.conversationId(handshake.bUser)
      ensure(pair.a.messages(aConversationAfter) == Vector((1L, "me", "delivered", "before-sender-restart")), "A history missing")
      ensure(
        pair.b.messages(pair.b.conversationId(handshake.aUser)) == Vector((1L, "peer", "received", "before-sender-restart")),
        "B history changed after A restart"
      )

      pair.a.sendMessage(aConversationAfter, "after-sender-restart")
      pair.b.awaitMessage(
        bConversation,
        messages => messages.exists(message => message._1 == 2L && message._4 == "after-sender-restart"),
        "post-restart message"
      )
      pair.a.awaitMessage(
        aConversationAfter,
        messages => messages.exists(message => message._1 == 2L && message._3 == "delivered" && message._4 == "after-sender-restart"),
        "post-restart delivered"
      )
      ensure(
        pair.a.messages(aConversationAfter) ==
          Vector((1L, "me", "delivered", "before-sender-restart"), (2L, "me", "delivered", "after-sender-restart")),
        "A complete history after sender restart"
      )
      ensure(
        pair.b.messages(bConversation) ==
          Vector((1L, "peer", "received", "before-sender-restart"), (2L, "peer", "received", "after-sender-restart")),
        "B complete history after sender restart"
      )
      "aMsgs=2 bMsgs=2 restartSeq=2"
    }

  private def scenario5(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      val aConversation = pair.a.conversationId(handshake.bUser)
      val bConversation = pair.b.conversationId(handshake.aUser)
      pair.a.sendMessage(aConversation, "before-receiver-restart")
      pair.b.awaitMessage(bConversation, _.nonEmpty, "initial message")
      pair.a.awaitMessage(aConversation, _.exists(_._3 == "delivered"), "initial delivered")
      ensure(pair.a.messages(aConversation) == Vector((1L, "me", "delivered", "before-receiver-restart")), "A initial history")
      ensure(pair.b.messages(bConversation) == Vector((1L, "peer", "received", "before-receiver-restart")), "B initial history")

      pair.b.restart()
      val bStateAfter = pair.b.state
      ensure(contact(bStateAfter, handshake.aUser)("userPub").str == handshake.aUser, "B contact missing after restart")
      val bConversationAfter = pair.b.conversationId(handshake.aUser)
      ensure(pair.b.messages(bConversationAfter) == Vector((1L, "peer", "received", "before-receiver-restart")), "B history missing")
      ensure(
        pair.a.messages(pair.a.conversationId(handshake.bUser)) == Vector((1L, "me", "delivered", "before-receiver-restart")),
        "A history changed after B restart"
      )

      pair.a.sendMessage(aConversation, "after-receiver-restart")
      pair.b.awaitMessage(
        bConversationAfter,
        messages => messages.exists(message => message._1 == 2L && message._4 == "after-receiver-restart"),
        "post-restart message"
      )
      pair.a.awaitMessage(
        aConversation,
        messages => messages.exists(message => message._1 == 2L && message._3 == "delivered" && message._4 == "after-receiver-restart"),
        "post-restart delivered"
      )
      ensure(
        pair.b.messages(bConversationAfter) ==
          Vector((1L, "peer", "received", "before-receiver-restart"), (2L, "peer", "received", "after-receiver-restart")),
        "B complete history after receiver restart"
      )
      ensure(
        pair.a.messages(aConversation) ==
          Vector((1L, "me", "delivered", "before-receiver-restart"), (2L, "me", "delivered", "after-receiver-restart")),
        "A complete history after receiver restart"
      )
      "aMsgs=2 bMsgs=2 restartSeq=2"
    }

  private def scenario6(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      pair.a.sendMessage(handshake.aConversation, "before-disconnect")
      pair.b.awaitMessage(handshake.bConversation, _.nonEmpty, "initial message")
      pair.a.awaitMessage(handshake.aConversation, _.exists(_._3 == "delivered"), "initial delivered")

      val relayPort = pair.relay.port
      pair.relay.stop()
      pair.a.awaitOnline(online = false)
      pair.b.awaitOnline(online = false)

      pair.a.sendMessage(handshake.aConversation, "while-disconnected")
      val aPending = pair.a.awaitMessage(
        handshake.aConversation,
        messages =>
          messages.exists(message =>
            message._1 == 2L && message._2 == "me" && message._3 == "pending" && message._4 == "while-disconnected"
          ),
        "offline pending"
      )
      ensure(
        aPending.exists(message => message._1 == 2L && message._3 == "pending" && message._4 == "while-disconnected"),
        "A offline message is not pending"
      )
      ensure(!pair.b.messages(handshake.bConversation).exists(_._4 == "while-disconnected"), "B received offline message too early")

      pair.relay.start(relayPort)
      pair.a.awaitOnline(online = true)
      pair.b.awaitOnline(online = true)
      val onB = pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(_._4 == "while-disconnected"),
        "offline message after reconnect",
        Duration.ofSeconds(30)
      )
      pair.a.awaitMessage(
        handshake.aConversation,
        messages => messages.exists(message => message._4 == "while-disconnected" && message._3 == "delivered"),
        "offline message delivered",
        Duration.ofSeconds(30)
      )
      ensure(onB.count(_._4 == "while-disconnected") == 1, s"offline message duplicated on B: $onB")
      s"relayPort=$relayPort aMsgs=${aPending.size} bMsgs=${onB.size}"
    }

  private def scenario7(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      val bodies = (1 to 100).map(number => f"m-$number%03d").toVector
      bodies.foreach(body => pair.a.sendMessage(handshake.aConversation, body))

      pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.count(_._2 == "peer") >= 100,
        "100 messages",
        Duration.ofSeconds(60)
      )
      pair.a.awaitMessage(
        handshake.aConversation,
        messages => messages.count(message => message._2 == "me" && message._3 == "delivered") >= 100,
        "100 delivered",
        Duration.ofSeconds(60)
      )

      val expectedB: Vector[(Long, String, String, String)] =
        bodies.zipWithIndex.map { case (body, index) => (index.toLong + 1L, "peer", "received", body) }
      val expectedA: Vector[(Long, String, String, String)] =
        bodies.zipWithIndex.map { case (body, index) => (index.toLong + 1L, "me", "delivered", body) }
      val onB = pair.b.messages(handshake.bConversation)
      val onA = pair.a.messages(handshake.aConversation)
      ensure(onB == expectedB, s"B complete history count=${onB.size} firstSeq=${onB.headOption.map(_._1)} lastSeq=${onB.lastOption.map(_._1)}")
      ensure(onA == expectedA, s"A complete history count=${onA.size} firstSeq=${onA.headOption.map(_._1)} lastSeq=${onA.lastOption.map(_._1)}")
      s"bCount=${onB.size} aDelivered=${onA.size} firstSeq=1 lastSeq=100"
    }

  private def verifySafety(pair: Pair, handshake: Handshake): Unit =
    pair.a.verifySafety(handshake.bUser)
    pair.b.verifySafety(handshake.aUser)
    pair.a.awaitSafety(handshake.bUser, verified = true)
    pair.b.awaitSafety(handshake.aUser, verified = true)

  private def assertNoPreSafetyBody(pair: Pair, handshake: Handshake, label: String): Unit =
    pair.a.sendMessage(handshake.aConversation, s"$label-before-safety")
    // Queue a command after the send and wait for its durable completion. The runtime is a single FIFO, so the send event has been
    // processed before this returns; an empty history is then meaningful without a wall-clock sleep.
    pair.a.createInvitation(None)
    ensure(pair.a.messages(handshake.aConversation).isEmpty, s"$label pre-safety body persisted on A")
    ensure(pair.b.messages(handshake.bConversation).isEmpty, s"$label pre-safety body arrived on B")

  private def expectedSafety(localUser: String, peerUser: String): String =
    val local = publicKey(localUser)
    val peer  = publicKey(peerUser)
    SafetyNumber
      .derive(local, peer)
      .fold(error => throw new AssertionError(s"safety derivation failed: $error"), identity)
      .display

  private def publicKey(value: String): Ed25519Public =
    val bytes = ByteVector.fromHex(value).getOrElse(throw new AssertionError("invalid public key hex"))
    Ed25519Public.from(bytes).fold(error => throw new AssertionError(s"invalid public key: $error"), identity)

  private def contact(state: Value, userPub: String): Value =
    state("contacts").arr.toVector
      .find(_.obj("userPub").str == userPub)
      .getOrElse(throw new AssertionError(s"contact $userPub missing: $state"))

  private def ensure(condition: Boolean, message: => String): Unit =
    if !condition then throw new AssertionError(message)

final class Receipt(directory: Path):
  Files.createDirectories(directory)
  val path: Path = directory.resolve("s1-e2e.log")
  write(s"started=${Instant.now} command=server.e2e java=${System.getProperty("java.version")} head=${gitHead()}")

  private def gitHead(): String =
    try
      val process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
      val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      process.waitFor()
      if process.exitValue() == 0 then output else "unknown"
    catch case NonFatal(_) => "unknown"

  def write(line: String): Unit =
    Files.writeString(
      path,
      line.replace('\n', ' ').replace('\r', ' ') + "\n",
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
