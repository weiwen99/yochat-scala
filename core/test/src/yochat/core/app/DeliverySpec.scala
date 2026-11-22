package yochat.core.app

import munit.FunSuite

import yochat.core.{Crypto, TestKeys, WireFrame}

class DeliverySpec extends FunSuite:

  private def send(state: AppState, id: Long, nonceSeed: Int): AppState =
    val conversation    = state.sessions.values.head.conversationId
    val (persisting, _) = Application.step(
      state,
      AppEvent.UserSend(id, conversation, Crypto.text(s"message-$id"), TestKeys.nonce(nonceSeed), 0L)
    )
    val generation      = persisting.outgoing(id).generation
    Application.step(persisting, AppEvent.PersistOutboundSucceeded(id, generation, 0L))._1

  private def receive(
    state: AppState,
    sessionId: yochat.core.SessionId,
    frame: WireFrame,
    messageId: Long,
    nonceSeed: Int,
    newDh: Option[yochat.core.X25519KeyPair] = None
  ): (AppState, Vector[AppEffect]) =
    Application.step(state, AppEvent.InboundFrame(messageId, sessionId, frame, newDh, TestKeys.nonce(nonceSeed), 10L))

  private def ackFrame(effects: Vector[AppEffect]): WireFrame =
    effects.collectFirst { case AppEffect.SendAck(_, frame) => frame }.getOrElse(fail("missing SendAck"))

  private def gapFrame(effects: Vector[AppEffect]): WireFrame =
    effects.collectFirst { case AppEffect.SendGap(_, frame) => frame }.getOrElse(fail("missing SendGap"))

  test("a data ACK marks the message Delivered and removes it from the outbox") {
    val peers                  = AppTestSupport.connected
    val aliceSession           = peers.aliceState.sessions.keys.head
    val bobSession             = peers.bobState.sessions.keys.head
    val alice                  = send(peers.aliceState, 1L, 1)
    val wire                   = alice.outgoing(1L).wire
    val (bob, bobEffects)      = receive(peers.bobState, bobSession, wire, 101L, 2)
    val ack                    = ackFrame(bobEffects)
    val (afterAck, ackEffects) = receive(alice, aliceSession, ack, 102L, 3, Some(AppTestSupport.xpair(90)))

    assertEquals(afterAck.outgoing.get(1L), None)
    assert(ackEffects.exists {
      case AppEffect.UpdateMessageState(_, _, _, _, seq, MessageTransition.Delivered) => seq == 1L
      case _                                                                          => false
    })
    assertEquals(bob.conversations.values.head.nextSeq.getOrElse(peers.alice.user.publicKey, 1L), 2L)
  }

  test("a gap request resends only unconfirmed messages in the window") {
    val peers                  = AppTestSupport.connected
    val aliceSession           = peers.aliceState.sessions.keys.head
    val bobSession             = peers.bobState.sessions.keys.head
    val alice                  = (1 to 3).foldLeft(peers.aliceState)((state, id) => send(state, id.toLong, id))
    val (bob1, bob1Effects)    = receive(peers.bobState, bobSession, alice.outgoing(1L).wire, 201L, 4)
    val ack                    = ackFrame(bob1Effects)
    val (alice1, _)            = receive(alice, aliceSession, ack, 202L, 5, Some(AppTestSupport.xpair(91)))
    val (bob3, bob3Effects)    = receive(bob1, bobSession, alice.outgoing(3L).wire, 203L, 5)
    assert(bob3Effects.exists { case AppEffect.Notify(Notification.Waiting) => true; case _ => false })
    val gap                    = gapFrame(bob3Effects)
    val (afterGap, gapEffects) = receive(alice1, aliceSession, gap, 204L, 6)

    assert(gapEffects.exists { case AppEffect.SendWire(2L, _) => true; case _ => false })
    assert(gapEffects.exists { case AppEffect.SendWire(3L, _) => true; case _ => false })
    assertEquals(afterGap.outgoing.get(1L), None)
    assertEquals(afterGap.outgoing(2L).state, OutgoingState.InFlight)
    assertEquals(afterGap.outgoing(3L).state, OutgoingState.InFlight)
    assertEquals(afterGap.outgoing(2L).attempts, 2)
    assertEquals(afterGap.outgoing(3L).attempts, 2)
    assertEquals(bob3.conversations.values.head.nextSeq.getOrElse(peers.alice.user.publicKey, 1L), 2L)
    assertEquals(ack.header.seq, 1L)
    assertEquals(gap.header.seq, 2L)
  }

  test("replaying an applied data frame only re-ACKs and does not grow inbox state") {
    val peers                    = AppTestSupport.connected
    val bobSession               = peers.bobState.sessions.keys.head
    val alice                    = send(peers.aliceState, 1L, 7)
    val wire                     = alice.outgoing(1L).wire
    val (bob1, _)                = receive(peers.bobState, bobSession, wire, 301L, 8)
    val (bob2, duplicateEffects) =
      (1 to 20).foldLeft((bob1, Vector.empty[AppEffect])) { case ((state, effects), index) =>
        val (next, added) = receive(state, bobSession, wire, 400L + index, 8 + index)
        (next, effects ++ added)
      }
    assert(duplicateEffects.exists { case AppEffect.SendAck(_, _) => true; case _ => false })
    assertEquals(bob2.conversations.values.head.seen, Set.empty)
    assertEquals(bob2.conversations.values.head.buffered, Map.empty)
    assertEquals(bob2.outgoing, Map.empty)
  }

  test("the in-flight window bounds sends and an ACK pumps the next queued message") {
    val peers        = AppTestSupport.connected
    val aliceSession = peers.aliceState.sessions.keys.head
    val bobSession   = peers.bobState.sessions.keys.head
    val alice        = (1 to 40).foldLeft(peers.aliceState)((state, id) => send(state, id.toLong, id))
    val inFlight     = alice.outgoing.values.count(_.state == OutgoingState.InFlight)
    val queued       = alice.outgoing.values.count(_.state == OutgoingState.Queued)
    assertEquals(inFlight, AppLimits.MaxInFlight)
    assertEquals(queued, 40 - AppLimits.MaxInFlight)

    val (bob, bobEffects) = receive(peers.bobState, bobSession, alice.outgoing(1L).wire, 501L, 30)
    val ack               = ackFrame(bobEffects)
    val (afterAck, _)     = receive(alice, aliceSession, ack, 502L, 31, Some(AppTestSupport.xpair(92)))

    assertEquals(afterAck.outgoing.get(1L), None)
    assertEquals(afterAck.outgoing(33L).state, OutgoingState.InFlight)
    assertEquals(afterAck.outgoing.values.count(_.state == OutgoingState.InFlight), AppLimits.MaxInFlight)
    assertEquals(afterAck.outgoing.values.count(_.state == OutgoingState.Queued), 40 - AppLimits.MaxInFlight - 1)
    assert(bob.conversations.values.head.nextSeq.getOrElse(peers.alice.user.publicKey, 1L) == 2L)
  }

  test("the outbound cap rejects a new send without touching state") {
    val peers           = AppTestSupport.connected
    val conversation    = peers.aliceState.sessions.values.head.conversationId
    val session         = peers.aliceState.sessions.values.head
    val wire            = AppTestSupport.sendFrames(peers.alice, session.state, 1)._2.head
    val template        = OutgoingMessage(
      id = 0L,
      conversationId = conversation,
      sessionId = session.state.sessionId,
      authorPub = peers.alice.user.publicKey,
      devicePub = peers.alice.device.publicKey,
      seq = 1L,
      body = Crypto.text("full"),
      wire = wire,
      generation = 0L,
      state = OutgoingState.Queued,
      attempts = 0
    )
    val full            = peers.aliceState.copy(outgoing = (1 to AppLimits.MaxOutgoing).map(id => id.toLong -> template.copy(id = id.toLong)).toMap)
    val (next, effects) = Application.step(
      full,
      AppEvent.UserSend(10_000L, conversation, Crypto.text("rejected"), TestKeys.nonce(40), 0L)
    )
    assertEquals(next, full)
    assertEquals(effects, Vector(AppEffect.Notify(Notification.QueueFull)))
  }
