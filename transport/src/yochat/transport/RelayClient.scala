package yochat.transport

import java.io.{DataInputStream, DataOutputStream}
import java.net.{InetSocketAddress, Socket}

import scala.concurrent.duration.*

import cats.effect.{FiberIO, IO, Ref, Resource}
import cats.effect.std.{Queue, Semaphore}

import yochat.core.Ed25519Public

final private[transport] class TransportError(message: String) extends RuntimeException(message)

/** A stable relay client. One supervisor keeps at most one registered socket; readers hand parsed payloads to a bounded queue. A broken socket
  * is closed and reconnected with capped backoff. The relay still has no authentication; it only routes by registered device key.
  */
final class RelayClient private (
  incomingQueue: Queue[IO, RelayIncoming],
  deviceIncomingQueue: Queue[IO, RelayIncoming],
  updatesQueue: Queue[IO, Boolean],
  current: Ref[IO, Option[RelayConnection]],
  closed: Ref[IO, Boolean],
  inboundBudget: ByteBudget,
  supervisor: FiberIO[Unit]
):

  def send(to: Ed25519Public, payload: RelayPayload): IO[Unit] =
    RelayClient.readRef(current).flatMap {
      case None             => IO.raiseError(new TransportError("relay-offline"))
      case Some(connection) =>
        connection.send(to, payload).handleErrorWith { error =>
          connection.close *> IO.raiseError(error)
        }
    }

  def incoming: IO[RelayIncoming] =
    incomingQueue.take.flatMap(value => inboundBudget.release(value.admittedBytes).as(value))

  /** S2 device-sync frames are routed away from the S1 session runtime. */
  def deviceIncoming: IO[RelayIncoming] =
    deviceIncomingQueue.take.flatMap(value => inboundBudget.release(value.admittedBytes).as(value))

  private[transport] def admittedBytes: IO[Long] = inboundBudget.currentBytes

  def updates: IO[Boolean] = updatesQueue.take

  def isOnline: IO[Boolean] = RelayClient.readRef(current).map(_.isDefined)

  def awaitOnline(timeout: FiniteDuration): IO[Boolean] =
    def loop(remainingMillis: Long): IO[Boolean] =
      if remainingMillis <= 0L then IO.pure(false)
      else
        isOnline.flatMap {
          case true  => IO.pure(true)
          case false => IO.sleep(10.millis) *> loop(remainingMillis - 10L)
        }
    loop(timeout.toMillis)

  def close: IO[Unit] =
    closed.set(true) *> supervisor.cancel *> current.getAndSet(None).flatMap {
      case None             => IO.unit
      case Some(connection) => connection.close
    }

