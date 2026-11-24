package yochat.core.app

import scodec.bits.ByteVector

import yochat.core.{
  ControlExtension,
  Ed25519Public,
  Invitation,
  Nonce,
  RekeyAccept,
  RekeyId,
  RekeyRequest,
  ReplyResult,
  SessionId,
  WireFrame,
  X25519KeyPair,
  X25519Private
}

enum AppEvent:
  case Unlock(identity: LocalIdentity, nowMs: Long, idleTimeoutMs: Long)
  case Lock
  case IdleTimeout(nowMs: Long)
  case UserSend(messageId: Long, conversationId: ByteVector, body: ByteVector, nonce: Nonce, nowMs: Long)
  case PersistOutboundSucceeded(messageId: Long, generation: Long, nowMs: Long)
  case PersistOutboundFailed(messageId: Long, generation: Long, nowMs: Long)
  case RebindOutgoing(messageId: Long, newSessionId: SessionId, nonce: Nonce, nowMs: Long)
  case RecoverRebinds
  case RecoverRekey(oldSessionId: SessionId, nonce: Nonce, nowMs: Long)
  case RekeyConfirmRetry(oldSessionId: SessionId, nonce: Nonce, nowMs: Long)
  case Pump(nowMs: Long)
  case SendSucceeded(messageId: Long, generation: Long)
  case SendFailed(messageId: Long, generation: Long, nowMs: Long)
  case TimerFired(timer: TimerId, generation: Long, nowMs: Long)
  case ConnectionChanged(online: Boolean, nowMs: Long)

  case InboundFrame(
    messageId: Long,
    sessionId: SessionId,
    frame: WireFrame,
    newDh: Option[X25519KeyPair],
    responseNonce: Nonce,
    nowMs: Long
  )
  case SafetyVerified(userPub: Ed25519Public, nowMs: Long)
  case SetContactAlias(userPub: Ed25519Public, alias: Option[String])

  case InvitationCreated(
    invitation: Invitation,
    ephemeralPrivate: X25519Private,
    ratchetPrivate: X25519Private,
    expectedAccount: Option[String],
    nowMs: Long
  )
  case InvitationReceived(invitation: Invitation, expectedAccount: Option[String], nowMs: Long)
  case ReplyCreated(invitationId: ByteVector, conversationId: ByteVector, result: ReplyResult, nowMs: Long)

  case ReplyReceived(
    invitationId: ByteVector,
    conversationId: ByteVector,
    result: ReplyResult,
    expectedAccount: Option[String],
    nowMs: Long
  )
  case ReplySent(invitationId: ByteVector)
  case ReplySendFailed(invitationId: ByteVector, nowMs: Long)
  case ResumeGap(conversationId: ByteVector, nonce: Nonce, nowMs: Long)
  case RejectInvitation(invitationId: ByteVector)
  case StartRekey(sessionId: SessionId, r1: X25519KeyPair, rekeyId: RekeyId, nonce: Nonce, nowMs: Long)

  case RekeyRequestReceived(
    sessionId: SessionId,
    request: RekeyRequest,
    r2: X25519KeyPair,
    r2Next: X25519KeyPair,
    nonce: Nonce,
    nowMs: Long = 0L
  )
  case RekeyAcceptReceived(sessionId: SessionId, accept: RekeyAccept, confirmNonce: Nonce, nowMs: Long = 0L)

  case RekeyConfirmReceived(
    newSessionId: SessionId,
    frame: WireFrame,
    expected: ControlExtension,
    responseNonce: Nonce,
    newDh: Option[X25519KeyPair]
  )
  case RekeyAckReceived(newSessionId: SessionId, frame: WireFrame, expected: ControlExtension, newDh: X25519KeyPair)

enum AppEffect:
  case PersistOutbound(message: OutgoingMessage, session: AppSession)
  case PersistInbound(message: BufferedMessage, session: AppSession)
  case PersistInboundAndRetire(message: BufferedMessage, session: AppSession, retire: Vector[SessionId])
  case PersistContact(contact: AppContact)
  case PersistSession(session: AppSession)
  case PersistSafetyVerified(userPub: Ed25519Public, verified: Boolean)
  case PersistInvitation(invitation: AppInvitation)

  case PersistHandshake(
    contact: Option[AppContact],
    conversation: Option[Conversation],
    sessions: Vector[AppSession],
    invitation: Option[AppInvitation],
    deleteInvitation: Option[ByteVector],
    retire: Vector[(SessionId, SessionRetirement)],
    deletePendingSessions: Vector[SessionId]
  )
  case RebindPending(oldSessionId: SessionId, newSessionId: SessionId)
  case PersistRebound(message: OutgoingMessage, session: AppSession, oldSessionId: SessionId, oldSeq: Long, expectedSessionVersion: Long)
  case PersistAttempt(message: OutgoingMessage)
  case PersistLockGeneration(generation: Long)
  case DeleteInvitation(invitationId: ByteVector)

  case UpdateMessageState(
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    transition: MessageTransition
  )
  case SendWire(toDevice: Ed25519Public, messageId: Long, generation: Long, wire: WireFrame)
  case SendAck(sessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame)
  case SendGap(sessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame)
  case SendReply(invitationId: ByteVector, toDevice: Ed25519Public, text: String)
  case SendRekeyRequest(sessionId: SessionId, toDevice: Ed25519Public, request: RekeyRequest)
  case SendRekeyAccept(sessionId: SessionId, toDevice: Ed25519Public, accept: RekeyAccept)
  case SendRekeyConfirm(newSessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame)
  case SendRekeyConfirmAck(newSessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame)
  case StartRekey(sessionId: SessionId)
  case StartTimer(timer: TimerId, deadlineMs: Long)
  case Pump(nowMs: Long)
  case CancelTimer(timer: TimerId)
  case Notify(notification: Notification)
