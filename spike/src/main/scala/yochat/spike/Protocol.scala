package yochat.spike

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.Arrays
import scala.collection.mutable

/** S1 session keys. The two directional chains are a symmetric hash ratchet:
  * each message key is HKDF(chain, "message") and the chain advances with
  * HKDF(chain, "chain"). This provides forward secrecy for the chain but does
  * not implement a DH ratchet or group membership. That is a deliberate spike
  * limitation, recorded in ADR-0005.
  */
final case class Session(root: Array[Byte], a2b: Array[Byte], b2a: Array[Byte])

object Session:
  def fromRoot(root: Array[Byte]): Session =
    val empty = Array.emptyByteArray
    Session(
      root = root,
      a2b = Crypto.hkdf(root, empty, "a->b", 32),
      b2a = Crypto.hkdf(root, empty, "b->a", 32),
    )

final case class ReplyResult(
  envelope: Wire.ReplyEnvelope,
  text: String,
  session: Session,
  fields: Wire.ReplyFields,
)

/** Invitation/reply exchange. The reply is signed, then encrypted to the
  * invitation's one-time X25519 key. The session root binds both texts.
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
    note: String,
  ): ReplyResult =
    val invitationHash = invitation.hash
    val shared         = Crypto.agree(ephemeral.getPrivate, Crypto.x25519Public(invitation.fields.ephemeralPub))
    val replyKey       = Crypto.hkdf(shared, invitationHash, "yochat-s1-reply", 32)
    val fields = Wire.ReplyFields(
      invitationHash = invitationHash,
      userPub = Crypto.publicBytes(user.getPublic),
      devicePub = Crypto.publicBytes(device.getPublic),
      channelLabel = channelLabel,
      channelAccount = channelAccount,
      note = note,
    )
    val plain      = Wire.ReplyFields.encode(fields)
    val signature  = Crypto.sign(user.getPrivate, plain)
    val nonce      = Crypto.randomBytes(12)
    val ciphertext = Crypto.aeadEncrypt(replyKey, nonce, invitationHash, plain ++ signature)
    val envelope   = Wire.ReplyEnvelope(Crypto.publicBytes(ephemeral.getPublic), nonce, ciphertext)
    val text       = envelope.text
    val replyHash  = Crypto.sha256(text.getBytes(Utf8))
    val root       = Crypto.hkdf(shared, Crypto.sha256(invitationHash, replyHash), "yochat-s1-root", 32)
    ReplyResult(envelope, text, Session.fromRoot(root), fields)

  def accept(
    invitation: Wire.Invitation,
    replyText: String,
    inviterEphemeral: PrivateKeyLike,
  ): ReplyResult =
    val trimmed        = replyText.trim
    val envelope       = Wire.ReplyEnvelope.parse(trimmed)
    val invitationHash = invitation.hash
    val shared         = Crypto.agree(inviterEphemeral.privateKey, Crypto.x25519Public(envelope.ephemeralPub))
    val replyKey       = Crypto.hkdf(shared, invitationHash, "yochat-s1-reply", 32)
    val opened         = Crypto.aeadDecrypt(replyKey, envelope.nonce, invitationHash, envelope.ciphertext)
    require(opened.length > 64, "reply payload is too short")
    val plain     = opened.dropRight(64)
    val signature = opened.takeRight(64)
    val fields    = Wire.ReplyFields.decode(plain)
    require(Arrays.equals(fields.invitationHash, invitationHash), "reply invitation hash mismatch")
    val verifier = Crypto.ed25519Public(fields.userPub)
    require(Crypto.verify(verifier, plain, signature), "reply signature does not verify")
    val replyHash = Crypto.sha256(trimmed.getBytes(Utf8))
    val root      = Crypto.hkdf(shared, Crypto.sha256(invitationHash, replyHash), "yochat-s1-root", 32)
    ReplyResult(envelope, trimmed, Session.fromRoot(root), fields)

/** Small wrapper so callers do not accidentally pass the wrong key type. */
final case class PrivateKeyLike(privateKey: java.security.PrivateKey)

object SafetyNumber:
  def of(aUserPub: Array[Byte], bUserPub: Array[Byte]): String =
    val ordered =
      if Arrays.compareUnsigned(aUserPub, bUserPub) <= 0 then (aUserPub, bUserPub)
      else (bUserPub, aUserPub)
    val digest = Crypto.sha256(ordered._1, ordered._2)
    digest.take(30).grouped(3).map(group => group.map(byte => f"${byte & 0xff}%03d").mkString).mkString(" ")

final class SenderChain(initialKey: Array[Byte]):
  private val empty = Array.emptyByteArray
  private var chainKey = initialKey.clone()
  private var nextSeq  = 1L

  def next(): (Long, Array[Byte]) =
    val seq        = nextSeq
    val messageKey = Crypto.hkdf(chainKey, empty, "message", 32)
    chainKey = Crypto.hkdf(chainKey, empty, "chain", 32)
    nextSeq += 1
    (seq, messageKey)

final class ReceiverChain(initialKey: Array[Byte]):
  private val empty = Array.emptyByteArray
  private var chainKey = initialKey.clone()
  private var nextSeq  = 1L
  private val skipped  = mutable.Map.empty[Long, Array[Byte]]

  def keyFor(seq: Long): Array[Byte] =
    require(seq >= 1, s"sequence must be positive: $seq")
    if seq < nextSeq then
      skipped.remove(seq).getOrElse(throw new SecurityException(s"replay or unknown sequence $seq"))
    else
      if seq - nextSeq > 64 then throw new SecurityException(s"sequence gap too large: $seq")
      while nextSeq < seq do
        skipped.put(nextSeq, Crypto.hkdf(chainKey, empty, "message", 32))
        chainKey = Crypto.hkdf(chainKey, empty, "chain", 32)
        nextSeq += 1
      val messageKey = Crypto.hkdf(chainKey, empty, "message", 32)
      chainKey = Crypto.hkdf(chainKey, empty, "chain", 32)
      nextSeq += 1
      messageKey

object MessageCrypto:
  def encrypt(sender: SenderChain, plaintext: Array[Byte]): Wire.Message =
    val (seq, key) = sender.next()
    val nonce      = Crypto.randomBytes(12)
    val aad        = Crypto.seqBytes(seq)
    Wire.Message(seq, nonce, Crypto.aeadEncrypt(key, nonce, aad, plaintext))

  def decrypt(receiver: ReceiverChain, message: Wire.Message): Array[Byte] =
    val key = receiver.keyFor(message.seq)
    Crypto.aeadDecrypt(key, message.nonce, Crypto.seqBytes(message.seq), message.ciphertext)
