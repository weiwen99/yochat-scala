package yochat.spike

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.Arrays

import scala.annotation.tailrec
import scala.util.control.NonFatal

/** S1 session keys. The two directional chains are a symmetric hash ratchet: each message key is HKDF(chain, "message") and the chain advances
  * with HKDF(chain, "chain"). This provides forward secrecy for the chain but does not implement a DH ratchet or group membership. That is a
  * deliberate spike limitation, recorded in ADR-0005.
  */
final case class Session(root: Array[Byte], a2b: Array[Byte], b2a: Array[Byte])

object Session:

  def fromRoot(root: Array[Byte]): Session =
    val empty = Array.emptyByteArray
    Session(
      root = root,
      a2b = Crypto.hkdf(root, empty, "a->b", 32),
      b2a = Crypto.hkdf(root, empty, "b->a", 32)
    )

final case class ReplyResult(
  envelope: Wire.ReplyEnvelope,
  text: String,
  session: Session,
  fields: Wire.ReplyFields
)

/** Invitation/reply exchange. The reply is signed, then encrypted to the invitation's one-time X25519 key. The session root binds both texts.
  */
object Reply:
  private val Utf8 = StandardCharsets.UTF_8

  def create(
    invitation: Wire.Invitation,
    user: KeyPair,
    device: KeyPair,
    ephemeral: KeyPair,
    channelLabel: String,
    channelAccount: String,
    note: String
  ): ReplyResult =
    val invitationHash = invitation.hash
    val shared         = Crypto.agree(ephemeral.getPrivate, Crypto.x25519Public(invitation.fields.ephemeralPub))
    val replyKey       = Crypto.hkdf(shared, invitationHash, "yochat-s1-reply", 32)
    val fields         = Wire.ReplyFields(
      invitationHash = invitationHash,
      userPub = Crypto.publicBytes(user.getPublic),
      devicePub = Crypto.publicBytes(device.getPublic),
      channelLabel = channelLabel,
      channelAccount = channelAccount,
      note = note
    )
    val plain          = Wire.ReplyFields.encode(fields)
    val signature      = Crypto.sign(user.getPrivate, plain)
    val nonce          = Crypto.randomBytes(12)
    val ciphertext     = Crypto.aeadEncrypt(replyKey, nonce, invitationHash, plain ++ signature)
    val envelope       = Wire.ReplyEnvelope(Crypto.publicBytes(ephemeral.getPublic), nonce, ciphertext)
    val text           = envelope.text
    val replyHash      = Crypto.sha256(text.getBytes(Utf8))
    val root           = Crypto.hkdf(shared, Crypto.sha256(invitationHash, replyHash), "yochat-s1-root", 32)
    ReplyResult(envelope, text, Session.fromRoot(root), fields)

  def accept(
    invitation: Wire.Invitation,
    replyText: String,
    inviterEphemeral: PrivateKeyLike
  ): ReplyResult =
    val trimmed        = replyText.trim
    val envelope       = Wire.ReplyEnvelope.parse(trimmed)
    val invitationHash = invitation.hash
    val shared         = Crypto.agree(inviterEphemeral.privateKey, Crypto.x25519Public(envelope.ephemeralPub))
    val replyKey       = Crypto.hkdf(shared, invitationHash, "yochat-s1-reply", 32)
    val opened         = Crypto.aeadDecrypt(replyKey, envelope.nonce, invitationHash, envelope.ciphertext)
    require(opened.length > 64, "reply payload is too short")
    val plain          = opened.dropRight(64)
    val signature      = opened.takeRight(64)
    val fields         = Wire.ReplyFields.decode(plain)
    require(Arrays.equals(fields.invitationHash, invitationHash), "reply invitation hash mismatch")
    val verifier       = Crypto.ed25519Public(fields.userPub)
    require(Crypto.verify(verifier, plain, signature), "reply signature does not verify")
    val replyHash      = Crypto.sha256(trimmed.getBytes(Utf8))
    val root           = Crypto.hkdf(shared, Crypto.sha256(invitationHash, replyHash), "yochat-s1-root", 32)
    ReplyResult(envelope, trimmed, Session.fromRoot(root), fields)

/** Small wrapper so callers do not accidentally pass the wrong key type. */
final case class PrivateKeyLike(privateKey: java.security.PrivateKey)

