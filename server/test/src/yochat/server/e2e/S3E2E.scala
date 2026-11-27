package yochat.server.e2e

import java.sql.DriverManager
import java.time.Duration

import scala.util.Using
import scala.util.control.NonFatal

import yochat.store.StorePaths

/** Fixed S3-A contract: block stops both directions and writes nothing, unblock resumes, deleting a contact keeps
  * the block record and history, and a new invitation re-adds the peer as an unverified relationship.
  */
object S3E2E:

  private val label = "S3E2E"

  def main(args: Array[String]): Unit =
    val started  = System.nanoTime()
    val evidence =
      try run()
      catch
        case NonFatal(error) =>
          println(s"$label FAIL ${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}")
          error.printStackTrace()
          System.exit(1)
          ""
    val elapsed = (System.nanoTime() - started) / 1_000_000L
    println(s"$label PASS duration_ms=$elapsed evidence=$evidence")

  private def run(): String =
    E2EEnvironment.withPair { pair =>
      val handshake = E2EEnvironment.handshake(pair)
      pair.a.verifySafety(handshake.bUser)
      pair.b.verifySafety(handshake.aUser)
      pair.a.awaitSafety(handshake.bUser, verified = true)
      pair.b.awaitSafety(handshake.aUser, verified = true)
      val http   = new Http()
      val before = messageCount(pair.a, handshake.aConversation)
      val seqs   = nextSeqs(pair.a)

      val blocked = http.post(s"${pair.a.base}/api/contacts/${handshake.bUser}/block", ujson.Obj())
      ensure(blocked.ok, s"block failed: ${blocked.status} ${blocked.body}")
      ensure(contactBlocked(pair.a, handshake.bUser), "contact is not shown as blocked")
      ensure(pair.a.state("blockedPeers").arr.exists(_.obj("userPub").str == handshake.bUser), "blockedPeers missing the peer")

      (1 to 3).foreach(index => pair.b.sendMessage(handshake.bConversation, s"blocked-$index"))
      Thread.sleep(2_500L)
      ensure(messageCount(pair.a, handshake.aConversation) == before, "blocked inbound wrote messages")
      ensure(nextSeqs(pair.a) == seqs, "blocked inbound advanced the session")

      pair.a.sendMessage(handshake.aConversation, "blocked-outbound")
      E2E.awaitTrue("blocked outbound fails", Duration.ofSeconds(25)) {
        pair.a.messages(handshake.aConversation).exists(message => message._4 == "blocked-outbound" && message._3 == "failed")
      }
      ensure(!pair.b.messages(handshake.bConversation).exists(_._4 == "blocked-outbound"), "blocked outbound reached the peer")

      val unblocked = http.post(s"${pair.a.base}/api/contacts/${handshake.bUser}/unblock", ujson.Obj())
      ensure(unblocked.ok, s"unblock failed: ${unblocked.status} ${unblocked.body}")
      pair.b.sendMessage(handshake.bConversation, "after-unblock")
      E2E.awaitTrue("unblock resumes delivery", Duration.ofSeconds(15)) {
        pair.a.messages(handshake.aConversation).exists(_._4 == "after-unblock")
      }

      val reblocked = http.post(s"${pair.a.base}/api/contacts/${handshake.bUser}/block", ujson.Obj())
      ensure(reblocked.ok, s"reblock failed: ${reblocked.status} ${reblocked.body}")
      val deleted = http.post(
        s"${pair.a.base}/api/contacts/${handshake.bUser}/delete",
        ujson.Obj("deleteHistory" -> false)
      )
      ensure(deleted.ok, s"delete failed: ${deleted.status} ${deleted.body}")
      ensure(!pair.a.state("contacts").arr.exists(_.obj("userPub").str == handshake.bUser), "deleted contact still visible")
      ensure(pair.a.state("blockedPeers").arr.exists(_.obj("userPub").str == handshake.bUser), "deleting a blocked contact dropped the block record")
      ensure(
        pair.a.state("conversations").arr.exists(_.obj("id").str == handshake.aConversation),
        "kept history lost its conversation"
      )
      val afterDelete = messageCount(pair.a, handshake.aConversation)
      pair.b.sendMessage(handshake.bConversation, "after-delete")
      Thread.sleep(2_500L)
      ensure(messageCount(pair.a, handshake.aConversation) == afterDelete, "inbound after delete wrote messages")

      val unblockedAgain = http.post(s"${pair.a.base}/api/contacts/${handshake.bUser}/unblock", ujson.Obj())
      ensure(unblockedAgain.ok, s"second unblock failed: ${unblockedAgain.status} ${unblockedAgain.body}")
      val (invitationId, invitationText) = pair.b.createInvitation(Some("@A"))
      pair.a.receiveInvitation(invitationText, Some("@B"))
      pair.a.acceptInvitation(invitationId)
      E2E.awaitTrue("re-add creates an unverified relationship", Duration.ofSeconds(20)) {
        pair.a.state("contacts").arr.exists { value =>
          value.obj("userPub").str == handshake.bUser &&
          !value.obj("safetyVerified").bool &&
          value.obj("recreated").bool
        }
      }
      ensure(
        pair.b.state("contacts").arr.exists { value =>
          value.obj("userPub").str == handshake.aUser && value.obj("confirmation").str == "account-mismatch"
        },
        "a second reply to an existing contact must stay AccountMismatch (ruling 8a)"
      )

      val messagesA = messageCount(pair.a, handshake.aConversation)
      s"messages_before=$before messages_after_delete=$afterDelete messages_final=$messagesA"
    }

  private def contactBlocked(service: ServiceProcess, user: String): Boolean =
    service.state("contacts").arr.exists { value =>
      value.obj("userPub").str == user && value.obj("blocked").bool
    }

  private def nextSeqs(service: ServiceProcess): Map[String, Long] =
    service.state("sessions").arr.toVector.map { value =>
      value.obj("id").str -> asLong(value.obj("nextSeq"))
    }.toMap

  private def asLong(value: ujson.Value): Long =
    value match
      case ujson.Num(number) => number.toLong
      case ujson.Str(text)   => text.toLongOption.getOrElse(0L)
      case _                 => 0L

  private def messageCount(service: ServiceProcess, conversation: String): Long =
    val database = StorePaths.under(service.dataDir).database
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:${database.toAbsolutePath}")) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery(s"SELECT count(*) FROM messages WHERE conversation_id = X'$conversation'")) { result =>
          if result.next() then result.getLong(1) else 0L
        }
      }
    }

  private def ensure(condition: Boolean, message: => String): Unit =
    if !condition then throw new AssertionError(message)
