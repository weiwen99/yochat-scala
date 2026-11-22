package yochat.store

import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, RatchetState, SessionId}

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
  createdAt: Long
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

enum CutoverRole:
  case Initiator, Responder

final case class CutoverEvidence(
  confirmAckReceived: Boolean,
  firstNewSessionDataReceived: Boolean
)
