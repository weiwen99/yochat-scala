package yochat.transport

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.{CodingErrorAction, StandardCharsets}

import scodec.bits.ByteVector

import yochat.core.Ed25519Public

enum RelayPayload:

  case Wire(bytes: ByteVector)
  case Reply(text: String)
  case RekeyRequest(bytes: ByteVector)
  case RekeyAccept(bytes: ByteVector)

object RelayPayload:

  private val WireKind: Int         = 1
  private val ReplyKind: Int        = 2
  private val RekeyRequestKind: Int = 3
  private val RekeyAcceptKind: Int  = 4
  val MaxPayload: Int               = 1_048_774

  def encode(value: RelayPayload): Either[String, ByteVector] =
    val (kind, bytes) = value match
      case RelayPayload.Wire(payload)         => (WireKind, payload)
      case RelayPayload.Reply(text)           => (ReplyKind, ByteVector.view(text.getBytes(StandardCharsets.UTF_8)))
      case RelayPayload.RekeyRequest(payload) => (RekeyRequestKind, payload)
      case RelayPayload.RekeyAccept(payload)  => (RekeyAcceptKind, payload)
    if bytes.size > MaxPayload.toLong then Left("payload-too-large")
    else
      val out  = new ByteArrayOutputStream(bytes.size.toInt + 5)
      val data = new DataOutputStream(out)
      data.writeByte(kind)
      data.writeInt(bytes.size.toInt)
      data.write(bytes.toArray)
      data.flush()
      Right(ByteVector.view(out.toByteArray))

  def decode(value: ByteVector): Either[String, RelayPayload] =
    if value.size < 5L then Left("short-payload")
    else
      val in = new DataInputStream(new ByteArrayInputStream(value.toArray))
      try
        val kind   = in.readByte().toInt
        val length = in.readInt()
        if length < 0 || length > MaxPayload then Left("invalid-length")
        else if length.toLong != value.size - 5L then Left("length-mismatch")
        else
          val bytes   = new Array[Byte](length)
          in.readFully(bytes)
          val payload = ByteVector.view(bytes)
          kind match
            case WireKind         => Right(RelayPayload.Wire(payload))
            case ReplyKind        => decodeText(payload).map(RelayPayload.Reply.apply)
            case RekeyRequestKind => Right(RelayPayload.RekeyRequest(payload))
            case RekeyAcceptKind  => Right(RelayPayload.RekeyAccept(payload))
            case _                => Left("unknown-kind")
      catch case _: java.io.IOException => Left("invalid-payload")

  private def decodeText(value: ByteVector): Either[String, String] =
    val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    try Right(decoder.decode(java.nio.ByteBuffer.wrap(value.toArray)).toString)
    catch case _: java.nio.charset.CharacterCodingException => Left("invalid-text")

final case class RelayIncoming(from: Ed25519Public, payload: RelayPayload)

object RelayWire:

  val Register: Int    = 1
  val Data: Int        = 2
  val RegisterAck: Int = 3
  val MaxBody: Int     = RelayPayload.MaxPayload + 40

  def registerBody(device: Ed25519Public): ByteVector = device.bytes

  def dataBody(to: Ed25519Public, payload: RelayPayload): Either[String, ByteVector] =
    RelayPayload.encode(payload).map { encoded =>
      val out  = new ByteArrayOutputStream(encoded.size.toInt + 36)
      val data = new DataOutputStream(out)
      data.write(to.bytes.toArray)
      data.write(encoded.toArray)
      data.flush()
      ByteVector.view(out.toByteArray)
    }

  def forwardBody(from: Ed25519Public, payload: RelayPayload): Either[String, ByteVector] =
    dataBody(from, payload)

  def parseData(body: ByteVector): Either[String, (Ed25519Public, RelayPayload)] =
    if body.size < 36L then Left("short-data")
    else
      Ed25519Public.from(body.take(32)) match
        case Left(_)       => Left("invalid-device")
        case Right(target) =>
          RelayPayload.decode(body.drop(32)).map(payload => (target, payload))

  def writeFrame(out: DataOutputStream, kind: Int, body: ByteVector): Either[String, Unit] =
    if body.size > MaxBody.toLong then Left("body-too-large")
    else
      try
        out.writeByte(kind)
        out.writeInt(body.size.toInt)
        out.write(body.toArray)
        out.flush()
        Right(())
      catch case _: java.io.IOException => Left("write-failed")

  def readFrame(in: DataInputStream): Either[String, (Int, ByteVector)] =
    try
      val kind   = in.readByte().toInt
      val length = in.readInt()
      if length < 0 || length > MaxBody then Left("invalid-frame-length")
      else
        val bytes = new Array[Byte](length)
        in.readFully(bytes)
        Right((kind, ByteVector.view(bytes)))
    catch case _: java.io.IOException => Left("read-failed")
