package yochat.core.app

import munit.FunSuite
import scodec.bits.ByteVector

class ConversationSpec extends FunSuite:

  test("Q2: apply by author sequence, buffer gaps, flush contiguous arrivals, deduplicate") {
    val peers     = AppTestSupport.connected
    val frames    = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 3)._2
    val author    = peers.alice.user.publicKey
    val device    = peers.alice.device.publicKey
    val initial   = peers.aliceState.conversations.values.head
    val messages  = frames.map(frame => BufferedMessage(frame.header.seq, author, device, frame.header.seq, ByteVector.empty, frame))
    val first     = initial.accept(messages(0))
    val third     = first.conversation.accept(messages(2))
    val second    = third.conversation.accept(messages(1))
    val duplicate = second.conversation.accept(messages(1))

    assertEquals(first.disposition, InboundDisposition.Applied)
    assertEquals(first.applied.map(_.seq), Vector(1L))
    assertEquals(third.disposition, InboundDisposition.Buffered)
    assertEquals(third.applied, Vector.empty)
    assertEquals(second.disposition, InboundDisposition.Applied)
    assertEquals(second.applied.map(_.seq), Vector(2L, 3L))
    assertEquals(second.conversation.nextSeq.getOrElse(author, 1L), 4L)
    assertEquals(duplicate.disposition, InboundDisposition.Duplicate)
    assertEquals(duplicate.applied, Vector.empty)
    assertEquals(duplicate.conversation, second.conversation)
  }

  test("Q2: a message without precedence is not applied beyond the hole") {
    val peers   = AppTestSupport.connected
    val frames  = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 2)._2
    val author  = peers.alice.user.publicKey
    val device  = peers.alice.device.publicKey
    val initial = peers.aliceState.conversations.values.head
    val second  = BufferedMessage(2L, author, device, 2L, ByteVector.empty, frames(1))
    val result  = initial.accept(second)
    assertEquals(result.disposition, InboundDisposition.Buffered)
    assertEquals(result.applied, Vector.empty)
    assertEquals(result.conversation.nextSeq.getOrElse(author, 1L), 1L)
    assertEquals(result.conversation.buffered.size, 1)
    assertEquals(result.gap, Some(1L))
  }
