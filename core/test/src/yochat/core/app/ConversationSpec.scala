package yochat.core.app

import munit.FunSuite
import scodec.bits.ByteVector

class ConversationSpec extends FunSuite:

  test("Q2: apply by author sequence, buffer gaps, flush contiguous arrivals, deduplicate") {
    val peers               = AppTestSupport.connected
    val frames              = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 3)._2
    val author              = peers.alice.user.publicKey
    val device              = peers.alice.device.publicKey
    val initial             = peers.aliceState.conversations.values.head
    val messages            = frames.map(frame => BufferedMessage(frame.header.seq, author, device, frame.header.seq, ByteVector.empty, frame))
    val (one, d1, a1)       = initial.accept(messages(0))
    val (three, d2, a2)     = one.accept(messages(2))
    val (two, d3, a3)       = three.accept(messages(1))
    val (duplicate, d4, a4) = two.accept(messages(1))

    assertEquals(d1, InboundDisposition.Applied)
    assertEquals(a1.map(_.seq), Vector(1L))
    assertEquals(d2, InboundDisposition.Buffered)
    assertEquals(a2, Vector.empty)
    assertEquals(d3, InboundDisposition.Applied)
    assertEquals(a3.map(_.seq), Vector(2L, 3L))
    assertEquals(two.applied.map(_.seq), Vector(1L, 2L, 3L))
    assertEquals(duplicate, two)
    assertEquals(d4, InboundDisposition.Duplicate)
    assertEquals(a4, Vector.empty)
  }

  test("Q2: a message without precedence is not applied beyond the hole") {
    val peers                            = AppTestSupport.connected
    val frames                           = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 2)._2
    val author                           = peers.alice.user.publicKey
    val device                           = peers.alice.device.publicKey
    val initial                          = peers.aliceState.conversations.values.head
    val second                           = BufferedMessage(2L, author, device, 2L, ByteVector.empty, frames(1))
    val (buffered, disposition, applied) = initial.accept(second)
    assertEquals(disposition, InboundDisposition.Buffered)
    assertEquals(applied, Vector.empty)
    assertEquals(buffered.applied, Vector.empty)
    assertEquals(buffered.buffered.size, 1)
  }
