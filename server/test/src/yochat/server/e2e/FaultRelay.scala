package yochat.server.e2e

import java.io.{DataInputStream, DataOutputStream}
import java.net.{InetAddress, ServerSocket, Socket}
import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.util.control.NonFatal

import scodec.bits.ByteVector

import yochat.core.{MessageKind, Signature, WireFrame}
import yochat.transport.{RelayPayload, RelayWire}

/** Test-only relay proxy. It forwards the real relay protocol unchanged unless one armed fault matches a Data frame. It never inspects
  * ciphertext or user content.
  */
trait RelayControl:
  def port: Int
  def start(requestedPort: Int): Int
  def stop(): Unit

enum FaultKind:
  case Drop, TamperCiphertext, TamperSignature

final case class Fault(kind: FaultKind, sourceDevice: String, targetDevice: String, seq: Option[Long])

final class FaultRelay private (
  upstream: RelayHandle,
  listener: ServerSocket,
  pool: ExecutorService
) extends RelayControl:

  private val sockets        = ConcurrentHashMap.newKeySet[Socket]()
  private val fault          = new AtomicReference[Option[Fault]](None)
  private val applied        = new AtomicBoolean(false)
  private val gapSeen        = new AtomicBoolean(false)
  private val badForwarded   = new AtomicBoolean(false)
  private val retryMatched   = new AtomicBoolean(false)
  private val original       = new AtomicReference[Option[ByteVector]](None)
  private val gapAt          = new AtomicReference[Option[Long]](None)
  private val resendAt       = new AtomicReference[Option[Long]](None)

  def port: Int = listener.getLocalPort

  def start(requestedPort: Int): Int =
    throw new UnsupportedOperationException("fault relay cannot restart")

  def arm(value: Fault): Unit =
    if !fault.compareAndSet(None, Some(value)) then throw new AssertionError("fault already armed")

  def awaitApplied(timeoutSeconds: Long = 20L): Unit =
    E2E.awaitTrue("fault applied", java.time.Duration.ofSeconds(timeoutSeconds))(applied.get())

  def awaitGap(timeoutSeconds: Long = 20L): Unit =
    E2E.awaitTrue("relay observed Gap", java.time.Duration.ofSeconds(timeoutSeconds))(gapSeen.get())

  def forwardedTampered: Boolean = badForwarded.get()

  def retryMatchesOriginal: Boolean = retryMatched.get()

  def gapBeforeResend: Boolean =
    gapAt.get().exists(gap => resendAt.get().exists(resend => resend >= gap))

  def gapToResendNanos: Long =
    gapAt.get().flatMap(gap => resendAt.get().map(resend => resend - gap)).getOrElse(0L)

  def stop(): Unit =
    if !listener.isClosed then
      try listener.close()
      catch case NonFatal(_) => ()
    sockets.forEach { socket =>
      try socket.close()
      catch case NonFatal(_) => ()
    }
    pool.shutdownNow()
    try pool.awaitTermination(5, TimeUnit.SECONDS)
    catch case _: InterruptedException => Thread.currentThread().interrupt()
    upstream.stop()

  private def acceptLoop(): Unit =
    while !listener.isClosed do
      try
        val client = listener.accept()
        val server = new Socket()
        server.connect(new java.net.InetSocketAddress("127.0.0.1", upstream.port), 5_000)
        sockets.add(client)
        sockets.add(server)
        pool.execute(() => forwardClient(client, server))
        pool.execute(() => forwardServer(server, client))
      catch
        case _: java.io.IOException => ()
        case NonFatal(_)            => ()

  private def forwardClient(client: Socket, server: Socket): Unit =
    val in  = new DataInputStream(client.getInputStream)
    val out = new DataOutputStream(server.getOutputStream)
    val registered = new AtomicReference[Option[String]](None)
    try
      var running = true
      while running do
        RelayWire.readFrame(in) match
          case Left(_)             => running = false
          case Right((kind, body)) =>
            if kind == RelayWire.Register && body.size == 32L then registered.set(Some(body.toHex))
            if kind == RelayWire.Data then
              handleClientData(registered.get(), body, out)
            else writeFrame(out, kind, body)
    catch case NonFatal(_) => ()
    finally closeQuietly(client, server)

  private def forwardServer(server: Socket, client: Socket): Unit =
    val in  = new DataInputStream(server.getInputStream)
    val out = new DataOutputStream(client.getOutputStream)
    try
      var running = true
      while running do
        RelayWire.readFrame(in) match
          case Left(_)             => running = false
          case Right((kind, body)) => writeFrame(out, kind, body)
    catch case NonFatal(_) => ()
    finally closeQuietly(server, client)

  private def handleClientData(source: Option[String], body: ByteVector, out: DataOutputStream): Unit =
    RelayWire.parseData(body) match
      case Left(_)                       => writeData(out, body)
      case Right((target, payload))      =>
        payload match
          case RelayPayload.Wire(bytes) =>
            WireFrame.parse(bytes) match
              case Right(frame) =>
                if frame.header.kind == MessageKind.Gap then
                  gapSeen.set(true)
                  gapAt.compareAndSet(None, Some(System.nanoTime()))
                val matched = fault.synchronized {
                  val current = fault.get().filter(value =>
                    frame.header.kind == MessageKind.Data &&
                      source.exists(_ == value.sourceDevice) &&
                      value.targetDevice == target.bytes.toHex &&
                      value.seq.forall(_ == frame.header.seq)
                  )
                  current.foreach(_ => fault.set(None))
                  current
                }
                matched match
                  case Some(value) =>
                    original.set(Some(frame.bytes))
                    applied.set(true)
                    applyFault(value.kind, frame, target, out)
                  case None        =>
                    if applied.get() && frame.header.kind == MessageKind.Data && frame.header.seq == 1L then
                      original.get().filter(_ == frame.bytes).foreach { _ =>
                        retryMatched.set(true)
                        resendAt.compareAndSet(None, Some(System.nanoTime()))
                      }
                    writeData(out, body)
              case Left(_) => writeData(out, body)
          case _                         => writeData(out, body)

  private def applyFault(kind: FaultKind, frame: WireFrame, target: yochat.core.Ed25519Public, out: DataOutputStream): Unit =
    kind match
      case FaultKind.Drop             => ()
      case FaultKind.TamperCiphertext =>
        val tampered = WireFrame(frame.header, flipLast(frame.ciphertext), frame.signature)
        writePayload(out, target, RelayPayload.Wire(tampered.bytes))
        badForwarded.set(true)
      case FaultKind.TamperSignature  =>
        val signature = Signature
          .from(flipFirst(frame.signature.bytes))
          .fold(error => throw new AssertionError(s"signature tamper failed: $error"), identity)
        writePayload(out, target, RelayPayload.Wire(WireFrame(frame.header, frame.ciphertext, signature).bytes))
        badForwarded.set(true)

  private def writeData(out: DataOutputStream, body: ByteVector): Unit =
    writeFrame(out, RelayWire.Data, body)

  private def writePayload(out: DataOutputStream, target: yochat.core.Ed25519Public, payload: RelayPayload): Unit =
    RelayWire.dataBody(target, payload) match
      case Left(error)  => throw new AssertionError(s"relay encode failed: $error")
      case Right(value) => writeFrame(out, RelayWire.Data, value)

  private def writeFrame(out: DataOutputStream, kind: Int, body: ByteVector): Unit =
    RelayWire.writeFrame(out, kind, body) match
      case Left(error)  => throw new AssertionError(s"relay write failed: $error")
      case Right(value) => value

  private def flipLast(value: ByteVector): ByteVector =
    val array = value.toArray
    if array.isEmpty then value
    else
      array(array.length - 1) = (array(array.length - 1) ^ 0x01).toByte
      ByteVector.view(array)

  private def flipFirst(value: ByteVector): ByteVector =
    val array = value.toArray
    if array.isEmpty then value
    else
      array(0) = (array(0) ^ 0x01).toByte
      ByteVector.view(array)

  private def closeQuietly(first: Socket, second: Socket): Unit =
    try first.close()
    catch case NonFatal(_) => ()
    try second.close()
    catch case NonFatal(_) => ()

object FaultRelay:

  def start(): FaultRelay =
    val upstream = new RelayHandle
    upstream.start(0)
    val listener = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    listener.setReuseAddress(true)
    val pool  = Executors.newVirtualThreadPerTaskExecutor()
    val relay = new FaultRelay(upstream, listener, pool)
    pool.execute(() => relay.acceptLoop())
    relay
