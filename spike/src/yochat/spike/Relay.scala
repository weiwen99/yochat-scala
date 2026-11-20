package yochat.spike

import java.io.{DataInputStream, DataOutputStream}
import java.net.{ServerSocket, Socket}
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.util.control.NonFatal

final case class RelayFrame(kind: Byte, body: Array[Byte])

final case class RelayData(address: Array[Byte], payload: Array[Byte])

/** Length-prefixed TCP frames. The relay only routes by device public key; it never sees the message keys or plaintext. There is no
  * authentication or authorization in this spike; that is a known limitation.
  */
object RelayFrames:
  val Register: Byte    = 1
  val Data: Byte        = 2
  val RegisterAck: Byte = 3
  private val MaxFrame  = 8 * 1024 * 1024

  def write(out: DataOutputStream, kind: Byte, body: Array[Byte]): Unit =
    out.synchronized {
      out.writeByte(kind.toInt)
      out.writeInt(body.length)
      out.write(body)
      out.flush()
    }

  def read(in: DataInputStream): RelayFrame =
    val kind   = in.readByte()
    val length = in.readInt()
    require(length >= 0 && length <= MaxFrame, s"invalid frame length $length")
    val body   = new Array[Byte](length)
    in.readFully(body)
    RelayFrame(kind, body)

  def dataBody(to: Array[Byte], payload: Array[Byte]): Array[Byte] =
    Wire.encode { out =>
      Wire.writeBytes(out, to)
      Wire.writeBytes(out, payload)
    }

  def parseData(body: Array[Byte]): RelayData =
    Wire.decode(body) { in =>
      RelayData(Wire.readBytes(in), Wire.readBytes(in))
    }

final class RelayServer(requestedPort: Int):
  private val server   = new ServerSocket(requestedPort)
  private val clients  = new ConcurrentHashMap[String, RelayConnection]()
  private val pool     = Executors.newCachedThreadPool()
  private val acceptor = new Thread(() => acceptLoop(), "relay-accept")
  acceptor.setDaemon(true)

  def port: Int = server.getLocalPort

  def start(): Unit = acceptor.start()

  private def acceptLoop(): Unit =
    @tailrec
    def loop(): Unit =
      if !server.isClosed then
        try
          val socket = server.accept()
          pool.execute(new RelayConnection(socket, clients))
        catch case NonFatal(_) => ()
        loop()
    loop()

  def close(): Unit =
    try server.close()
    catch case NonFatal(_) => ()
    clients.values().forEach(_.close())
    pool.shutdownNow()

final private class RelayConnection(socket: Socket, clients: ConcurrentHashMap[String, RelayConnection]) extends Runnable:
  final private case class Registration(devicePub: Array[Byte], deviceKey: String)

  private val in           = new DataInputStream(socket.getInputStream)
  private val out          = new DataOutputStream(socket.getOutputStream)
  private val registration = new AtomicReference[Option[Registration]](None)

  def send(kind: Byte, body: Array[Byte]): Unit =
    try RelayFrames.write(out, kind, body)
    catch case NonFatal(_) => close()

  override def run(): Unit =
    try
      @tailrec
      def loop(): Unit =
        if !socket.isClosed then
          handle(RelayFrames.read(in))
          loop()
      loop()
    catch case NonFatal(_) => ()
    finally close()

  private def handle(frame: RelayFrame): Unit =
    frame.kind match
      case RelayFrames.Register =>
        val reg = Registration(frame.body, Crypto.hex(frame.body))
        registration.set(Some(reg))
        clients.put(reg.deviceKey, this)
        send(RelayFrames.RegisterAck, Array.emptyByteArray)
      case RelayFrames.Data     =>
        val data   = RelayFrames.parseData(frame.body)
        val target = Option(clients.get(Crypto.hex(data.address)))
        registration.get().foreach { reg =>
          target.foreach(_.send(RelayFrames.Data, RelayFrames.dataBody(reg.devicePub, data.payload)))
        }
      case _                    => ()

  def close(): Unit =
    registration.get().foreach(reg => clients.remove(reg.deviceKey, this))
    try socket.close()
    catch case NonFatal(_) => ()

final class RelayClient private (socket: Socket, devicePub: Array[Byte], onData: (Array[Byte], Array[Byte]) => Unit):
  private val in         = new DataInputStream(socket.getInputStream)
  private val out        = new DataOutputStream(socket.getOutputStream)
  private val reader     = new Thread(() => readLoop(), "relay-client")
  private val registered = new CountDownLatch(1)
  reader.setDaemon(true)

  def start(): Unit =
    RelayFrames.write(out, RelayFrames.Register, devicePub)
    reader.start()
    if !registered.await(5, TimeUnit.SECONDS) then
      close()
      throw new RuntimeException("relay registration timed out")

  def send(to: Array[Byte], payload: Array[Byte]): Unit =
    RelayFrames.write(out, RelayFrames.Data, RelayFrames.dataBody(to, payload))

  def close(): Unit =
    try socket.close()
    catch case NonFatal(_) => ()

  private def readLoop(): Unit =
    try
      @tailrec
      def loop(): Unit =
        if !socket.isClosed then
          val frame = RelayFrames.read(in)
          if frame.kind == RelayFrames.RegisterAck then registered.countDown()
          else if frame.kind == RelayFrames.Data then
            val data = RelayFrames.parseData(frame.body)
            onData(data.address, data.payload)
          loop()
      loop()
    catch case NonFatal(_) => ()
    finally close()

object RelayClient:

  def connect(
    host: String,
    port: Int,
    devicePub: Array[Byte],
    onData: (Array[Byte], Array[Byte]) => Unit,
    attempts: Int = 50
  ): RelayClient =
    @tailrec
    def attempt(remaining: Int): RelayClient =
      try
        val socket = new Socket(host, port)
        val client = new RelayClient(socket, devicePub, onData)
        client.start()
        client
      catch
        case NonFatal(_) if remaining > 1 =>
          Thread.sleep(100)
          attempt(remaining - 1)
        case NonFatal(error)              =>
          throw new RuntimeException(s"cannot connect to relay $host:$port", error)

    attempt(attempts)
