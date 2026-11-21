package yochat.core.app

import scodec.bits.ByteVector

import yochat.core.{Ed25519KeyPair, Ed25519Public, Invitation, RatchetState, SessionId, WireFrame, X25519Private}

enum LockState:
  case Locked(generation: Long)
  case Unlocked(generation: Long)

  def value: Long =
    this match
      case Locked(generation)   => generation
      case Unlocked(generation) => generation

  def isUnlocked: Boolean =
    this match
      case Unlocked(_) => true
      case Locked(_)   => false

final case class LocalIdentity(user: Ed25519KeyPair, device: Ed25519KeyPair):

  override def toString: String = "LocalIdentity(<redacted>)"

enum ContactConfirmation:
  case Pending, Confirmed, AccountMismatch

final case class AppContact(
  userPub: Ed25519Public,
  devicePub: Ed25519Public,
  channelLabel: String,
  channelAccount: String,
  peerChannelAccount: Option[String],
  safetyVerified: Boolean,
  confirmation: ContactConfirmation
):

  override def toString: String = s"AppContact(confirmation=$confirmation, safetyVerified=$safetyVerified)"

enum InvitationLifecycle:
  case Issued, AwaitingReply, Accepted, Expired

final case class AppInvitation(
  id: ByteVector,
  invitation: Invitation,
  ephemeralPrivate: Option[X25519Private],
  expectedAccount: Option[String],
  lifecycle: InvitationLifecycle,
  expiresAt: Long
):

  override def toString: String = s"AppInvitation(lifecycle=$lifecycle, expiresAt=$expiresAt)"

final case class AppSession(
  state: RatchetState,
  conversationId: ByteVector,
  nextSeq: Long,
  retireOldOnFirstData: Boolean = false
):

  override def toString: String = s"AppSession(conversation=$conversationId, nextSeq=$nextSeq, phase=${state.phase})"

enum OutgoingState:
  case Persisting, Queued, Sent, Failed

final case class OutgoingMessage(
  id: Long,
  conversationId: ByteVector,
  sessionId: SessionId,
  authorPub: Ed25519Public,
  devicePub: Ed25519Public,
  seq: Long,
  body: ByteVector,
  wire: WireFrame,
  generation: Long,
  state: OutgoingState,
  attempts: Int
):

  override def toString: String = s"OutgoingMessage(id=$id, seq=$seq, state=$state, attempts=$attempts)"

enum ConnectionState:
  case Offline, Online

enum TimerKind:
  case Delivery, Retry

final case class TimerId(kind: TimerKind, messageId: Long)

enum Notification:
  case Waiting, MessageFailed, RekeyRequired, RekeyFailed

enum SessionRetirement:
  case InitiatorAck, ResponderFirstData

enum MessageTransition:
  case Sent, Failed

final case class MessageKey(authorPub: Ed25519Public, devicePub: Ed25519Public, seq: Long)

final case class BufferedMessage(
  id: Long,
  authorPub: Ed25519Public,
  devicePub: Ed25519Public,
  seq: Long,
  body: ByteVector,
  wire: WireFrame
):

  override def toString: String = s"BufferedMessage(id=$id, seq=$seq)"

final case class AppliedMessage(
  id: Long,
  authorPub: Ed25519Public,
  devicePub: Ed25519Public,
  seq: Long,
  body: ByteVector
):

  override def toString: String = s"AppliedMessage(id=$id, seq=$seq)"

enum InboundDisposition:
  case Applied, Buffered, Duplicate, Overflow

final case class Conversation(
  id: ByteVector,
  peerUserPub: Ed25519Public,
  peerDevicePub: Ed25519Public,
  nextSeq: Map[Ed25519Public, Long],
  seen: Set[MessageKey],
  buffered: Map[MessageKey, BufferedMessage],
  applied: Vector[AppliedMessage]
):

  def accept(message: BufferedMessage): (Conversation, InboundDisposition, Vector[AppliedMessage]) =
    val key = MessageKey(message.authorPub, message.devicePub, message.seq)
    if seen.contains(key) then (this, InboundDisposition.Duplicate, Vector.empty)
    else
      val expected = nextSeq.getOrElse(message.authorPub, 1L)
      if message.seq == expected then
        val initial             = copy(
          nextSeq = nextSeq.updated(message.authorPub, expected + 1L),
          seen = seen + key,
          applied = applied :+ message.toApplied
        )
        val (flushed, appended) = initial.flush(message.authorPub, Vector(message.toApplied))
        (flushed, InboundDisposition.Applied, appended)
      else if message.seq > expected then
        if buffered.size >= AppLimits.MaxBuffered then (this, InboundDisposition.Overflow, Vector.empty)
        else (copy(seen = seen + key, buffered = buffered + (key -> message)), InboundDisposition.Buffered, Vector.empty)
      else (copy(seen = seen + key), InboundDisposition.Duplicate, Vector.empty)

  override def toString: String = s"Conversation(applied=${applied.size}, buffered=${buffered.size})"

  private def flush(author: Ed25519Public, appended: Vector[AppliedMessage]): (Conversation, Vector[AppliedMessage]) =
    val expected = nextSeq.getOrElse(author, 1L)
    buffered.keys.find(key => key.authorPub == author && key.seq == expected) match
      case None      => (this, appended)
      case Some(key) =>
        val message = buffered(key)
        val next    = copy(
          nextSeq = nextSeq.updated(author, expected + 1L),
          buffered = buffered - key,
          applied = applied :+ message.toApplied
        )
        next.flush(author, appended :+ message.toApplied)

extension (message: BufferedMessage)

  private def toApplied: AppliedMessage =
    AppliedMessage(message.id, message.authorPub, message.devicePub, message.seq, message.body)

object AppLimits:

  val DeliveryTimeoutMs: Long = 5_000L
  val RetryBackoffMs: Long    = 2_000L
  val MaxSendAttempts: Int    = 3
  val MaxBuffered: Int        = 256

final case class AppState(
  identity: Option[LocalIdentity] = None,
  lock: LockState = LockState.Locked(0L),
  idleDeadline: Option[Long] = None,
  contacts: Map[Ed25519Public, AppContact] = Map.empty,
  sessions: Map[SessionId, AppSession] = Map.empty,
  conversations: Map[ByteVector, Conversation] = Map.empty,
  invitations: Map[ByteVector, AppInvitation] = Map.empty,
  outgoing: Map[Long, OutgoingMessage] = Map.empty,
  timers: Map[TimerId, Long] = Map.empty,
  connection: ConnectionState = ConnectionState.Offline
):

  override def toString: String =
    s"AppState(lock=$lock, contacts=${contacts.size}, sessions=${sessions.size}, " +
      s"conversations=${conversations.size}, outgoing=${outgoing.size}, connection=$connection)"

object AppState:

  def empty: AppState = AppState()
