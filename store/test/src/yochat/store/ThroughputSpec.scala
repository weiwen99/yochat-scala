package yochat.store

import cats.effect.IO
import munit.FunSuite

class ThroughputSpec extends FunSuite:

  test("G13-partial: 1,000 production store writes and reads stay under five seconds") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-throughput-")
    try
      val session      = StoreTestSupport.state(safetyVerified = false)
      val conversation = StoreTestSupport.conversationId(9)
      StoreTestSupport.withStore(directory) { store =>
        for
          _           <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _           <- store.createConversation(conversation, session.peerUserPub, 0L)
          _           <- store.insertSession(session, 0L)
          started     <- IO.delay(System.nanoTime())
          inserted    <- List.range(1, 1_001).foldLeft(IO.pure((session, 1L))) { case (acc, seq) =>
                           acc.flatMap { case (state, version) =>
                             val body = StoreTestSupport.body(s"message-$seq")
                             val wire = StoreTestSupport.wire(state, seq.toLong)
                             store
                               .appendOutbound(
                                 state,
                                 version,
                                 0L,
                                 conversation,
                                 state.peerUserPub,
                                 state.peerDevicePub,
                                 seq.toLong,
                                 body,
                                 wire,
                                 MessageState.Sent
                               )
                               .flatMap {
                                 case Left(error)  => IO.raiseError(new AssertionError(s"write $seq failed: ${error.code}"))
                                 case Right(value) => IO.pure((value.session.state, value.session.version))
                               }
                           }
                         }
          insertNs    <- IO.delay(System.nanoTime() - started)
          readStarted <- IO.delay(System.nanoTime())
          _           <- List.range(1, 1_001).foldLeft(IO.unit) { (acc, seq) =>
                           acc.flatMap(_ =>
                             store
                               .loadMessage(
                                 conversation,
                                 inserted._1.sessionId,
                                 inserted._1.peerUserPub,
                                 inserted._1.peerDevicePub,
                                 seq.toLong
                               )
                               .flatMap {
                                 case Left(error)  => IO.raiseError(new AssertionError(s"read $seq failed: ${error.code}"))
                                 case Right(value) =>
                                   val expected = StoreTestSupport.body(s"message-$seq")
                                   if value.body == expected then IO.unit
                                   else IO.raiseError(new AssertionError(s"read $seq body mismatch"))
                               }
                           )
                         }
          readNs      <- IO.delay(System.nanoTime() - readStarted)
          totalMs      = (insertNs + readNs) / 1_000_000L
          _           <-
            IO.delay(
              println(
                s"STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=${insertNs / 1_000_000L} read_ms=${readNs / 1_000_000L} total_ms=$totalMs"
              )
            )
          _           <- IO.delay(assert(totalMs < 5_000L, s"1,000 store writes+reads took ${totalMs}ms"))
        yield ()
      }
    finally StoreTestSupport.deleteTree(directory)
  }
