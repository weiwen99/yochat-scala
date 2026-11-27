package yochat.server

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.app.*
import yochat.core.RekeyAccept
import yochat.core.RekeyRequest
import yochat.core.WireFrame
import yochat.store.{
  ContactConfirmation as StoreConfirmation,
  ContactData,
  ConversationRecord,
  CutoverEvidence,
  CutoverRole,
  HandshakeWrite,
  InvitationData,
  InvitationState,
  MessageRecord,
  MessageState,
  RebindOutcome,
  SessionRecord,
  StorageError,
  Store
}

/** Transport boundary for effects that leave the process. The executor never opens sockets itself. */
trait AppTransport:

  def sendWire(toDevice: yochat.core.Ed25519Public, messageId: Long, generation: Long, wire: WireFrame): IO[Unit]

  def sendAck(sessionId: yochat.core.SessionId, toDevice: yochat.core.Ed25519Public, frame: WireFrame): IO[Unit]

  def sendGap(sessionId: yochat.core.SessionId, toDevice: yochat.core.Ed25519Public, frame: WireFrame): IO[Unit]

  def sendReply(invitationId: ByteVector, toDevice: yochat.core.Ed25519Public, text: String): IO[Unit]

  def sendRekeyRequest(
    sessionId: yochat.core.SessionId,
    toDevice: yochat.core.Ed25519Public,
    request: RekeyRequest
  ): IO[Unit]

  def sendRekeyAccept(
    sessionId: yochat.core.SessionId,
    toDevice: yochat.core.Ed25519Public,
    accept: RekeyAccept
  ): IO[Unit]

  def sendRekeyConfirm(
    newSessionId: yochat.core.SessionId,
    toDevice: yochat.core.Ed25519Public,
    frame: WireFrame
  ): IO[Unit]

  def sendRekeyConfirmAck(
    newSessionId: yochat.core.SessionId,
    toDevice: yochat.core.Ed25519Public,
    frame: WireFrame
  ): IO[Unit]

  def startTimer(timer: TimerId, deadlineMs: Long, installationId: Long): IO[Unit]

  def cancelTimer(timer: TimerId): IO[Unit]

  def notify(notification: Notification): IO[Unit]

enum AppExecutionError:

  case StoreFailed(error: StorageError)
  case TransportFailed
  case Unsupported(effect: String)

  def code: String =
    this match
      case StoreFailed(error) => s"StoreFailed:${error.code}"
      case TransportFailed    => "TransportFailed"
      case Unsupported(name)  => s"Unsupported:$name"

  override def toString: String = code

