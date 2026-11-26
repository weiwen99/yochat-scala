package yochat.store

import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, RatchetState, SessionId, X25519Public}
import yochat.core.s2.{LogicalKey, OrderStamp}

enum MessageState:
  case Pending, Sent, Received, Failed, Delivered

object MessageState:

  def code(value: MessageState): Int =
    value match
      case MessageState.Pending   => 1
      case MessageState.Sent      => 2
      case MessageState.Received  => 3
      case MessageState.Failed    => 4
      case MessageState.Delivered => 5

  def fromCode(value: Int): Either[StorageError, MessageState] =
    value match
      case 1 => Right(MessageState.Pending)
      case 2 => Right(MessageState.Sent)
      case 3 => Right(MessageState.Received)
      case 4 => Right(MessageState.Failed)
      case 5 => Right(MessageState.Delivered)
      case _ => Left(StorageError.InvalidEnvelope)

final case class SessionRecord(
  state: RatchetState,
  version: Long,
  generation: Long,
  updatedAt: Long
):

  override def toString: String = s"SessionRecord(version=$version, generation=$generation)"

final case class MessageRecord(
  id: Long,
  conversationId: ByteVector,
  sessionId: SessionId,
  authorPub: Ed25519Public,
  devicePub: Ed25519Public,
  seq: Long,
  body: ByteVector,
  wire: ByteVector,
  state: MessageState,
  createdAt: Long,
  attempts: Int,
  order: Option[OrderStamp] = None
):

  override def toString: String = s"MessageRecord(id=$id, seq=$seq, state=$state)"

final case class AppendResult(session: SessionRecord, message: MessageRecord)

final case class ContactRecord(
  userPub: Ed25519Public,
  data: ContactData,
  version: Long,
  createdAt: Long
):

  override def toString: String = s"ContactRecord(version=$version, confirmation=${data.confirmationState})"

final case class InvitationRecord(
  id: ByteVector,
  data: InvitationData,
  version: Long,
  createdAt: Long
):

  override def toString: String = s"InvitationRecord(version=$version, state=${data.state})"

final case class ConversationRecord(
  id: ByteVector,
  peerUserPub: Ed25519Public,
  createdAt: Long,
  lastAppliedSeq: Long
):

  override def toString: String = s"ConversationRecord(lastAppliedSeq=$lastAppliedSeq)"

enum DeviceRole:
  case Primary, Companion

  def code: Int =
    this match
      case DeviceRole.Primary   => 1
      case DeviceRole.Companion => 2

object DeviceRole:

  def fromCode(value: Int): Either[StorageError, DeviceRole] =
    value match
      case 1 => Right(DeviceRole.Primary)
      case 2 => Right(DeviceRole.Companion)
      case _ => Left(StorageError.InvalidEnvelope)

final case class DeviceRecord(
  devicePub: Ed25519Public,
  userPub: Ed25519Public,
  role: DeviceRole,
  name: String,
  authorization: ByteVector,
  version: Long,
  createdAt: Long,
  revokedAt: Option[Long],
  joinEpoch: Long,
  joinManifest: ByteVector,
  joinManifestHash: ByteVector,
  joinCursorSyncSeq: Long,
  pairingNonce: Option[ByteVector],
  requestHash: Option[ByteVector],
  fanoutPub: Option[X25519Public] = None
):

  def isRevoked: Boolean = revokedAt.isDefined

  override def toString: String =
    s"DeviceRecord(role=$role, version=$version, revoked=${isRevoked}, manifestBytes=${joinManifest.size})"

final case class DeviceSyncRecord(
  peerDevicePub: Ed25519Public,
  syncRoot: ByteVector,
  lastAckedSyncSeq: Long,
  lastBatchSeq: Long,
  lastBatchHash: ByteVector,
  version: Long,
  updatedAt: Long
):

  override def toString: String =
    s"DeviceSyncRecord(lastAckedSyncSeq=$lastAckedSyncSeq, lastBatchSeq=$lastBatchSeq, version=$version)"

final case class DeliveryReceipt(
  conversationId: ByteVector,
  authorDevice: Ed25519Public,
  authorSeq: Long,
  recipientDevice: Ed25519Public,
  logicalKey: LogicalKey,
  state: Int,
  createdAt: Long,
  updatedAt: Long,
  attempts: Int
):

  override def toString: String =
    s"DeliveryReceipt(seq=$authorSeq, recipient=${recipientDevice.bytes.toHex.take(8)}, state=$state, attempts=$attempts)"

final case class CompanionMessageRecord(
  sourceId: Long,
  conversationId: ByteVector,
  authorPub: Ed25519Public,
  devicePub: Ed25519Public,
  seq: Long,
  body: ByteVector,
  state: MessageState,
  createdAt: Long,
  syncSeq: Long,
  logicalKey: Option[LogicalKey] = None
):

  override def toString: String =
    s"CompanionMessageRecord(sourceId=$sourceId, seq=$seq, state=$state, syncSeq=$syncSeq)"

final case class StoreSnapshot(
  contacts: List[ContactRecord],
  invitations: List[InvitationRecord],
  sessions: List[SessionRecord],
  conversations: List[ConversationRecord],
  messages: Map[ByteVector, List[MessageRecord]],
  bindings: Set[ByteVector] = Set.empty
)

final case class HandshakeWrite(
  contact: Option[(Ed25519Public, ContactData)],
  conversation: Option[(ByteVector, Ed25519Public)],
  sessions: Vector[RatchetState],
  invitation: Option[(ByteVector, InvitationData)],
  deleteInvitation: Option[ByteVector],
  retire: Vector[(SessionId, CutoverRole, CutoverEvidence)],
  deletePendingSessions: Vector[SessionId]
):

  override def toString: String =
    s"HandshakeWrite(contact=${contact.isDefined}, conversation=${conversation.isDefined}, sessions=${sessions.size}, " +
      s"invitation=${invitation.isDefined}, deleteInvitation=${deleteInvitation.isDefined}, retire=${retire.size}, " +
      s"deletePending=${deletePendingSessions.size})"

enum CutoverRole:
  case Initiator, Responder

final case class CutoverEvidence(
  confirmAckReceived: Boolean,
  firstNewSessionDataReceived: Boolean
)
