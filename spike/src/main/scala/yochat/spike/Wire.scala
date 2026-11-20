package yochat.spike

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.KeyPair

/** Binary wire formats for the spike. Lengths are bounded; the relay and
  * clients never accept an unbounded blob.
  */
object Wire:
  private val Utf8   = StandardCharsets.UTF_8
  private val MaxBlob = 8 * 1024 * 1024

  def writeBytes(out: DataOutputStream, bytes: Array[Byte]): Unit =
    out.writeInt(bytes.length)
    out.write(bytes)

  def readBytes(in: DataInputStream): Array[Byte] =
    val length = in.readInt()
    require(length >= 0 && length <= MaxBlob, s"invalid blob length $length")
    val bytes = new Array[Byte](length)
    in.readFully(bytes)
    bytes

  def writeString(out: DataOutputStream, value: String): Unit =
    writeBytes(out, value.getBytes(Utf8))

  def readString(in: DataInputStream): String =
    new String(readBytes(in), Utf8)

  def encode(write: DataOutputStream => Unit): Array[Byte] =
    val bytes = new ByteArrayOutputStream()
    val out   = new DataOutputStream(bytes)
    write(out)
    out.flush()
    bytes.toByteArray

  def decode[A](bytes: Array[Byte])(read: DataInputStream => A): A =
    val in    = new DataInputStream(new ByteArrayInputStream(bytes))
    val value = read(in)
    require(in.available() == 0, s"trailing wire bytes: ${in.available()}")
    value

  final case class InvitationFields(
    version: Int,
    userPub: Array[Byte],
    devicePub: Array[Byte],
    channelLabel: String,
    channelAccount: String,
    expiresAt: Long,
    ephemeralPub: Array[Byte],
    note: String,
  )

  final case class Invitation(fields: InvitationFields, payload: Array[Byte], signature: Array[Byte]):
    def text: String = Crypto.base64(Wire.encode { out =>
      Wire.writeBytes(out, payload)
      Wire.writeBytes(out, signature)
    })

    def hash: Array[Byte] = Crypto.sha256(payload)

  object Invitation:
    def create(
      user: KeyPair,
      device: KeyPair,
      ephemeral: KeyPair,
      channelLabel: String,
      channelAccount: String,
      expiresAt: Long,
      note: String,
    ): Invitation =
      val fields = InvitationFields(
        version = 1,
        userPub = Crypto.publicBytes(user.getPublic),
        devicePub = Crypto.publicBytes(device.getPublic),
        channelLabel = channelLabel,
        channelAccount = channelAccount,
        expiresAt = expiresAt,
        ephemeralPub = Crypto.publicBytes(ephemeral.getPublic),
        note = note,
      )
      val payload   = encodeInvitationPayload(fields)
      val signature = Crypto.sign(user.getPrivate, payload)
      Invitation(fields, payload, signature)

    def parse(text: String, now: Long): Invitation =
      val bytes = Crypto.unbase64(text)
      val (payload, signature) = Wire.decode(bytes) { in =>
        (Wire.readBytes(in), Wire.readBytes(in))
      }
      val fields = decodeInvitationPayload(payload)
      require(fields.version == 1, s"unsupported invitation version ${fields.version}")
      val verifier = Crypto.ed25519Public(fields.userPub)
      require(Crypto.verify(verifier, payload, signature), "invitation signature does not verify")
      require(fields.expiresAt > now, "invitation expired")
      Invitation(fields, payload, signature)

    private def encodeInvitationPayload(fields: InvitationFields): Array[Byte] =
      Wire.encode { out =>
        out.writeInt(fields.version)
        Wire.writeBytes(out, fields.userPub)
        Wire.writeBytes(out, fields.devicePub)
        Wire.writeString(out, fields.channelLabel)
        Wire.writeString(out, fields.channelAccount)
        out.writeLong(fields.expiresAt)
        Wire.writeBytes(out, fields.ephemeralPub)
        Wire.writeString(out, fields.note)
      }

    private def decodeInvitationPayload(bytes: Array[Byte]): InvitationFields =
      Wire.decode(bytes) { in =>
        InvitationFields(
          version = in.readInt(),
          userPub = Wire.readBytes(in),
          devicePub = Wire.readBytes(in),
          channelLabel = Wire.readString(in),
          channelAccount = Wire.readString(in),
          expiresAt = in.readLong(),
          ephemeralPub = Wire.readBytes(in),
          note = Wire.readString(in),
        )
      }

  final case class ReplyFields(
    invitationHash: Array[Byte],
    userPub: Array[Byte],
    devicePub: Array[Byte],
    channelLabel: String,
    channelAccount: String,
    note: String,
  )

  object ReplyFields:
    def encode(fields: ReplyFields): Array[Byte] =
      Wire.encode { out =>
        Wire.writeBytes(out, fields.invitationHash)
        Wire.writeBytes(out, fields.userPub)
        Wire.writeBytes(out, fields.devicePub)
        Wire.writeString(out, fields.channelLabel)
        Wire.writeString(out, fields.channelAccount)
        Wire.writeString(out, fields.note)
      }

    def decode(bytes: Array[Byte]): ReplyFields =
      Wire.decode(bytes) { in =>
        ReplyFields(
          invitationHash = Wire.readBytes(in),
          userPub = Wire.readBytes(in),
          devicePub = Wire.readBytes(in),
          channelLabel = Wire.readString(in),
          channelAccount = Wire.readString(in),
          note = Wire.readString(in),
        )
      }

  final case class ReplyEnvelope(ephemeralPub: Array[Byte], nonce: Array[Byte], ciphertext: Array[Byte]):
    def text: String = Crypto.base64(Wire.encode { out =>
      Wire.writeBytes(out, ephemeralPub)
      Wire.writeBytes(out, nonce)
      Wire.writeBytes(out, ciphertext)
    })

  object ReplyEnvelope:
    def parse(text: String): ReplyEnvelope =
      val bytes = Crypto.unbase64(text)
      Wire.decode(bytes) { in =>
        ReplyEnvelope(Wire.readBytes(in), Wire.readBytes(in), Wire.readBytes(in))
      }

  final case class Message(seq: Long, nonce: Array[Byte], ciphertext: Array[Byte]):
    def bytes: Array[Byte] = Wire.encode { out =>
      out.writeLong(seq)
      Wire.writeBytes(out, nonce)
      Wire.writeBytes(out, ciphertext)
    }

  object Message:
    def decode(bytes: Array[Byte]): Message =
      Wire.decode(bytes) { in =>
        Message(in.readLong(), Wire.readBytes(in), Wire.readBytes(in))
      }