object RelayClient:

  private[transport] def readRef[A](ref: Ref[IO, A]): IO[A] =
    ref.get // scalafix:ok DisableSyntax.no-unsafe-get

  private val InitialBackoff: FiniteDuration = 100.millis
  private val MaxBackoff: FiniteDuration     = 5.seconds
  private val ConnectTimeoutMs: Int          = 5_000

  val DefaultMaxIncomingBytes: Long = 8L * 1024L * 1024L

  def connect(
    host: String,
    port: Int,
    localDevice: Ed25519Public,
    capacity: Int = 1_024,
    maxIncomingBytes: Long = DefaultMaxIncomingBytes
  ): Resource[IO, RelayClient] =
    Resource.make {
      for
        incoming       <- Queue.bounded[IO, RelayIncoming](math.max(1, capacity))
        deviceIncoming <- Queue.bounded[IO, RelayIncoming](math.max(1, capacity))
        updates        <- Queue.bounded[IO, Boolean](16)
        current        <- Ref.of[IO, Option[RelayConnection]](None)
        closed         <- Ref.of[IO, Boolean](false)
        inboundBudget  <- ByteBudget.create(maxIncomingBytes)
        supervisor     <- supervise(host, port, localDevice, incoming, deviceIncoming, updates, current, closed, inboundBudget).start
      yield new RelayClient(incoming, deviceIncoming, updates, current, closed, inboundBudget, supervisor)
    }(_.close)

  private def supervise(
    host: String,
    port: Int,
    localDevice: Ed25519Public,
    incoming: Queue[IO, RelayIncoming],
    deviceIncoming: Queue[IO, RelayIncoming],
    updates: Queue[IO, Boolean],
    current: Ref[IO, Option[RelayConnection]],
    closed: Ref[IO, Boolean],
    inboundBudget: ByteBudget
  ): IO[Unit] =
    def loop(backoff: FiniteDuration): IO[Unit] =
      RelayClient.readRef(closed).flatMap {
        case true  => IO.unit
        case false =>
          openOnce(host, port, localDevice).attempt.flatMap {
            case Left(_)      =>
              updates.tryOffer(false) *> IO.sleep(backoff) *> loop(nextBackoff(backoff))
            case Right(value) =>
              RelayClient.readRef(closed).flatMap {
                case true  => value.close
                case false =>
                  current.set(Some(value)) *> updates.tryOffer(true) *>
                    value.readLoop(incoming, deviceIncoming, inboundBudget).start.flatMap(_.join) *>
                    value.close *> current.set(None) *> updates.tryOffer(false) *> IO.sleep(backoff) *> loop(InitialBackoff)
              }
          }
      }
    loop(InitialBackoff)

  private def nextBackoff(value: FiniteDuration): FiniteDuration =
    val doubled = value * 2
    if doubled > MaxBackoff then MaxBackoff else doubled

  private def openOnce(host: String, port: Int, localDevice: Ed25519Public): IO[RelayConnection] =
    IO.blocking(new Socket()).flatMap { socket =>
      val cleanup = IO.blocking(socket.close()).attempt.void
      IO.blocking {
        socket.connect(new InetSocketAddress(host, port), ConnectTimeoutMs)
        socket.setSoTimeout(ConnectTimeoutMs)
      }.flatMap { _ =>
        val in  = new DataInputStream(socket.getInputStream)
        val out = new DataOutputStream(socket.getOutputStream)
        for
          writeLock <- Semaphore[IO](1)
          _         <- register(in, out, localDevice)
          _         <- IO.blocking(socket.setSoTimeout(0))
        yield new RelayConnection(socket, in, out, writeLock)
      }.onCancel(cleanup)
        .handleErrorWith(error => cleanup *> IO.raiseError(error))
    }

  private def register(in: DataInputStream, out: DataOutputStream, localDevice: Ed25519Public): IO[Unit] =
    RelayWire.writeFrame(out, RelayWire.Register, RelayWire.registerBody(localDevice)) match
      case Left(error) => IO.raiseError(new TransportError(error))
      case Right(_)    =>
        IO.blocking(RelayWire.readFrame(in)).flatMap {
          case Right((RelayWire.RegisterAck, _)) => IO.unit
          case _                                 => IO.raiseError(new TransportError("registration-failed"))
        }

final private[transport] class RelayConnection(
  socket: Socket,
  in: DataInputStream,
  out: DataOutputStream,
  writeLock: Semaphore[IO]
):

  def send(to: Ed25519Public, payload: RelayPayload): IO[Unit] =
    RelayWire.dataBody(to, payload) match
      case Left(error) => IO.raiseError(new TransportError(error))
      case Right(body) =>
        writeLock.permit.use { _ =>
          IO.blocking(RelayWire.writeFrame(out, RelayWire.Data, body))
            .timeout(5.seconds)
            .flatMap {
              case Left(error) => IO.raiseError(new TransportError(error))
              case Right(_)    => IO.unit
            }
            .handleErrorWith(error => close *> IO.raiseError(error))
        }

  def readLoop(
    incoming: Queue[IO, RelayIncoming],
    deviceIncoming: Queue[IO, RelayIncoming],
    budget: ByteBudget
  ): IO[Unit] =
    def admit(from: Ed25519Public, payload: RelayPayload): IO[Unit] =
      val bytes = RelayPayload.byteSize(payload)
      val queue =
        payload match
          case _: RelayPayload.DeviceSync => deviceIncoming
          case _                          => incoming
      budget.tryReserve(bytes).flatMap {
        case false => IO.unit
        case true  =>
          queue
            .tryOffer(RelayIncoming(from, payload, bytes))
            .flatMap {
              case true  => IO.unit
              case false => budget.release(bytes)
            }
      }

    def loop(): IO[Unit] =
      IO.blocking(RelayWire.readFrame(in)).flatMap {
        case Left(_)                       => IO.unit
        case Right((RelayWire.Data, body)) =>
          RelayWire.parseData(body) match
            case Left(_)                => loop()
            case Right((from, payload)) => admit(from, payload) *> loop()
        case Right(_)                      => loop()
      }
    loop().handleErrorWith(_ => IO.unit)

  def close: IO[Unit] =
    IO.blocking(socket.close()).attempt.void
