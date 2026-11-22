package yochat.transport

import scala.concurrent.duration.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

class RelaySpec extends FunSuite:

  private val deviceA = device(0x11)
  private val deviceB = device(0x22)
  private val deviceC = device(0x33)

  test("relay forwards an opaque wire payload and reports the sender") {
    val program = RelayServer.start(0).use { server =>
      val connected = for
        a <- RelayClient.connect("127.0.0.1", server.port, deviceA)
        b <- RelayClient.connect("127.0.0.1", server.port, deviceB)
      yield (a, b)
      connected.use { case (a, b) =>
        for
          _   <- a.awaitOnline(5.seconds)
          _   <- b.awaitOnline(5.seconds)
          _   <- a.send(deviceB, RelayPayload.Wire(ByteVector.view("secret".getBytes("UTF-8"))))
          got <- b.incoming.timeout(5.seconds)
        yield
          assertEquals(got.from, deviceA)
          got.payload match
            case RelayPayload.Wire(bytes) => assertEquals(bytes, ByteVector.view("secret".getBytes("UTF-8")))
            case _                        => fail("unexpected payload")
      }
    }
    program.unsafeRunSync()
  }

  test("relay payloads cover reply and rekey request/accept") {
    val values = Vector(
      RelayPayload.Reply("reply-text"),
      RelayPayload.RekeyRequest(ByteVector.fill(32)(1)),
      RelayPayload.RekeyAccept(ByteVector.fill(32)(2))
    )
    values.foreach { value =>
      assertEquals(RelayPayload.decode(RelayPayload.encode(value).toOption.get), Right(value))
    }
  }

  test("relay payload decoder rejects truncation, trailing bytes, and oversized payloads") {
    val valid = RelayPayload.encode(RelayPayload.Wire(ByteVector.view("hello".getBytes("UTF-8")))).toOption.get
    assertEquals(RelayPayload.decode(valid), Right(RelayPayload.Wire(ByteVector.view("hello".getBytes("UTF-8")))))
    assert(RelayPayload.decode(valid.dropRight(1)).isLeft)
    assert(RelayPayload.decode(valid ++ ByteVector(0)).isLeft)
    val oversized = ByteVector(1.toByte) ++ ByteVector.fromInt(RelayPayload.MaxPayload + 1)
    assert(RelayPayload.decode(oversized).isLeft)
  }

  test("relay frame body is bounded") {
    val body = ByteVector.fill(RelayWire.MaxBody + 1)(0)
    assert(RelayWire.dataBody(deviceB, RelayPayload.Wire(ByteVector.empty)).isRight)
    assert(RelayWire.writeFrame(new java.io.DataOutputStream(java.io.ByteArrayOutputStream()), RelayWire.Data, body).isLeft)
  }

  test("relay does not deliver to an unregistered target") {
    val program = RelayServer.start(0).use { server =>
      RelayClient.connect("127.0.0.1", server.port, deviceA).use { a =>
        for
          _      <- a.awaitOnline(5.seconds)
          _      <- a.send(deviceC, RelayPayload.Wire(ByteVector.view("lost".getBytes("UTF-8"))))
          result <- a.incoming.timeout(200.millis).attempt
        yield assert(result.isLeft)
      }
    }
    program.unsafeRunSync()
  }

  test("relay client reconnects after the relay restarts") {
    val program = RelayServer.start(0).use { server =>
      val port = server.port
      RelayClient.connect("127.0.0.1", port, deviceA).use { a =>
        for
          online <- a.awaitOnline(5.seconds)
          _      <- if online then IO.unit else IO.raiseError(new AssertionError("client did not connect"))
          _      <- server.close
          _      <- awaitOffline(a, 5.seconds)
          restart = RelayServer.start(port)
          _      <- restart.use { _ =>
                      for
                        reconnected <- a.awaitOnline(5.seconds)
                        _           <- if reconnected then IO.unit else IO.raiseError(new AssertionError("client did not reconnect"))
                        _           <- a.send(deviceB, RelayPayload.Reply("after-restart"))
                      yield ()
                    }
        yield ()
      }
    }
    program.unsafeRunSync()
  }

  private def awaitOffline(client: RelayClient, timeout: FiniteDuration): IO[Unit] =
    def loop(remaining: Long): IO[Unit] =
      if remaining <= 0L then IO.raiseError(new AssertionError("client did not go offline"))
      else
        client.isOnline.flatMap {
          case false => IO.unit
          case true  => IO.sleep(10.millis) *> loop(remaining - 10L)
        }
    loop(timeout.toMillis)

  private def device(byte: Int): Ed25519Public =
    Ed25519Public.from(ByteVector.fill(32)(byte.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)