/** Executes the pure `AppEffect` vocabulary against the production store and an explicit transport sink. */
final class AppEffectExecutor(
  store: Store,
  identity: LocalIdentity,
  transport: AppTransport,
  onOutboundPersisted: (MessageRecord, Long) => IO[Unit] = (_, _) => IO.unit
):

  def executeAll(effects: Vector[AppEffect]): IO[Either[AppExecutionError, Vector[Long]]] =
    effects.foldLeft(IO.pure[Either[AppExecutionError, Vector[Long]]](Right(Vector.empty))) { (accumulator, effect) =>
      accumulator.flatMap {
        case Left(error)                        => IO.pure(Left(error))
        case Right(skipped) if skipped.nonEmpty => IO.pure(Right(skipped))
        case Right(skipped)                     =>
          effect match
            case AppEffect.PersistRebound(message, session, oldSessionId, oldSeq, expectedVersion) =>
              persistRebound(message, session, oldSessionId, oldSeq, expectedVersion).map {
                case Left(error)                   => Left(error)
                case Right(RebindOutcome.Migrated) => Right(skipped)
                case Right(RebindOutcome.Skipped)  => Right(skipped :+ message.id)
              }
            case _                                                                                 =>
              execute(effect).map(_.map(_ => skipped))
      }
    }

  def execute(effect: AppEffect): IO[Either[AppExecutionError, Unit]] =
    executeEffect(effect).attempt.map {
      case Right(result)               => result
      case Left(abort: ExecutionAbort) => Left(abort.error)
      case Left(_)                     => Left(AppExecutionError.StoreFailed(StorageError.StorageFailure))
    }

  private def executeEffect(effect: AppEffect): IO[Either[AppExecutionError, Unit]] =
    effect match
      case AppEffect.PersistOutbound(message, session)                                                                      =>
        persistOutbound(message, session)
      case AppEffect.PersistRebound(message, session, oldSessionId, oldSeq, expectedVersion)                                =>
        persistRebound(message, session, oldSessionId, oldSeq, expectedVersion).map(_.map(_ => ()))
      case AppEffect.RebindPending(_, _)                                                                                    =>
        IO.pure(Right(()))
      case AppEffect.Pump(_)                                                                                                =>
        IO.pure(Right(()))
      case AppEffect.PersistInbound(message, session)                                                                       =>
        persistInbound(message, session)
      case AppEffect.PersistInboundAndRetire(message, session, retire)                                                      =>
        persistInbound(message, session, retire)
      case AppEffect.PersistContact(contact)                                                                                =>
        persistContact(contact)
      case AppEffect.PersistSession(session)                                                                                =>
        persistSession(session)
      case AppEffect.PersistSafetyVerified(userPub, verified)                                                               =>
        persistSafetyVerified(userPub, verified)
      case AppEffect.PersistInvitation(invitation)                                                                          =>
        persistInvitation(invitation)
      case AppEffect.PersistHandshake(contact, conversation, sessions, invitation, deleteInvitation, retire, deletePending) =>
        persistHandshake(contact, conversation, sessions, invitation, deleteInvitation, retire, deletePending)
      case AppEffect.PersistAttempt(message)                                                                                =>
        persistAttempt(message)
      case AppEffect.PersistLockGeneration(generation)                                                                      =>
        persistLockGeneration(generation)
      case AppEffect.DeleteInvitation(invitationId)                                                                         =>
        deleteInvitation(invitationId)
      case AppEffect.UpdateMessageState(conversationId, sessionId, authorPub, devicePub, seq, transition)                   =>
        updateMessageState(conversationId, sessionId, authorPub, devicePub, seq, transition)
      case AppEffect.SendWire(toDevice, messageId, generation, wire)                                                        =>
        transportOnly(transport.sendWire(toDevice, messageId, generation, wire))
      case AppEffect.SendAck(sessionId, toDevice, frame)                                                                    =>
        transportOnly(transport.sendAck(sessionId, toDevice, frame))
      case AppEffect.SendGap(sessionId, toDevice, frame)                                                                    =>
        transportOnly(transport.sendGap(sessionId, toDevice, frame))
      case AppEffect.SendReply(invitationId, toDevice, text)                                                                =>
        transportOnly(transport.sendReply(invitationId, toDevice, text))
      case AppEffect.SendRekeyRequest(sessionId, toDevice, request)                                                         =>
        transportOnly(transport.sendRekeyRequest(sessionId, toDevice, request))
      case AppEffect.SendRekeyAccept(sessionId, toDevice, accept)                                                           =>
        transportOnly(transport.sendRekeyAccept(sessionId, toDevice, accept))
      case AppEffect.SendRekeyConfirm(newSessionId, toDevice, frame)                                                        =>
        transportOnly(transport.sendRekeyConfirm(newSessionId, toDevice, frame))
      case AppEffect.SendRekeyConfirmAck(newSessionId, toDevice, frame)                                                     =>
        transportOnly(transport.sendRekeyConfirmAck(newSessionId, toDevice, frame))
      case AppEffect.StartRekey(_)                                                                                          =>
        IO.pure(Left(AppExecutionError.Unsupported("StartRekey")))
      case AppEffect.StartTimer(timer, deadlineMs, installationId)                                                          =>
        transportOnly(transport.startTimer(timer, deadlineMs, installationId))
      case AppEffect.CancelTimer(timer)                                                                                     =>
        transportOnly(transport.cancelTimer(timer))
      case AppEffect.Notify(notification)                                                                                   =>
        transportOnly(transport.notify(notification))

  private def persistOutbound(message: OutgoingMessage, session: AppSession): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      loaded <- store.loadSession(message.sessionId)
      result <- loaded match
                  case Right(record)                  =>
                    appendOutbound(record, message, session, lock)
                  case Left(StorageError.NotFound(_)) =>
                    for
                      inserted <- store.insertSession(session.state, lock)
                      record   <- inserted match
                                    case Right(_)    => store.loadSession(message.sessionId)
                                    case Left(error) => IO.pure(Left(error))
                      result   <- record match
                                    case Right(value) => appendOutbound(value, message, session, lock)
                                    case Left(error)  => IO.pure(Left(AppExecutionError.StoreFailed(error)))
                    yield result
                  case Left(error)                    => IO.pure(Left(AppExecutionError.StoreFailed(error)))
    yield result

  private def appendOutbound(
    record: SessionRecord,
    message: OutgoingMessage,
    session: AppSession,
    lock: Long
  ): IO[Either[AppExecutionError, Unit]] =
    for
      conversation <- ensureConversation(message.conversationId, session, lock)
      result       <- conversation match
                        case Left(error) => IO.pure(Left(error))
                        case Right(_)    =>
                          store
                            .appendOutbound(
                              session.state,
                              record.version,
                              lock,
                              message.conversationId,
                              identity.user.publicKey,
                              identity.device.publicKey,
                              message.seq,
                              message.body,
                              message.wire.bytes,
                              MessageState.Pending,
                              message.attempts,
                              message.order
                            )
                            .flatMap {
                              case Right(append) => onOutboundPersisted(append.message, lock).attempt.void.as(Right(()))
                              case Left(error)   => IO.pure(Left(AppExecutionError.StoreFailed(error)))
                            }
    yield result

  private def persistInbound(
    message: BufferedMessage,
    session: AppSession,
    retire: Vector[yochat.core.SessionId] = Vector.empty
  ): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      loaded <- store.loadSession(session.state.sessionId)
      result <- loaded match
                  case Left(error)   => IO.pure(Left(AppExecutionError.StoreFailed(error)))
                  case Right(record) =>
                    for
                      conversation <- ensureConversation(session.conversationId, session, lock)
                      appended     <- conversation match
                                        case Left(error) => IO.pure(Left(error))
                                        case Right(_)    =>
                                          val retired = retire.map { sessionId =>
                                            (
                                              sessionId,
                                              CutoverRole.Responder,
                                              CutoverEvidence(confirmAckReceived = false, firstNewSessionDataReceived = true)
                                            )
                                          }
                                          (if retired.isEmpty then
                                             store.appendInbound(
                                               session.state,
                                               record.version,
                                               lock,
                                               session.conversationId,
                                               message.authorPub,
                                               message.devicePub,
                                               message.seq,
                                               message.body,
                                               message.wire.bytes,
                                               message.order
                                             )
                                           else
                                             store.appendInboundAndRetire(
                                               session.state,
                                               record.version,
                                               lock,
                                               session.conversationId,
                                               message.authorPub,
                                               message.devicePub,
                                               message.seq,
                                               message.body,
                                               message.wire.bytes,
                                               retired,
                                               message.order
                                             )
                                          ).map(mapStore)
                    yield appended
    yield result

  private def persistContact(contact: AppContact): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      loaded <- store.loadContact(contact.userPub)
      result <- loaded match
                  case Right(record)                  =>
                    store
                      .updateContact(contact.userPub, contactData(contact), record.version, lock)
                      .map(mapStore)
                  case Left(StorageError.NotFound(_)) =>
                    store.insertContact(contact.userPub, contactData(contact), lock).map(mapStore)
                  case Left(error)                    => IO.pure(Left(AppExecutionError.StoreFailed(error)))
    yield result

  private def persistSession(session: AppSession): IO[Either[AppExecutionError, Unit]] =
    for
      lock         <- lockGeneration
      conversation <- ensureConversation(session.conversationId, session, lock)
      result       <- conversation match
                        case Left(error) => IO.pure(Left(error))
                        case Right(_)    =>
                          store.loadSession(session.state.sessionId).flatMap {
                            case Right(record)                  =>
                              store.updateSession(session.state, record.version, lock).map(mapStore)
                            case Left(StorageError.NotFound(_)) =>
                              store.insertSession(session.state, lock).map(mapStore)
                            case Left(error)                    => IO.pure(Left(AppExecutionError.StoreFailed(error)))
                          }
    yield result

  private def persistSafetyVerified(userPub: yochat.core.Ed25519Public, verified: Boolean): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      loaded <- store.loadContact(userPub)
      result <- loaded match
                  case Right(record) => store.setSafetyVerified(userPub, verified, record.version, lock).map(mapStore)
                  case Left(error)   => IO.pure(Left(AppExecutionError.StoreFailed(error)))
    yield result

  private def persistHandshake(
    contact: Option[AppContact],
    conversation: Option[Conversation],
    sessions: Vector[AppSession],
    invitation: Option[AppInvitation],
    deleteInvitation: Option[ByteVector],
    retire: Vector[(yochat.core.SessionId, SessionRetirement)],
    deletePending: Vector[yochat.core.SessionId]
  ): IO[Either[AppExecutionError, Unit]] =
    val write = HandshakeWrite(
      contact = contact.map(value => value.userPub -> contactData(value)),
      conversation = conversation.map(value => value.id -> value.peerUserPub),
      sessions = sessions.map(_.state),
      invitation = invitation.map(value => value.id -> invitationData(value)),
      deleteInvitation = deleteInvitation,
      retire = retire.map { case (sessionId, retirement) =>
        retirement match
          case SessionRetirement.InitiatorAck       =>
            (sessionId, CutoverRole.Initiator, CutoverEvidence(confirmAckReceived = true, firstNewSessionDataReceived = false))
          case SessionRetirement.ResponderFirstData =>
            (sessionId, CutoverRole.Responder, CutoverEvidence(confirmAckReceived = false, firstNewSessionDataReceived = true))
      },
      deletePendingSessions = deletePending
    )
    store.persistHandshake(write).map(mapStore)

  private def persistRebound(
    message: OutgoingMessage,
    session: AppSession,
    oldSessionId: yochat.core.SessionId,
    oldSeq: Long,
    expectedVersion: Long
  ): IO[Either[AppExecutionError, RebindOutcome]] =
    for
      current <- store.loadSession(session.state.sessionId)
      lock    <- lockGeneration
      result  <- current match
                   case Left(error)   => IO.pure(Left(AppExecutionError.StoreFailed(error)))
                   case Right(record) =>
                     if expectedVersion >= 0 && record.version != expectedVersion then
                       IO.pure(Left(AppExecutionError.StoreFailed(StorageError.InvalidArgument("session-version"))))
                     else
                       store
                         .rebindOutbound(
                           session.state,
                           record.version,
                           lock,
                           oldSessionId,
                           message.conversationId,
                           message.authorPub,
                           message.devicePub,
                           oldSeq,
                           message.seq,
                           message.body,
                           message.wire.bytes
                         )
                         .map(_.left.map(AppExecutionError.StoreFailed.apply))
    yield result

  private def persistAttempt(message: OutgoingMessage): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      result <- store.setAttemptsByKey(
                  message.conversationId,
                  message.sessionId,
                  message.authorPub,
                  message.devicePub,
                  message.seq,
                  message.attempts,
                  lock
                )
    yield mapStore(result)

  private def persistLockGeneration(generation: Long): IO[Either[AppExecutionError, Unit]] =
    store.advanceLockGeneration(generation).map(mapStore)

  private def persistInvitation(invitation: AppInvitation): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      data    = invitationData(invitation)
      loaded <- store.loadInvitation(invitation.id)
      result <- loaded match
                  case Right(record)                  =>
                    store.updateInvitation(invitation.id, data, record.version, lock).map(mapStore)
                  case Left(StorageError.NotFound(_)) =>
                    store.insertInvitation(invitation.id, data, lock).map(mapStore)
                  case Left(error)                    => IO.pure(Left(AppExecutionError.StoreFailed(error)))
    yield result

  private def deleteInvitation(invitationId: ByteVector): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      loaded <- store.loadInvitation(invitationId)
      result <- loaded match
                  case Right(record)                  =>
                    store.deleteInvitation(invitationId, record.version, lock).map(mapStore)
                  case Left(StorageError.NotFound(_)) => IO.pure(Right(()))
                  case Left(error)                    => IO.pure(Left(AppExecutionError.StoreFailed(error)))
    yield result

  private def updateMessageState(
    conversationId: ByteVector,
    sessionId: yochat.core.SessionId,
    authorPub: yochat.core.Ed25519Public,
    devicePub: yochat.core.Ed25519Public,
    seq: Long,
    transition: MessageTransition
  ): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      loaded <- store.loadMessage(conversationId, sessionId, authorPub, devicePub, seq)
      result <- loaded match
                  case Left(error)   => IO.pure(Left(AppExecutionError.StoreFailed(error)))
                  case Right(record) =>
                    val target = transition match
                      case MessageTransition.Sent      => MessageState.Sent
                      case MessageTransition.Delivered => MessageState.Delivered
                      case MessageTransition.Failed    => MessageState.Failed
                    val next   =
                      (record.state, target) match
                        case (MessageState.Delivered, _)              => MessageState.Delivered
                        case (MessageState.Failed, MessageState.Sent) => MessageState.Failed
                        case _                                        => target
                    if next == record.state then IO.pure(Right(()))
                    else
                      store
                        .setMessageStateByKey(conversationId, sessionId, authorPub, devicePub, seq, record.state, next, lock)
                        .map(mapStore)
    yield result

  private def ensureConversation(
    conversationId: ByteVector,
    session: AppSession,
    lock: Long
  ): IO[Either[AppExecutionError, ConversationRecord]] =
    store.loadConversation(conversationId).flatMap {
      case Right(record)                  => IO.pure(Right(record))
      case Left(StorageError.NotFound(_)) =>
        store
          .createConversation(conversationId, session.state.peerUserPub, lock)
          .map(_.left.map(AppExecutionError.StoreFailed.apply))
      case Left(error)                    => IO.pure(Left(AppExecutionError.StoreFailed(error)))
    }

  private def lockGeneration: IO[Long] =
    store.lockGeneration.flatMap {
      case Right(lock) => IO.pure(lock)
      case Left(error) => IO.raiseError(new ExecutionAbort(AppExecutionError.StoreFailed(error)))
    }

  private def transportOnly(action: IO[Unit]): IO[Either[AppExecutionError, Unit]] =
    action.attempt.map {
      case Right(_) => Right(())
      case Left(_)  => Left(AppExecutionError.TransportFailed)
    }

  private def mapStore[A](value: Either[StorageError, A]): Either[AppExecutionError, Unit] =
    value match
      case Right(_)    => Right(())
      case Left(error) => Left(AppExecutionError.StoreFailed(error))

  private def contactData(contact: AppContact): ContactData =
    ContactData(
      channelLabel = contact.channelLabel,
      channelAccount = contact.channelAccount,
      peerChannelAccount = contact.peerChannelAccount,
      alias = contact.alias,
      note = contact.note,
      safetyVerified = contact.safetyVerified,
      confirmationState = contact.confirmation match
        case ContactConfirmation.Pending         => StoreConfirmation.Pending
        case ContactConfirmation.Confirmed       => StoreConfirmation.Confirmed
        case ContactConfirmation.AccountMismatch => StoreConfirmation.AccountMismatch
    )

  final private class ExecutionAbort(val error: AppExecutionError) extends RuntimeException(error.code)

  private def invitationData(invitation: AppInvitation): InvitationData =
    InvitationData(
      ephemeralPrivate = invitation.ephemeralPrivate,
      ratchetPrivate = invitation.ratchetPrivate,
      peerAccount = invitation.expectedAccount,
      state = invitationState(invitation.lifecycle),
      confirmationState = StoreConfirmation.Pending,
      payload = invitation.invitation.canonical,
      expiresAt = invitation.expiresAt,
      replyText = invitation.replyText,
      replyAttempts = invitation.replyAttempts
    )

  private def invitationState(lifecycle: InvitationLifecycle): InvitationState =
    lifecycle match
      case InvitationLifecycle.Issued        => InvitationState.Pending
      case InvitationLifecycle.AwaitingReply => InvitationState.Pending
      case InvitationLifecycle.PendingReply  => InvitationState.PendingReply
      case InvitationLifecycle.Accepted      => InvitationState.Accepted
      case InvitationLifecycle.Expired       => InvitationState.Expired
