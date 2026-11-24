package yochat.transport

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

class ByteBudgetSpec extends FunSuite:

  test("byte budget admits and releases exact byte counts") {
    val program = for
      budget <- ByteBudget.create(10L)
      first  <- budget.tryReserve(7L)
      second <- budget.tryReserve(4L)
      _      <- budget.release(7L)
      third  <- budget.tryReserve(10L)
      used   <- budget.currentBytes
    yield (first, second, third, used)
    assertEquals(program.unsafeRunSync(), (true, false, true, 10L))
  }

  test("relay client drops a frame when its admitted-byte budget is exhausted") {
    val deviceA = device(0x11)
    val deviceB = device(0x22)
    val limit   = RelayPayload.MaxPayload.toLong + 5L
    val frame   = RelayPayload.Wire(ByteVector.fill(RelayPayload.MaxPayload)(0))

    val program = RelayServer.start(0).use { server =>
      (for
        a <- RelayClient.connect("127.0.0.1", server.port, deviceA)
        b <- RelayClient.connect("127.0.0.1", server.port, deviceB, maxIncomingBytes = limit)
      yield (a, b)).use { case (a, b) =>
        for
          _      <- a.awaitOnline(5.seconds)
          _      <- b.awaitOnline(5.seconds)
          _      <- a.send(deviceB, frame)
          _      <- awaitBytes(b, limit)
          _      <- a.send(deviceB, frame)
          _      <- IO.sleep(100.millis)
          first  <- b.incoming.timeout(5.seconds)
          second <- b.incoming.timeout(300.millis).attempt
        yield
          assertEquals(first.payload, frame)
          assert(second.isLeft, second.toString)
      }
    }
    program.unsafeRunSync()
  }

  private def awaitBytes(client: RelayClient, expected: Long): IO[Unit] =
    def loop(remaining: Long): IO[Unit] =
      client.admittedBytes.flatMap { current =>
        if current == expected then IO.unit
        else if remaining <= 0L then IO.raiseError(new AssertionError(s"byte budget not reached: $current"))
        else IO.sleep(10.millis) *> loop(remaining - 10L)
      }
    loop(5_000L)

  private def device(byte: Int): Ed25519Public =
    Ed25519Public.from(ByteVector.fill(32)(byte.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)