object SafetyNumber:

  def of(aUserPub: Array[Byte], bUserPub: Array[Byte]): String =
    val ordered =
      if Arrays.compareUnsigned(aUserPub, bUserPub) <= 0 then (aUserPub, bUserPub)
      else (bUserPub, aUserPub)
    val digest  = Crypto.sha256(ordered._1, ordered._2)
    digest.take(30).grouped(3).map(group => group.map(byte => f"${byte & 0xff}%03d").mkString).mkString(" ")

enum MessageError:
  case InvalidSequence(seq: Long)
  case Replay(seq: Long)
  case GapTooLarge(seq: Long, next: Long)
  case InvalidCiphertext(seq: Long)

/** Immutable sender ratchet. The byte array is treated as immutable; callers never mutate it. */
final class SenderChain private (chainKey: Array[Byte], val nextSeq: Long):
  private val Empty = Array.emptyByteArray

  def next: (SenderChain, Long, Array[Byte]) =
    val messageKey = Crypto.hkdf(chainKey, Empty, "message", 32)
    val nextKey    = Crypto.hkdf(chainKey, Empty, "chain", 32)
    (new SenderChain(nextKey, nextSeq + 1), nextSeq, messageKey)

object SenderChain:
  def apply(initialKey: Array[Byte]): SenderChain = new SenderChain(initialKey.clone(), 1L)

/** Immutable receiver ratchet with a bounded skipped-key window. The byte arrays are treated as immutable. */
final class ReceiverChain private (chainKey: Array[Byte], nextSeq: Long, skipped: Map[Long, Array[Byte]]):
  private val Empty = Array.emptyByteArray

  def keyFor(seq: Long): Either[MessageError, (ReceiverChain, Array[Byte])] =
    if seq < 1 then Left(MessageError.InvalidSequence(seq))
    else if seq < nextSeq then
      skipped.get(seq) match
        case Some(key) => Right((new ReceiverChain(chainKey, nextSeq, skipped - seq), key))
        case None      => Left(MessageError.Replay(seq))
    else if seq - nextSeq > 64 then Left(MessageError.GapTooLarge(seq, nextSeq))
    else
      val (targetChain, newSkipped) = advance(chainKey, nextSeq, seq, skipped)
      val messageKey                = Crypto.hkdf(targetChain, Empty, "message", 32)
      val nextChain                 = Crypto.hkdf(targetChain, Empty, "chain", 32)
      Right((new ReceiverChain(nextChain, seq + 1, newSkipped), messageKey))

  private def advance(
    key: Array[Byte],
    from: Long,
    target: Long,
    acc: Map[Long, Array[Byte]]
  ): (Array[Byte], Map[Long, Array[Byte]]) =
    @tailrec
    def loop(current: Array[Byte], seq: Long, skippedNow: Map[Long, Array[Byte]]): (Array[Byte], Map[Long, Array[Byte]]) =
      if seq >= target then (current, skippedNow)
      else
        val messageKey = Crypto.hkdf(current, Empty, "message", 32)
        val nextKey    = Crypto.hkdf(current, Empty, "chain", 32)
        loop(nextKey, seq + 1, skippedNow + (seq -> messageKey))
    loop(key, from, acc)

object ReceiverChain:
  def apply(initialKey: Array[Byte]): ReceiverChain = new ReceiverChain(initialKey.clone(), 1L, Map.empty)

object MessageCrypto:

  def encrypt(sender: SenderChain, plaintext: Array[Byte]): (SenderChain, Wire.Message) =
    val (nextSender, seq, key) = sender.next
    val nonce                  = Crypto.randomBytes(12)
    val aad                    = Crypto.seqBytes(seq)
    (nextSender, Wire.Message(seq, nonce, Crypto.aeadEncrypt(key, nonce, aad, plaintext)))

  def decrypt(receiver: ReceiverChain, message: Wire.Message): Either[MessageError, (ReceiverChain, Array[Byte])] =
    receiver.keyFor(message.seq).flatMap { case (nextReceiver, key) =>
      try Right((nextReceiver, Crypto.aeadDecrypt(key, message.nonce, Crypto.seqBytes(message.seq), message.ciphertext)))
      catch case NonFatal(_) => Left(MessageError.InvalidCiphertext(message.seq))
    }
