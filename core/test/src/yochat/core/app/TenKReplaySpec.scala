package yochat.core.app

import scala.concurrent.duration.*

import munit.FunSuite

import yochat.core.{Crypto, TestKeys, WireFrame}

class TenKReplaySpec extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  private val Total = 10_000
  private val Batch = AppLimits.MaxInFlight

  test("10k: protocol replay delivers every message with bounded state under 50s") {
    val peers        = AppTestSupport.connected
    val conversation = peers.aliceState.sessions.values.head.conversationId
    val aliceSession = peers.aliceState.sessions.keys.head
    val bobSession   = peers.bobState.sessions.keys.head
    val author       = peers.alice.user.publicKey
    val started      = System.nanoTime()

    val (alice, bob, _, _) = (0 until Total by Batch).foldLeft((peers.aliceState, peers.bobState, 1L, 1_000)) {
      case ((aliceState, bobState, nextId, dhSeed), offset) =>
        val count                        = math.min(Batch, Total - offset)
        val ids                          = (0 until count).map(step => nextId + step.toLong).toVector
        val sent                         = ids.foldLeft(aliceState) { (state, id) =>
          val (persisting, _) = Application.step(
            state,
            AppEvent.UserSend(id, conversation, Crypto.text(s"message-$id"), TestKeys.nonce((id % 250).toInt + 1), 0L)
          )
          val generation      = persisting.outgoing(id).generation
          Application.step(persisting, AppEvent.PersistOutboundSucceeded(id, generation, 0L))._1
        }
        val (received, acks, nextDhSeed) = ids.foldLeft((bobState, Vector.empty[WireFrame], dhSeed)) { case ((state, collected, seed), id) =>
          val (next, effects, afterSeed) =
            receive(state, bobSession, sent.outgoing(id).wire, 1_000_000L + id, TestKeys.nonce(((id + 50) % 250).toInt + 1), seed)
          val frames                     = effects.collect { case AppEffect.SendAck(_, _, frame) => frame }
          (next, collected ++ frames, afterSeed)
        }
        val (afterAcks, _, finalSeed)    = acks.foldLeft((sent, Vector.empty[AppEffect], nextDhSeed)) { case ((state, effects, seed), frame) =>
          val (next, added, afterSeed) =
            receive(state, aliceSession, frame, 2_000_000L, TestKeys.nonce(1), seed)
          (next, effects ++ added, afterSeed)
        }
        (afterAcks, received, nextId + count, finalSeed)
    }

    val elapsedMs         = (System.nanoTime() - started) / 1_000_000L
    val conversationAfter = bob.conversations.values.head
    println(
      s"TENK_REPLAY messages=$Total elapsed_ms=$elapsedMs outgoing=${alice.outgoing.size} next_seq=${conversationAfter.nextSeq.getOrElse(author, 1L)}"
    )
    assertEquals(alice.outgoing, Map.empty)
    assertEquals(conversationAfter.nextSeq.getOrElse(author, 1L), Total.toLong + 1L)
    assertEquals(conversationAfter.seen, Set.empty)
    assertEquals(conversationAfter.buffered, Map.empty)
    assert(bob.sessions(bobSession).state.skipped.size <= yochat.core.S1Limits.MaxSkipTotal)
    assert(elapsedMs < 50_000L, s"10k replay took ${elapsedMs}ms")
  }

  private def receive(
    state: AppState,
    sessionId: yochat.core.SessionId,
    frame: WireFrame,
    messageId: Long,
    nonce: yochat.core.Nonce,
    dhSeed: Int
  ): (AppState, Vector[AppEffect], Int) =
    val session         = state.sessions(sessionId)
    val needsDh         = !session.state.dhPeer.contains(frame.header.dhPub)
    val newDh           = if needsDh then Some(freshDh(dhSeed)) else None
    val (next, effects) =
      Application.step(state, AppEvent.InboundFrame(messageId, sessionId, frame, newDh, nonce, 0L))
    (next, effects, if needsDh then dhSeed + 1 else dhSeed)

  private def freshDh(seed: Int): yochat.core.X25519KeyPair =
    val privateKey = yochat.core.X25519Private
      .from(Crypto.sha256(Crypto.ascii(s"tenk-dh-$seed")).bytes)
      .fold(error => fail(s"fresh dh: $error"), identity)
    yochat.core.X25519KeyPair.fromSeed(privateKey).fold(error => fail(s"fresh dh: $error"), identity)
