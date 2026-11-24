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
  confirmation: ContactConfirmation,
  alias: Option[String] = None
):

  override def toString: String = s"AppContact(confirmation=$confirmation, safetyVerified=$safetyVerified)"

enum InvitationLifecycle:
  case Issued, AwaitingReply, PendingReply, Accepted, Expired

final case class AppInvitation(
  id: ByteVector,
  invitation: Invitation,
  ephemeralPrivate: Option[X25519Private],
  ratchetPrivate: Option[X25519Private],
  expectedAccount: Option[String],
  lifecycle: InvitationLifecycle,
  expiresAt: Long,
  replyText: Option[String] = None,
  replyAttempts: Int = 0
):

  override def toString: String = s"AppInvitation(lifecycle=$lifecycle, expiresAt=$expiresAt)"

final case class AppSession(
  state: RatchetState,
  conversationId: ByteVector,
  nextSeq: Long,
  retireOldOnFirstData: Boolean = false,
  bindingEstablished: Boolean = true
):

  override def toString: String =
    s"AppSession(conversation=$conversationId, nextSeq=$nextSeq, phase=${state.phase}, binding=$bindingEstablished)"

enum OutgoingState:
  case Persisting, Queued, InFlight, Sent, Failed

final case class OutgoingMessage(
  id: Long,
  conversationId: ByteVector,
  sessionId: SessionId,
  authorPub: Ed25519Public,
  devicePub: Ed25519Public,
  peerDevicePub: Ed25519Public,
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

enum RekeyTimerStage:
  case Initiator, Responder, Confirm

enum TimerId:
  case Message(timerKind: TimerKind, outgoingId: Long)
  case Reply(invitationId: ByteVector)
  case Rekey(sessionId: SessionId, stage: RekeyTimerStage)
  case Idle

  def kind: TimerKind =
    this match
      case TimerId.Message(value, _) => value
      case _                         => TimerKind.Delivery

  def messageId: Long =
    this match
      case TimerId.Message(_, value) => value
      case _                         => 0L

  def isRekey: Boolean =
    this match
      case TimerId.Rekey(_, _) => true
      case _                   => false

  def rekeySessionId: Option[SessionId] =
    this match
      case TimerId.Rekey(sessionId, _) => Some(sessionId)
      case _                           => None

object TimerId:

  def apply(kind: TimerKind, messageId: Long): TimerId = TimerId.Message(kind, messageId)

  def unapply(value: TimerId): Option[(TimerKind, Long)] =
    value match
      case TimerId.Message(kind, messageId) => Some((kind, messageId))
      case _                                => None

enum Notification:
  case Waiting, MessageFailed, QueueFull, InboundOverflow, RekeyRequired, RekeyFailed

enum SessionRetirement:
  case InitiatorAck, ResponderFirstData

enum MessageTransition:
  case Sent, Delivered, Failed

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

final case class InboundResult(
  conversation: Conversation,
  disposition: InboundDisposition,
  applied: Vector[AppliedMessage],
  gap: Option[Long]
)

final case class Conversation(
  id: ByteVector,
  peerUserPub: Ed25519Public,
  peerDevicePub: Ed25519Public,
  nextSeq: Map[Ed25519Public, Long],
  seen: Set[MessageKey],
  buffered: Map[MessageKey, BufferedMessage],
  gapRequested: Option[Long] = None
):

  def accept(message: BufferedMessage): InboundResult =
    val key      = MessageKey(message.authorPub, message.devicePub, message.seq)
    val expected = nextSeq.getOrElse(message.authorPub, 1L)
    if message.seq < expected then InboundResult(this, InboundDisposition.Duplicate, Vector.empty, None)
    else if seen.contains(key) then InboundResult(this, InboundDisposition.Duplicate, Vector.empty, None)
    else if message.seq == expected then
      val initial             = copy(nextSeq = nextSeq.updated(message.authorPub, expected + 1L), seen = seen - key)
      val (flushed, appended) = initial.flush(message.authorPub, Vector(message.toApplied))
      val gap                 = flushed.pendingGap
      InboundResult(
        flushed.copy(gapRequested = gap),
        InboundDisposition.Applied,
        appended,
        gap.filterNot(value => gapRequested.contains(value))
      )
    else if buffered.size >= AppLimits.MaxBuffered then
      val gap = Some(expected)
      InboundResult(copy(gapRequested = gap), InboundDisposition.Overflow, Vector.empty, gap.filterNot(value => gapRequested.contains(value)))
    else
      val added = copy(seen = seen + key, buffered = buffered + (key -> message))
      val gap   = added.pendingGap
      InboundResult(
        added.copy(gapRequested = gap),
        InboundDisposition.Buffered,
        Vector.empty,
        gap.filterNot(value => gapRequested.contains(value))
      )

  override def toString: String =
    s"Conversation(authors=${nextSeq.size}, buffered=${buffered.size}, gap=$gapRequested)"

  private def pendingGap: Option[Long] =
    buffered.keys.toVector
      .sortBy(key => (key.authorPub.bytes.toHex, key.seq))
      .headOption
      .map(key => nextSeq.getOrElse(key.authorPub, 1L))

  private def flush(author: Ed25519Public, appended: Vector[AppliedMessage]): (Conversation, Vector[AppliedMessage]) =
    val expected = nextSeq.getOrElse(author, 1L)
    buffered.keys.find(key => key.authorPub == author && key.seq == expected) match
      case None      => (this, appended)
      case Some(key) =>
        val message = buffered(key)
        val next    = copy(
          nextSeq = nextSeq.updated(author, expected + 1L),
          seen = seen - key,
          buffered = buffered - key
        )
        next.flush(author, appended :+ message.toApplied)

extension (message: BufferedMessage)

  private def toApplied: AppliedMessage =
    AppliedMessage(message.id, message.authorPub, message.devicePub, message.seq, message.body)

object AppLimits:

  val DeliveryTimeoutMs: Long     = 5_000L
  val RetryBackoffMs: Long        = 2_000L
  val RekeyWindowMs: Long         = 60_000L
  val MaxRekeyWindowAttempts: Int = 3
  val MaxSendAttempts: Int        = 3
  val MaxBuffered: Int            = 256
  val MaxInFlight: Int            = 32
  val MaxOutgoing: Int            = 1_024
  val MaxFailedRetained: Int      = 64
  val MaxReplyAttempts: Int       = 3
  val ReplyRetryMs: Long          = 2_000L
  val RekeyTimeoutMs: Long        = 5_000L
  val RekeyRetryMs: Long          = 2_500L
  val MaxReplyCandidates: Int     = 8

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
  timerInstallations: Map[TimerId, Long] = Map.empty,
  nextTimerInstallation: Long = 1L,
  connection: ConnectionState = ConnectionState.Offline,
  rekeyWindowStartedAtMs: Long = 0L,
  rekeyWindowAttempts: Int = 0
):

  override def toString: String =
    s"AppState(lock=$lock, contacts=${contacts.size}, sessions=${sessions.size}, " +
      s"conversations=${conversations.size}, outgoing=${outgoing.size}, connection=$connection)"

object AppState:

  def empty: AppState = AppState()
