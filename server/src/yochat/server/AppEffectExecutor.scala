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
  InvitationData,
  InvitationState,
  MessageState,
  SessionRecord,
  StorageError,
  Store
}

/** Transport boundary for effects that leave the process. The executor never opens sockets itself. */
trait AppTransport:

  def sendWire(messageId: Long, wire: WireFrame): IO[Unit]

  def sendReply(invitationId: ByteVector, text: String): IO[Unit]

  def sendRekeyRequest(sessionId: yochat.core.SessionId, request: RekeyRequest): IO[Unit]

  def sendRekeyAccept(sessionId: yochat.core.SessionId, accept: RekeyAccept): IO[Unit]

  def sendRekeyConfirm(newSessionId: yochat.core.SessionId, frame: WireFrame): IO[Unit]

  def sendRekeyConfirmAck(newSessionId: yochat.core.SessionId, frame: WireFrame): IO[Unit]

  def startTimer(timer: TimerId, deadlineMs: Long): IO[Unit]

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
final class AppEffectExecutor(store: Store, identity: LocalIdentity, transport: AppTransport):

  def executeAll(effects: Vector[AppEffect]): IO[Either[AppExecutionError, Unit]] =
    effects.foldLeft(IO.pure[Either[AppExecutionError, Unit]](Right(()))) { (accumulator, effect) =>
      accumulator.flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right(_)    => execute(effect)
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
      case AppEffect.PersistOutbound(message, session)                                                    =>
        persistOutbound(message, session)
      case AppEffect.PersistInbound(message, session)                                                     =>
        persistInbound(message, session)
      case AppEffect.PersistContact(contact)                                                              =>
        persistContact(contact)
      case AppEffect.PersistSession(session)                                                              =>
        persistSession(session)
      case AppEffect.PersistInvitation(invitation)                                                        =>
        persistInvitation(invitation)
      case AppEffect.DeleteInvitation(invitationId)                                                       =>
        deleteInvitation(invitationId)
      case AppEffect.DeleteSession(sessionId, retirement)                                                 =>
        deleteSession(sessionId, retirement)
      case AppEffect.UpdateMessageState(conversationId, sessionId, authorPub, devicePub, seq, transition) =>
        updateMessageState(conversationId, sessionId, authorPub, devicePub, seq, transition)
      case AppEffect.SendWire(messageId, wire)                                                            =>
        transportOnly(transport.sendWire(messageId, wire))
      case AppEffect.SendReply(invitationId, text)                                                        =>
        transportOnly(transport.sendReply(invitationId, text))
      case AppEffect.SendRekeyRequest(sessionId, request)                                                 =>
        transportOnly(transport.sendRekeyRequest(sessionId, request))
      case AppEffect.SendRekeyAccept(sessionId, accept)                                                   =>
        transportOnly(transport.sendRekeyAccept(sessionId, accept))
      case AppEffect.SendRekeyConfirm(newSessionId, frame)                                                =>
        transportOnly(transport.sendRekeyConfirm(newSessionId, frame))
      case AppEffect.SendRekeyConfirmAck(newSessionId, frame)                                             =>
        transportOnly(transport.sendRekeyConfirmAck(newSessionId, frame))
      case AppEffect.StartTimer(timer, deadlineMs)                                                        =>
        transportOnly(transport.startTimer(timer, deadlineMs))
      case AppEffect.CancelTimer(timer)                                                                   =>
        transportOnly(transport.cancelTimer(timer))
      case AppEffect.Notify(notification)                                                                 =>
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
                              MessageState.Pending
                            )
                            .map(mapStore)
    yield result

  private def persistInbound(message: BufferedMessage, session: AppSession): IO[Either[AppExecutionError, Unit]] =
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
                                          store
                                            .appendInbound(
                                              session.state,
                                              record.version,
                                              lock,
                                              session.conversationId,
                                              message.authorPub,
                                              message.devicePub,
                                              message.seq,
                                              message.body,
                                              message.wire.bytes
                                            )
                                            .map(mapStore)
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
      lock   <- lockGeneration
      loaded <- store.loadSession(session.state.sessionId)
      result <- loaded match
                  case Right(record)                  =>
                    store.updateSession(session.state, record.version, lock).map(mapStore)
                  case Left(StorageError.NotFound(_)) =>
                    store.insertSession(session.state, lock).map(mapStore)
                  case Left(error)                    => IO.pure(Left(AppExecutionError.StoreFailed(error)))
    yield result

  private def persistInvitation(invitation: AppInvitation): IO[Either[AppExecutionError, Unit]] =
    invitation.ephemeralPrivate match
      case None                   => IO.pure(Left(AppExecutionError.Unsupported("PersistInvitation:no-ephemeral-private")))
      case Some(ephemeralPrivate) =>
        for
          lock   <- lockGeneration
          data    = InvitationData(
                      ephemeralPrivate = ephemeralPrivate,
                      peerAccount = invitation.expectedAccount,
                      state = invitationState(invitation.lifecycle),
                      confirmationState = StoreConfirmation.Pending,
                      payload = invitation.invitation.canonical,
                      expiresAt = invitation.expiresAt
                    )
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

  private def deleteSession(sessionId: yochat.core.SessionId, retirement: SessionRetirement): IO[Either[AppExecutionError, Unit]] =
    for
      lock   <- lockGeneration
      loaded <- store.loadSession(sessionId)
      result <- loaded match
                  case Right(record)                  =>
                    val (role, evidence) = retirement match
                      case SessionRetirement.InitiatorAck       =>
                        (CutoverRole.Initiator, CutoverEvidence(confirmAckReceived = true, firstNewSessionDataReceived = false))
                      case SessionRetirement.ResponderFirstData =>
                        (CutoverRole.Responder, CutoverEvidence(confirmAckReceived = false, firstNewSessionDataReceived = true))
                    store.retireSession(sessionId, record.version, lock, role, evidence).map(mapStore)
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
      target  = transition match
                  case MessageTransition.Sent   => MessageState.Sent
                  case MessageTransition.Failed => MessageState.Failed
      result <-
        store.setMessageStateByKey(conversationId, sessionId, authorPub, devicePub, seq, MessageState.Pending, target, lock).map(mapStore)
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
      alias = None,
      note = None,
      safetyVerified = contact.safetyVerified,
      confirmationState = contact.confirmation match
        case ContactConfirmation.Pending         => StoreConfirmation.Pending
        case ContactConfirmation.Confirmed       => StoreConfirmation.Confirmed
        case ContactConfirmation.AccountMismatch => StoreConfirmation.AccountMismatch
    )

  final private class ExecutionAbort(val error: AppExecutionError) extends RuntimeException(error.code)

  private def invitationState(lifecycle: InvitationLifecycle): InvitationState =
    lifecycle match
      case InvitationLifecycle.Issued        => InvitationState.Pending
      case InvitationLifecycle.AwaitingReply => InvitationState.Pending
      case InvitationLifecycle.Accepted      => InvitationState.Accepted
      case InvitationLifecycle.Expired       => InvitationState.Expired
