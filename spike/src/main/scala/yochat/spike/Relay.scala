package yochat.spike

import java.io.{DataInputStream, DataOutputStream}
import java.net.{ServerSocket, Socket}
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, Executors, TimeUnit}
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Length-prefixed TCP frames. The relay only routes by device public key; it
  * never sees the message keys or plaintext. There is no authentication or
  * authorization in this spike; that is a known limitation.
  */
object RelayFrames:
  val Register: Byte    = 1
  val Data: Byte        = 2
  val RegisterAck: Byte = 3
  private val MaxFrame = 8 * 1024 * 1024

  def write(out: DataOutputStream, kind: Byte, body: Array[Byte]): Unit =
    out.synchronized {
      out.writeByte(kind.toInt)
      out.writeInt(body.length)
      out.write(body)
      out.flush()
    }

  def read(in: DataInputStream): (Byte, Array[Byte]) =
    val kind   = in.readByte()
    val length = in.readInt()
    require(length >= 0 && length <= MaxFrame, s"invalid frame length $length")
    val body = new Array[Byte](length)
    in.readFully(body)
    (kind, body)

  def dataBody(to: Array[Byte], payload: Array[Byte]): Array[Byte] =
    Wire.encode { out =>
      Wire.writeBytes(out, to)
      Wire.writeBytes(out, payload)
    }

  def parseData(body: Array[Byte]): (Array[Byte], Array[Byte]) =
    Wire.decode(body) { in =>
      (Wire.readBytes(in), Wire.readBytes(in))
    }

final class RelayServer(requestedPort: Int):
  private val server = new ServerSocket(requestedPort)
  private val clients = new ConcurrentHashMap[String, RelayConnection]()
  private val pool    = Executors.newCachedThreadPool()
  private val acceptor = new Thread(() => acceptLoop(), "relay-accept")
  acceptor.setDaemon(true)

  def port: Int = server.getLocalPort

  def start(): Unit = acceptor.start()

  private def acceptLoop(): Unit =
    while !server.isClosed do
      try
        val socket = server.accept()
        pool.execute(new RelayConnection(socket, clients))
      catch
        case NonFatal(_) => ()

  def close(): Unit =
    try server.close()
    catch case NonFatal(_) => ()
    clients.values().forEach(_.close())
    pool.shutdownNow()

private final class RelayConnection(socket: Socket, clients: ConcurrentHashMap[String, RelayConnection]) extends Runnable:
  private val in  = new DataInputStream(socket.getInputStream)
  private val out = new DataOutputStream(socket.getOutputStream)
  @volatile private var devicePub: Array[Byte] = Array.emptyByteArray
  @volatile private var deviceKey: String       = ""

  def send(kind: Byte, body: Array[Byte]): Unit =
    try RelayFrames.write(out, kind, body)
    catch case NonFatal(_) => close()

  override def run(): Unit =
    try
      while !socket.isClosed do
        val (kind, body) = RelayFrames.read(in)
        kind match
          case RelayFrames.Register =>
            devicePub = body
            deviceKey = Crypto.hex(body)
            clients.put(deviceKey, this)
            send(RelayFrames.RegisterAck, Array.emptyByteArray)
          case RelayFrames.Data =>
            val (to, payload) = RelayFrames.parseData(body)
            val target        = clients.get(Crypto.hex(to))
            if target != null then target.send(RelayFrames.Data, RelayFrames.dataBody(devicePub, payload))
          case _ => ()
    catch
      case NonFatal(_) => ()
    finally close()

  def close(): Unit =
    if deviceKey.nonEmpty then clients.remove(deviceKey, this)
    try socket.close()
    catch case NonFatal(_) => ()

final class RelayClient private (socket: Socket, devicePub: Array[Byte], onData: (Array[Byte], Array[Byte]) => Unit):
  private val in  = new DataInputStream(socket.getInputStream)
  private val out = new DataOutputStream(socket.getOutputStream)
  private val reader = new Thread(() => readLoop(), "relay-client")
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
      while !socket.isClosed do
        val (kind, body) = RelayFrames.read(in)
        if kind == RelayFrames.RegisterAck then registered.countDown()
        else if kind == RelayFrames.Data then
          val (from, payload) = RelayFrames.parseData(body)
          onData(from, payload)
    catch
      case NonFatal(_) => ()
    finally close()

object RelayClient:
  def connect(
    host: String,
    port: Int,
    devicePub: Array[Byte],
    onData: (Array[Byte], Array[Byte]) => Unit,
    attempts: Int = 50,
  ): RelayClient =
    @tailrec
    def attempt(remaining: Int): RelayClient =
      try
        val socket = new Socket(host, port)
        val client = new RelayClient(socket, devicePub, onData)
        client.start()
        client
      catch
        case NonFatal(error) =>
          if remaining <= 1 then throw new RuntimeException(s"cannot connect to relay $host:$port", error)
          Thread.sleep(100)
          attempt(remaining - 1)
    attempt(attempts)
