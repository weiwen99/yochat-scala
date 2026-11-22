package yochat.server

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.{Ed25519KeyPair, Ed25519Public, Invitation, WireFrame}
import yochat.core.app.*
import yochat.store.{
  ContactData,
  ConversationRecord,
  InvitationRecord,
  InvitationState,
  MessageRecord,
  MessageState,
  SessionRecord,
  StorageError,
  Store,
  StoreSnapshot
}

/** Rebuilds the pure application state from the durable store. Conversations are replayed from persisted inbound messages so a restart
  * preserves the gap buffer and the next expected sequence without a second sequence table.
  */
object AppStateRestore:

  def load(store: Store): IO[Either[StorageError, AppState]] =
    for
      lock     <- store.lockGeneration
      snapshot <- store.snapshot
    yield for
      generation <- lock
      values     <- snapshot
      state      <- fromSnapshot(store, generation, values)
    yield state

  private def fromSnapshot(store: Store, generation: Long, snapshot: StoreSnapshot): Either[StorageError, AppState] =
    val identity = LocalIdentity(
      Ed25519KeyPair(store.identity.userPrivate, store.identity.userPublic),
      Ed25519KeyPair(store.identity.devicePrivate, store.identity.devicePublic)
    )
    for
      contacts      <- contactsFrom(snapshot)
      conversations <- conversationsFrom(snapshot)
      sessions      <- sessionsFrom(snapshot)
      outgoing      <- outgoingFrom(snapshot, sessions, generation)
      invitations   <- invitationsFrom(snapshot)
    yield AppState(
      identity = Some(identity),
      lock = LockState.Locked(generation),
      contacts = contacts,
      sessions = sessions,
      conversations = conversations,
      invitations = invitations,
      outgoing = outgoing,
      connection = ConnectionState.Offline
    )

  private def contactsFrom(snapshot: StoreSnapshot): Either[StorageError, Map[Ed25519Public, AppContact]] =
    snapshot.contacts.foldLeft[Either[StorageError, Map[Ed25519Public, AppContact]]](Right(Map.empty)) { (acc, record) =>
      acc.flatMap { values =>
        deviceFor(snapshot, record.userPub) match
          case None         => Left(StorageError.NotFound("session-device"))
          case Some(device) => Right(values.updated(record.userPub, contactFrom(record.userPub, device, record.data)))
      }
    }

  private def contactFrom(userPub: Ed25519Public, devicePub: Ed25519Public, data: ContactData): AppContact =
    AppContact(
      userPub = userPub,
      devicePub = devicePub,
      channelLabel = data.channelLabel,
      channelAccount = data.channelAccount,
      peerChannelAccount = data.peerChannelAccount,
      safetyVerified = data.safetyVerified,
      confirmation = data.confirmationState match
        case yochat.store.ContactConfirmation.Pending         => ContactConfirmation.Pending
        case yochat.store.ContactConfirmation.Confirmed       => ContactConfirmation.Confirmed
        case yochat.store.ContactConfirmation.AccountMismatch => ContactConfirmation.AccountMismatch
    )

  private def conversationsFrom(snapshot: StoreSnapshot): Either[StorageError, Map[ByteVector, Conversation]] =
    snapshot.conversations.foldLeft[Either[StorageError, Map[ByteVector, Conversation]]](Right(Map.empty)) { (acc, record) =>
      acc.flatMap { values =>
        conversationFrom(snapshot, record).map(conversation => values.updated(conversation.id, conversation))
      }
    }

  private def conversationFrom(snapshot: StoreSnapshot, record: ConversationRecord): Either[StorageError, Conversation] =
    deviceFor(snapshot, record.peerUserPub) match
      case None         => Left(StorageError.NotFound("conversation-device"))
      case Some(device) =>
        val base     = Conversation(record.id, record.peerUserPub, device, Map.empty, Set.empty, Map.empty)
        val messages = snapshot.messages.getOrElse(record.id, Nil).filter(_.state == MessageState.Received)
        messages
          .sortBy(message => (message.authorPub.bytes.toHex, message.seq))
          .foldLeft[Either[StorageError, Conversation]](Right(base)) { (conversation, message) =>
            conversation.flatMap { current =>
              WireFrame.parse(message.wire) match
                case Left(_)     => Left(StorageError.InvalidEnvelope)
                case Right(wire) =>
                  val buffered = BufferedMessage(
                    message.id,
                    message.authorPub,
                    message.devicePub,
                    message.seq,
                    message.body,
                    wire
                  )
                  Right(current.accept(buffered).conversation)
            }
          }

  private def sessionsFrom(snapshot: StoreSnapshot): Either[StorageError, Map[yochat.core.SessionId, AppSession]] =
    snapshot.sessions.foldLeft[Either[StorageError, Map[yochat.core.SessionId, AppSession]]](Right(Map.empty)) { (acc, record) =>
      acc.flatMap { values =>
        sessionFrom(snapshot, record).map(session => values.updated(record.state.sessionId, session))
      }
    }

  private def sessionFrom(snapshot: StoreSnapshot, record: SessionRecord): Either[StorageError, AppSession] =
    val peer         = record.state.peerUserPub
    val byPeer       = snapshot.conversations.filter(_.peerUserPub == peer).sortBy(_.id.toHex).headOption
    val byMessage    = snapshot.messages.values.flatten.find(_.sessionId == record.state.sessionId).map(_.conversationId)
    val conversation = byPeer.map(_.id).orElse(byMessage)
    conversation match
      case None     => Left(StorageError.NotFound("session-conversation"))
      case Some(id) =>
        val maxOutbound = snapshot.messages.values.flatten
          .filter(message => message.sessionId == record.state.sessionId && message.state != MessageState.Received)
          .map(_.seq)
          .maxOption
          .getOrElse(0L)
        val nextSeq     = if maxOutbound == Long.MaxValue then Long.MaxValue else maxOutbound + 1L
        Right(AppSession(record.state, id, math.max(1L, nextSeq)))

  private def outgoingFrom(
    snapshot: StoreSnapshot,
    sessions: Map[yochat.core.SessionId, AppSession],
    generation: Long
  ): Either[StorageError, Map[Long, OutgoingMessage]] =
    val rebuilt = snapshot.messages.values.flatten.foldLeft[Either[StorageError, Vector[OutgoingMessage]]](Right(Vector.empty)) {
      (acc, record) =>
        acc.flatMap { values =>
          outgoingFromRecord(record, sessions, generation) match
            case Left(error)    => Left(error)
            case Right(None)    => Right(values)
            case Right(Some(v)) => Right(values :+ v)
        }
    }
    rebuilt.map { values =>
      val retained = values.filter(_.state == OutgoingState.Failed).sortBy(_.id).takeRight(AppLimits.MaxFailedRetained).map(_.id).toSet
      values
        .filter(value => value.state != OutgoingState.Failed || retained.contains(value.id))
        .map(value => value.id -> value)
        .toMap
    }

  private def outgoingFromRecord(
    record: MessageRecord,
    sessions: Map[yochat.core.SessionId, AppSession],
    generation: Long
  ): Either[StorageError, Option[OutgoingMessage]] =
    record.state match
      case MessageState.Received | MessageState.Delivered => Right(None)
      case state                                          =>
        sessions.get(record.sessionId) match
          case None        => Left(StorageError.NotFound("outgoing-session"))
          case Some(value) =>
            WireFrame
              .parse(record.wire)
              .map { wire =>
                Some(
                  OutgoingMessage(
                    id = record.id,
                    conversationId = record.conversationId,
                    sessionId = record.sessionId,
                    authorPub = record.authorPub,
                    devicePub = record.devicePub,
                    peerDevicePub = value.state.peerDevicePub,
                    seq = record.seq,
                    body = record.body,
                    wire = wire,
                    generation = generation,
                    state = if state == MessageState.Failed then OutgoingState.Failed else OutgoingState.Queued,
                    attempts = if state == MessageState.Failed then AppLimits.MaxSendAttempts else 0
                  )
                )
              }
              .left
              .map(_ => StorageError.InvalidEnvelope)

  private def invitationsFrom(snapshot: StoreSnapshot): Either[StorageError, Map[ByteVector, AppInvitation]] =
    snapshot.invitations.foldLeft[Either[StorageError, Map[ByteVector, AppInvitation]]](Right(Map.empty)) { (acc, record) =>
      acc.flatMap { values =>
        invitationFrom(record).map(invitation => values.updated(record.id, invitation))
      }
    }

  private def invitationFrom(record: InvitationRecord): Either[StorageError, AppInvitation] =
    Invitation.fromCanonical(record.data.payload) match
      case Left(_)    => Left(StorageError.InvalidEnvelope)
      case Right(inv) =>
        val data = record.data
        Right(
          AppInvitation(
            id = record.id,
            invitation = inv,
            ephemeralPrivate = data.ephemeralPrivate,
            ratchetPrivate = data.ratchetPrivate,
            expectedAccount = data.peerAccount,
            lifecycle = data.state match
              case InvitationState.Pending                             =>
                if data.ephemeralPrivate.isDefined && data.ratchetPrivate.isDefined then InvitationLifecycle.Issued
                else InvitationLifecycle.AwaitingReply
              case InvitationState.Accepted | InvitationState.Consumed => InvitationLifecycle.Accepted
              case InvitationState.Declined | InvitationState.Expired  => InvitationLifecycle.Expired,
            expiresAt = data.expiresAt
          )
        )

  private def deviceFor(snapshot: StoreSnapshot, userPub: Ed25519Public): Option[Ed25519Public] =
    snapshot.sessions
      .find(_.state.peerUserPub == userPub)
      .map(_.state.peerDevicePub)
      .orElse(snapshot.messages.values.flatten.find(_.authorPub == userPub).map(_.devicePub))
