package yochat.server.e2e

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.KeyPairGenerator
import java.time.{Duration, Instant}

import scala.concurrent.duration.*
import scala.util.control.NonFatal

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{
  Crypto,
  DataBinding,
  DataPayload,
  Ed25519KeyPair,
  Ed25519Private,
  Ed25519Public,
  Invitation,
  Nonce,
  Ratchet,
  SafetyNumber,
  SessionId,
  X25519KeyPair,
  X25519Private
}
import yochat.store.{IdentityFile, KeyFile, KeySource, Store, StoreConfig, StoreKeys, StorePaths}
import yochat.transport.{RelayClient, RelayPayload}

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
      Contract("E2E#7", "100 messages in order, no duplicates", () => scenario7()),
      Contract("E2E#9", "wrong or expired invitation rejected", () => scenario9()),
      Contract("E2E#10", "tampered ciphertext rejected", () => scenario10()),
      Contract("E2E#11", "tampered signature rejected", () => scenario11()),
      Contract("E2E#12", "logs and diagnostics contain no secrets", () => scenario12()),
      Contract("E2E#13", "app lock and unlock", () => scenario13()),
      Contract("E2E#14", "undecryptable message triggers automatic rebuild", () => scenario14()),
      Contract("E2E#15", "dropped data frame recovered by Gap and resend", () => scenario15())
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
        val detail   = Option(error.getMessage).getOrElse("").replace('\n', ' ').take(600)
        println(s"${contract.id} FAIL ${error.getClass.getSimpleName} at $location: $detail")
        receipt.write(
          s"${contract.id} FAIL duration_ms=$elapsed failure=${error.getClass.getSimpleName} at=$location detail=$detail"
        )
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

  private def scenario9(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      ensure(pair.a.contacts.contains(handshake.bUser), "valid invitation control was not accepted")
    }
    E2EEnvironment.withPair { pair =>
      val (_, validText) = pair.a.createInvitation(Some("@B"))
      val tamperedText   = corruptBase64(validText)
      val tampered       = pair.b.tryReceiveInvitation(tamperedText, Some("@A"))
      ensure(tampered.status == 400, s"tampered invitation returned ${tampered.status}: ${tampered.body}")
      ensure(pair.b.contacts.isEmpty, s"tampered invitation created a contact: ${pair.b.state}")

      val wrongId = pair.b.tryReceiveInvitation(validText, Some("@WRONG"))
      ensure(wrongId.status == 200, s"wrong-account invitation receive returned ${wrongId.status}: ${wrongId.body}")
      val wrongAccept = pair.b.tryAcceptInvitation(wrongId.json("id").str)
      ensure(wrongAccept.status == 400, s"wrong-account invitation accept returned ${wrongAccept.status}: ${wrongAccept.body}")
      ensure(pair.b.contacts.isEmpty, s"wrong-account invitation created a contact: ${pair.b.state}")

      val expiredText    = expiredInvitationText()
      val expiredReceive = pair.b.tryReceiveInvitation(expiredText, Some("@A"))
      val rejected       =
        if expiredReceive.status == 400 then true
        else if expiredReceive.status == 200 then
          pair.b.tryAcceptInvitation(expiredReceive.json("id").str).status == 400
        else false
      ensure(rejected, s"expired invitation was not rejected: receive=${expiredReceive.status} ${expiredReceive.body}")
      ensure(pair.b.contacts.isEmpty, s"expired invitation created a contact: ${pair.b.state}")
      s"valid=1 wrong=${wrongAccept.status} tampered=${tampered.status} expired=$rejected contacts=0"
    }

  private def scenario10(): String =
    proxyFidelity()
    tamperedData(FaultKind.TamperCiphertext, "ciphertext", proxyChecked = true)

  private def proxyFidelity(): Unit =
    E2EEnvironment.withFaultPair { (pair, _) =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      pair.a.sendMessage(handshake.aConversation, "proxy-fidelity")
      pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(_._4 == "proxy-fidelity"),
        "proxy fidelity on B"
      )
      pair.a.awaitMessage(
        handshake.aConversation,
        messages => messages.exists(message => message._4 == "proxy-fidelity" && message._3 == "delivered"),
        "proxy fidelity delivered"
      )
    }

  private def scenario11(): String = tamperedData(FaultKind.TamperSignature, "signature")

  private def scenario12(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      val marker = s"log-secret-${System.nanoTime()}"
      val alias  = s"alias-secret-${System.nanoTime()}"
      pair.a.setAlias(handshake.bUser, alias)
      pair.b.setAlias(handshake.aUser, alias)
      pair.a.sendMessage(handshake.aConversation, s"body-$marker")
      pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(_._4 == s"body-$marker"),
        "secret message on B"
      )
      val error = pair.a.trySendMessage(handshake.aConversation, s"body-$marker" + ("x" * 65_536))
      ensure(error.status == 400, s"diagnostic error request returned ${error.status}")
      ensure(pair.a.logText.nonEmpty && pair.b.logText.nonEmpty, "service log missing")
      val diagnostics = Vector(
        "body"       -> s"body-$marker",
        "alias"      -> alias,
        "a-user"     -> handshake.aUser,
        "b-user"     -> handshake.bUser,
        "a-device"   -> pair.a.identityDevice,
        "b-device"   -> pair.b.identityDevice,
        "a-private"  -> devicePrivate(pair.a.dataDir).bytes.toHex,
        "b-private"  -> devicePrivate(pair.b.dataDir).bytes.toHex,
        "passphrase" -> E2E.Passphrase,
        "a-safety"   -> expectedSafety(handshake.aUser, handshake.bUser),
        "b-safety"   -> expectedSafety(handshake.bUser, handshake.aUser)
      )
      val logs = pair.a.logText + "\n" + pair.b.logText + "\n" + error.body
      diagnostics.foreach { case (label, value) =>
        ensure(!logs.contains(value), s"diagnostics leaked $label")
      }
      s"body=clean alias=clean keys=clean safety=clean errors=clean"
    }

  private def scenario13(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      val aConversation = pair.a.conversationId(handshake.bUser)
      pair.a.sendMessage(aConversation, "before-lock")
      pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(_._4 == "before-lock"),
        "pre-lock message on B"
      )
      pair.a.awaitMessage(
        aConversation,
        messages => messages.exists(message => message._4 == "before-lock" && message._3 == "delivered"),
        "pre-lock message delivered"
      )

      ensure(pair.a.lock(), "A lock failed")
      val lockedSession = pair.a.session
      ensure(!lockedSession("unlocked").bool, "A still unlocked")
      ensure(!lockedSession.obj.contains("identity"), "locked session exposed identity")
      ensure(!lockedSession.obj.contains("contacts") && !lockedSession.obj.contains("conversations"), "locked session exposed data")
      val lockedState = pair.a.tryState
      val lockedMessages = pair.a.tryMessages(aConversation)
      val lockedSend = pair.a.trySendMessage(aConversation, "while-locked")
      ensure(lockedState.status == 401, "locked state endpoint did not return 401")
      ensure(lockedMessages.status == 401, "locked messages endpoint did not return 401")
      ensure(lockedSend.status == 401, "locked send did not return 401")
      Vector(lockedState, lockedMessages, lockedSend).foreach(result => ensure(result.body.startsWith("{\"error\":"), "locked response was not code-only"))
      val wrong = pair.a.tryUnlock("wrong passphrase")
      ensure(wrong.status == 401, s"wrong passphrase returned ${wrong.status}")
      ensure(wrong.body.startsWith("{\"error\":"), "wrong-passphrase response was not code-only")
      ensure(!wrong.body.contains(E2E.Passphrase), "wrong-passphrase response echoed passphrase")
      ensure(!pair.a.session("unlocked").bool, "wrong passphrase unlocked A")

      pair.a.unlock()
      val history = pair.a.messages(aConversation)
      ensure(history.size == 1 && history.headOption.exists(_._4 == "before-lock"), "A history changed after unlock")
      pair.a.sendMessage(aConversation, "after-unlock")
      pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(_._4 == "after-unlock"),
        "post-unlock message on B"
      )
      pair.a.awaitMessage(
        aConversation,
        messages => messages.exists(message => message._4 == "after-unlock" && message._3 == "delivered"),
        "post-unlock message delivered"
      )
      s"locked=401 wrong=401 reopened=1 history=1"
    }

  private def scenario14(): String =
    E2EEnvironment.withFaultPair { (pair, fault) =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      val oldA = pair.a.activeSessionId(handshake.aConversation)
      val oldB = pair.b.activeSessionId(handshake.bConversation)
      val client = keyPair()
      val badFrame = signedData(pair.a, handshake, 1L)
      val targetDevice = publicKey(pair.b.identityDevice)
      fault.arm(
        Fault(
          FaultKind.TamperCiphertextSigned,
          client.publicKey.bytes.toHex,
          pair.b.identityDevice,
          Some(1L),
          count = 3,
          signingKey = Some(devicePrivate(pair.a.dataDir))
        )
      )
      RelayClient
        .connect("127.0.0.1", pair.relay.port, client.publicKey)
        .use(relay =>
          relay.awaitOnline(5.seconds).flatMap { online =>
            if !online then IO.raiseError(new AssertionError("test relay client did not register"))
            else
              (1 to 3).toList.foldLeft(IO.unit) { (acc, _) =>
                acc *> relay.send(targetDevice, RelayPayload.Wire(badFrame.bytes))
              }
          }
        )
        .unsafeRunSync()
      fault.awaitApplied(3, 60)
      val newA = pair.a.awaitActiveSessionChanged(handshake.aConversation, oldA, Duration.ofSeconds(60))
      val newB = pair.b.awaitActiveSessionChanged(handshake.bConversation, oldB, Duration.ofSeconds(60))
      ensure(newA != oldA && newB != oldB, s"session did not rebuild oldA=$oldA newA=$newA oldB=$oldB newB=$newB")

      pair.a.sendMessage(handshake.aConversation, "after-rebuild")
      pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(_._4 == "after-rebuild"),
        "post-rebuild message on B"
      )
      pair.a.awaitMessage(
        handshake.aConversation,
        messages => messages.exists(message => message._4 == "after-rebuild" && message._3 == "delivered"),
        "post-rebuild message delivered"
      )
      ensure(fault.forwardedTampered, "injected bad frames were not forwarded")
      ensure(!pair.b.messages(handshake.bConversation).exists(_._4 == "injected-bad-frame"), "bad body became visible")
      ensure(pair.b.messages(handshake.bConversation).count(_._4 == "after-rebuild") == 1, "post-rebuild message duplicated")
      s"auto=1 oldA=${oldA.take(8)} newA=${newA.take(8)} oldB=${oldB.take(8)} newB=${newB.take(8)}"
    }

  private def scenario15(): String =
    E2EEnvironment.withFaultPair { (pair, fault) =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      fault.arm(Fault(FaultKind.Drop, pair.a.identityDevice, pair.b.identityDevice, Some(1L)))

      pair.a.sendMessage(handshake.aConversation, "one")
      pair.a.sendMessage(handshake.aConversation, "two")
      fault.awaitApplied()
      fault.awaitGap()

      val onB = pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.count(_._2 == "peer") >= 2,
        "both messages after dropped seq=1",
        Duration.ofSeconds(30)
      )
      val onA = pair.a.awaitMessage(
        handshake.aConversation,
        messages => messages.count(message => message._2 == "me" && message._3 == "delivered") >= 2,
        "both messages delivered",
        Duration.ofSeconds(30)
      )
      val expectedB: Vector[(Long, String, String, String)] =
        Vector((1L, "peer", "received", "one"), (2L, "peer", "received", "two"))
      val expectedA: Vector[(Long, String, String, String)] =
        Vector((1L, "me", "delivered", "one"), (2L, "me", "delivered", "two"))
      ensure(onB == expectedB, s"B did not recover the dropped frame in order: $onB")
      ensure(onA == expectedA, s"A did not deliver both messages: $onA")
      ensure(fault.retryMatchesOriginal, "A's retry was not byte-identical to the dropped Data")
      ensure(fault.gapBeforeResend, "A resent before the Gap was forwarded")
      ensure(
        fault.gapToResendNanos > 0L && fault.gapToResendNanos < 1_800_000_000L,
        s"retry was not Gap-driven; gap_to_resend_ns=${fault.gapToResendNanos}"
      )
      s"dropped=1 gap=1 retry_identical=1 gap_to_resend_ms=${fault.gapToResendNanos / 1_000_000L} bCount=${onB.size} aDelivered=${onA.size}"
    }

  private def tamperedData(kind: FaultKind, label: String, proxyChecked: Boolean = false): String =
    E2EEnvironment.withFaultPair { (pair, fault) =>
      val handshake = E2EEnvironment.handshake(pair)
      verifySafety(pair, handshake)
      val body      = s"tampered-$label"
      fault.arm(Fault(kind, pair.a.identityDevice, pair.b.identityDevice, Some(1L)))

      pair.a.sendMessage(handshake.aConversation, body)
      fault.awaitApplied()
      ensure(!pair.b.messages(handshake.bConversation).exists(_._4 == body), s"B applied tampered $label")

      val onB = pair.b.awaitMessage(
        handshake.bConversation,
        messages => messages.exists(_._4 == body),
        s"retried $label message",
        Duration.ofSeconds(30)
      )
      val onA = pair.a.awaitMessage(
        handshake.aConversation,
        messages => messages.exists(message => message._4 == body && message._3 == "delivered"),
        s"delivered $label message",
        Duration.ofSeconds(30)
      )
      ensure(onB.count(_._4 == body) == 1, s"B saw $label body more than once: $onB")
      ensure(onA.count(_._4 == body) == 1, s"A saw $label body more than once: $onA")
      ensure(fault.forwardedTampered, s"proxy did not forward the tampered $label frame")
      ensure(fault.retryMatchesOriginal, s"A's retry was not byte-identical to the original $label wire")
      val proxy = if proxyChecked then "proxy_fidelity=1 " else ""
      s"${proxy}tampered=1 forwarded=1 retried=1 bCount=${onB.count(_._4 == body)} aDelivered=${onA.count(_._4 == body)}"
    }

  private def signedData(a: ServiceProcess, handshake: Handshake, seq: Long): yochat.core.WireFrame =
    val sessionId = SessionId
      .from(ByteVector.fromValidHex(a.activeSessionId(handshake.aConversation)))
      .fold(error => throw new AssertionError(s"invalid session id: $error"), identity)
    val config = StoreConfig.default(a.dataDir, KeySource.KeyFile(E2E.Passphrase.toCharArray))
    Store
      .open(config)
      .use {
        case Left(error)  => IO.raiseError(new AssertionError(s"test store open failed: $error"))
        case Right(store) =>
          store.loadSession(sessionId).flatMap {
            case Left(error)  => IO.raiseError(new AssertionError(s"test session load failed: $error"))
            case Right(record) =>
              val safety = SafetyNumber
                .derive(publicKey(handshake.aUser), publicKey(handshake.bUser))
                .fold(error => throw new AssertionError(s"safety derive failed: $error"), identity)
              val binding =
                if seq == 1L then Some(DataBinding("@A", "@B", safety.digits)) else None
              val plaintext = DataPayload
                .encode(DataPayload(binding, Crypto.text("injected-bad-frame")))
                .fold(error => throw new AssertionError(s"payload encode failed: $error"), identity)
              val nonce = Nonce
                .from(ByteVector.fill(Nonce.Length)(0x5a.toByte))
                .fold(error => throw new AssertionError(s"nonce failed: $error"), identity)
              IO.fromEither(
                Ratchet
                  .encryptUser(
                    record.state,
                    store.identity.devicePublic,
                    store.identity.devicePrivate,
                    seq,
                    nonce,
                    plaintext
                  )
                  .left
                  .map(error => new AssertionError(s"test frame encrypt failed: $error"))
              ).map(_._2)
          }
      }
      .unsafeRunSync()

  private def devicePrivate(directory: java.nio.file.Path): Ed25519Private =
    val paths = StorePaths.under(directory)
    val master = KeyFile
      .unlock(paths.keyFile, E2E.Passphrase.toCharArray)
      .unsafeRunSync()
      .fold(error => throw new AssertionError(s"test identity unlock failed: $error"), identity)
    val keys = StoreKeys.from(master)
    IdentityFile
      .load(paths.identityFile, keys.identityKey)
      .unsafeRunSync()
      .fold(error => throw new AssertionError(s"test identity load failed: $error"), identity)
      .devicePrivate

  private def expiredInvitationText(): String =
    val ephemeral = x25519(0x11.toByte)
    val ratchet   = x25519(0x22.toByte)
    Invitation
      .create(
        keyPair(),
        keyPair(),
        ephemeral.publicKey,
        ratchet.publicKey,
        "test",
        "@A",
        System.currentTimeMillis() - 60_000L,
        "expired"
      )
      .fold(error => throw new AssertionError(s"expired invitation generation failed: $error"), identity)
      .text

  private def keyPair(): Ed25519KeyPair =
    val generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val privateKey = Ed25519Private
      .from(ByteVector.view(generated.getPrivate.getEncoded).drop(16))
      .fold(error => throw new AssertionError(s"invalid test private key: $error"), identity)
    val publicKey = Ed25519Public
      .from(ByteVector.view(generated.getPublic.getEncoded).takeRight(32))
      .fold(error => throw new AssertionError(s"invalid test public key: $error"), identity)
    Ed25519KeyPair(privateKey, publicKey)

  private def x25519(seed: Byte): X25519KeyPair =
    X25519Private
      .from(ByteVector.fill(32)(seed))
      .flatMap(X25519KeyPair.fromSeed)
      .fold(error => throw new AssertionError(s"invalid test x25519 key: $error"), identity)

  private def corruptBase64(value: String): String =
    val last = value.lastOption.getOrElse('A')
    value.dropRight(1) + (if last == 'A' then 'B' else 'A')

  private def verifySafety(pair: Pair, handshake: Handshake): Unit =
    pair.a.verifySafety(handshake.bUser)
    pair.b.verifySafety(handshake.aUser)
    pair.a.awaitSafety(handshake.bUser, verified = true)
    pair.b.awaitSafety(handshake.aUser, verified = true)

  private def assertNoPreSafetyBody(pair: Pair, handshake: Handshake, label: String): Unit =
    val response = pair.a.trySendMessage(handshake.aConversation, s"$label-before-safety")
    ensure(response.status == 400, s"$label pre-safety send returned ${response.status}, expected 400")
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
