package yochat.transport

import java.io.{DataInputStream, DataOutputStream}
import java.net.{ServerSocket, Socket}
import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import cats.effect.{IO, Resource}
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

/** Minimal relay-only TCP forwarder. It sees routing device keys and opaque payload bytes; it never parses message ciphertext, plaintext, or
  * session state. Connections are bounded; no store or account service is involved.
  */
final class RelayServer private (
  server: ServerSocket,
  clients: ConcurrentHashMap[String, RelayServerConnection],
  pool: ExecutorService,
  active: AtomicInteger
):

  def port: Int = server.getLocalPort

  def close: IO[Unit] =
    IO.blocking {
      try server.close()
      catch case _: java.io.IOException => ()
      clients.values().forEach(_.closeUnsafe())
      pool.shutdownNow()
    }

  private[transport] def acceptLoop: IO[Unit] =
    IO.blocking(server.accept()).attempt.flatMap {
      case Left(_)       => IO.unit
      case Right(socket) =>
        if active.incrementAndGet() > RelayServer.MaxConnections then
          active.decrementAndGet()
          IO.blocking(socket.close()).attempt.void *> acceptLoop
        else
          val connection = new RelayServerConnection(socket, clients, active)
          IO.blocking(pool.execute(connection)).attempt.flatMap {
            case Left(_)  => IO.blocking(connection.closeUnsafe()).attempt.void
            case Right(_) => IO.unit
          } *> acceptLoop
    }

object RelayServer:

  private val MaxConnections: Int = 1_024

  def start(port: Int): Resource[IO, RelayServer] =
    Resource.make {
      IO.blocking {
        val server  = new ServerSocket()
        server.setReuseAddress(true)
        server.bind(new java.net.InetSocketAddress(port))
        val clients = new ConcurrentHashMap[String, RelayServerConnection]()
        val pool    = Executors.newVirtualThreadPerTaskExecutor()
        val active  = new AtomicInteger(0)
        val relay   = new RelayServer(server, clients, pool, active)
        relay
      }.flatTap(relay => relay.acceptLoop.start.void)
    }(_.close)

final private class RelayServerConnection(
  socket: Socket,
  clients: ConcurrentHashMap[String, RelayServerConnection],
  active: AtomicInteger
) extends Runnable:

  private val in         = new DataInputStream(socket.getInputStream)
  private val out        = new DataOutputStream(socket.getOutputStream)
  private val writeLock  = new Object
  private val closed     = new AtomicBoolean(false)
  private val registered = new AtomicReference[Option[Ed25519Public]](None)

  override def run(): Unit =
    try
      socket.setSoTimeout(5_000)
      @scala.annotation.tailrec
      def loop(): Unit =
        if !socket.isClosed then
          RelayWire.readFrame(in) match
            case Left(_)             => closeUnsafe()
            case Right((kind, body)) =>
              handle(kind, body)
              loop()
      loop()
    catch case _: java.io.IOException => ()
    finally closeUnsafe()

  private def handle(kind: Int, body: ByteVector): Unit =
    kind match
      case RelayWire.Register =>
        if body.size != 32L || registered.get().isDefined then closeUnsafe()
        else
          Ed25519Public.from(body) match
            case Left(_)       => closeUnsafe()
            case Right(device) =>
              sendUnsafe(RelayWire.RegisterAck, ByteVector.empty)
              if !closed.get() then
                socket.setSoTimeout(0)
                registered.set(Some(device))
                Option(clients.put(device.bytes.toHex, this)).foreach { previous =>
                  if previous ne this then previous.closeUnsafe()
                }
      case RelayWire.Data     =>
        registered.get().foreach { source =>
          RelayWire.parseData(body) match
            case Left(_)                  => ()
            case Right((target, payload)) =>
              Option(clients.get(target.bytes.toHex)).foreach { connection =>
                RelayWire.forwardBody(source, payload).foreach { forwarded =>
                  connection.sendUnsafe(RelayWire.Data, forwarded)
                }
              }
        }
      case _                  => ()

  def sendUnsafe(kind: Int, body: ByteVector): Unit =
    writeLock.synchronized {
      if !closed.get() then
        RelayWire.writeFrame(out, kind, body) match
          case Left(_)  => closeUnsafe()
          case Right(_) => ()
    }

  def closeUnsafe(): Unit =
    if closed.compareAndSet(false, true) then
      registered.get().foreach(device => clients.remove(device.bytes.toHex, this))
      try socket.close()
      catch case _: java.io.IOException => ()
      active.decrementAndGet()
