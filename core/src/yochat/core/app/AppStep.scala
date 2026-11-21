package yochat.core.app

import scodec.bits.ByteVector

import yochat.core.*

/** Pure S1 application kernel. Time, random values, keys, and persistence results enter as events. */
object Application:

  def step(state: AppState, event: AppEvent): (AppState, Vector[AppEffect]) =
    event match
      case AppEvent.Unlock(identity, nowMs, idleTimeoutMs)                                      =>
        val deadline = saturatingAdd(nowMs, math.max(0L, idleTimeoutMs))
        (state.copy(identity = Some(identity), lock = LockState.Unlocked(state.lock.value), idleDeadline = Some(deadline)), Vector.empty)
      case AppEvent.Lock                                                                        =>
        lock(state)
      case AppEvent.IdleTimeout(nowMs)                                                          =>
        state.idleDeadline match
          case Some(deadline) if nowMs >= deadline => lock(state)
          case _                                   => (state, Vector.empty)
      case AppEvent.UserSend(messageId, conversationId, body, nonce, nowMs)                     =>
        (for
          identity   <- state.identity
          generation <- unlockedGeneration(state)
          session    <- activeSession(state, conversationId)
          contact    <- state.contacts.get(session.state.peerUserPub)
          if contact.safetyVerified
          encrypted  <-
            Ratchet.encryptUser(session.state, identity.device.publicKey, identity.device.privateKey, session.nextSeq, nonce, body).toOption
        yield
          val (nextRatchet, wire) = encrypted
          val outgoing            = OutgoingMessage(
            id = messageId,
            conversationId = conversationId,
            sessionId = session.state.sessionId,
            seq = session.nextSeq,
            body = body,
            wire = wire,
            generation = generation,
            state = OutgoingState.Persisting,
            attempts = 0
          )
          val nextSession         = session.copy(state = nextRatchet, nextSeq = session.nextSeq + 1L)
          val nextState           = state.copy(
            sessions = state.sessions.updated(nextSession.state.sessionId, nextSession),
            outgoing = state.outgoing.updated(messageId, outgoing)
          )
          (nextState, Vector(AppEffect.PersistOutbound(outgoing, nextSession)))
        ).getOrElse((state, Vector.empty))
      case AppEvent.PersistOutboundSucceeded(messageId, generation, nowMs)                      =>
        matchOutgoing(state, messageId, generation, Set(OutgoingState.Persisting)) match
          case None        => (state, Vector.empty)
          case Some(value) =>
            val timer    = TimerId(TimerKind.Delivery, messageId)
            val deadline = saturatingAdd(nowMs, AppLimits.DeliveryTimeoutMs)
            val queued   = value.copy(state = OutgoingState.Queued)
            if state.connection == ConnectionState.Online then
              val next = state.copy(
                outgoing = state.outgoing.updated(messageId, queued),
                timers = state.timers.updated(timer, deadline)
              )
              (next, Vector(AppEffect.SendWire(messageId, queued.wire), AppEffect.StartTimer(timer, deadline)))
            else (state.copy(outgoing = state.outgoing.updated(messageId, queued)), Vector.empty)
      case AppEvent.PersistOutboundFailed(messageId, generation, _)                             =>
        matchOutgoing(state, messageId, generation, Set(OutgoingState.Persisting)) match
          case None        => (state, Vector.empty)
          case Some(value) =>
            val failed = value.copy(state = OutgoingState.Failed)
            (state.copy(outgoing = state.outgoing.updated(messageId, failed)), Vector(AppEffect.Notify(Notification.MessageFailed)))
      case AppEvent.SendSucceeded(messageId, generation)                                        =>
        matchOutgoing(state, messageId, generation, Set(OutgoingState.Queued, OutgoingState.Sent)) match
          case None        => (state, Vector.empty)
          case Some(value) => (state.copy(outgoing = state.outgoing.updated(messageId, value.copy(state = OutgoingState.Sent))), Vector.empty)
      case AppEvent.SendFailed(messageId, generation, nowMs)                                    =>
        matchOutgoing(state, messageId, generation, Set(OutgoingState.Queued, OutgoingState.Sent)) match
          case None        => (state, Vector.empty)
          case Some(value) => retryOrFail(state, value, nowMs)
      case AppEvent.TimerFired(timer, generation, nowMs)                                        =>
        matchOutgoing(state, timer.messageId, generation, Set(OutgoingState.Queued, OutgoingState.Sent)) match
          case None        => (state.copy(timers = state.timers - timer), Vector.empty)
          case Some(value) =>
            val withoutTimer = state.copy(timers = state.timers - timer)
            if state.connection == ConnectionState.Online then sendNow(withoutTimer, value, nowMs)
            else (withoutTimer, Vector.empty)
      case AppEvent.ConnectionChanged(online, nowMs)                                            =>
        if !online then
          val cancelled = state.timers.keys.toVector.map(AppEffect.CancelTimer.apply)
          (state.copy(connection = ConnectionState.Offline, timers = Map.empty), cancelled)
        else if state.connection == ConnectionState.Online then (state, Vector.empty)
        else
          val next = state.copy(connection = ConnectionState.Online)
          replay(next, nowMs)
      case AppEvent.InboundFrame(messageId, sessionId, frame, newDh, _)                         =>
        if !state.lock.isUnlocked then (state, Vector.empty)
        else
          state.sessions.get(sessionId) match
            case None        => (state, Vector.empty)
            case Some(value) =>
              state.conversations.get(value.conversationId) match
                case None               => (state, Vector.empty)
                case Some(conversation) =>
                  val key     = MessageKey(value.state.peerUserPub, frame.header.devicePub, frame.header.seq)
                  val seqSeen = conversation.seen.contains(key)
                  Ratchet.decrypt(value.state, frame, seqSeen, newDh) match
                    case Left(error)   => receiveFailure(state, sessionId, value, error)
                    case Right(result) => receiveSuccess(state, messageId, sessionId, value, conversation, frame, result)
      case AppEvent.SafetyVerified(userPub, _)                                                  =>
        state.contacts.get(userPub) match
          case None          => (state, Vector.empty)
          case Some(contact) =>
            val verified = contact.copy(safetyVerified = true)
            val sessions = state.sessions.values.filter(_.state.peerUserPub == userPub).toVector
            val updated  = sessions.map(session => session.copy(state = session.state.copy(safetyVerified = true)))
            val next     = state.copy(
              contacts = state.contacts.updated(userPub, verified),
              sessions = updated.foldLeft(state.sessions)((map, session) => map.updated(session.state.sessionId, session))
            )
            (next, AppEffect.PersistContact(verified) +: updated.map(AppEffect.PersistSession.apply))
      case AppEvent.InvitationCreated(invitation, expectedAccount, _)                           =>
        val id     = invitation.fields.ephPub.bytes
        val record = AppInvitation(id, invitation, expectedAccount, InvitationLifecycle.Issued, invitation.fields.expiresAt)
        val next   = state.copy(invitations = state.invitations.updated(id, record))
        (next, Vector(AppEffect.PersistInvitation(record)))
      case AppEvent.InvitationReceived(invitation, expectedAccount, _)                          =>
        val id     = invitation.fields.ephPub.bytes
        val record = AppInvitation(id, invitation, expectedAccount, InvitationLifecycle.AwaitingReply, invitation.fields.expiresAt)
        val next   = state.copy(invitations = state.invitations.updated(id, record))
        (next, Vector(AppEffect.PersistInvitation(record)))
      case AppEvent.ReplyCreated(invitationId, conversationId, result, nowMs)                   =>
        state.invitations.get(invitationId) match
          case None                                             => (state, Vector.empty)
          case Some(invitation) if nowMs > invitation.expiresAt =>
            val expired = invitation.copy(lifecycle = InvitationLifecycle.Expired)
            (state.copy(invitations = state.invitations.updated(invitationId, expired)), Vector(AppEffect.PersistInvitation(expired)))
          case Some(invitation)                                 =>
            val peer    = result.state.peerUserPub
            val contact = AppContact(
              userPub = peer,
              devicePub = result.state.peerDevicePub,
              channelLabel = invitation.invitation.fields.channelLabel,
              channelAccount = result.fields.channelAccount,
              peerChannelAccount = Some(invitation.invitation.fields.channelAccount),
              safetyVerified = false,
              confirmation = ContactConfirmation.Confirmed
            )
            val session = AppSession(result.state, conversationId, 1L)
            val next    = state.copy(
              contacts = state.contacts.updated(peer, contact),
              sessions = state.sessions.updated(session.state.sessionId, session),
              invitations = state.invitations - invitationId
            )
            (
              next,
              Vector(
                AppEffect.PersistContact(contact),
                AppEffect.PersistSession(session),
                AppEffect.DeleteInvitation(invitationId),
                AppEffect.SendReply(invitationId, result.text)
              )
            )
      case AppEvent.ReplyReceived(invitationId, conversationId, result, expectedAccount, nowMs) =>
        state.invitations.get(invitationId) match
          case None                                             => (state, Vector.empty)
          case Some(invitation) if nowMs > invitation.expiresAt =>
            val expired = invitation.copy(lifecycle = InvitationLifecycle.Expired)
            (state.copy(invitations = state.invitations.updated(invitationId, expired)), Vector(AppEffect.PersistInvitation(expired)))
          case Some(invitation)                                 =>
            val mismatch = expectedAccount.exists(account => account != result.fields.channelAccount)
            val peer     = result.fields.userPub
            val contact  = AppContact(
              userPub = peer,
              devicePub = result.fields.devicePub,
              channelLabel = invitation.invitation.fields.channelLabel,
              channelAccount = invitation.invitation.fields.channelAccount,
              peerChannelAccount = Some(result.fields.channelAccount),
              safetyVerified = false,
              confirmation = if mismatch then ContactConfirmation.AccountMismatch else ContactConfirmation.Pending
            )
            val session  = AppSession(result.state, conversationId, 1L)
            val next     = state.copy(
              contacts = state.contacts.updated(peer, contact),
              sessions = state.sessions.updated(session.state.sessionId, session),
              invitations = state.invitations - invitationId
            )
            (next, Vector(AppEffect.PersistContact(contact), AppEffect.PersistSession(session), AppEffect.DeleteInvitation(invitationId)))
      case AppEvent.RejectInvitation(invitationId)                                              =>
        (state.copy(invitations = state.invitations - invitationId), Vector(AppEffect.DeleteInvitation(invitationId)))
      case AppEvent.StartRekey(sessionId, r1, rekeyId, nonce, nowMs)                            =>
        state.identity match
          case None                                    => (state, Vector.empty)
          case Some(identity) if state.lock.isUnlocked =>
            state.sessions.get(sessionId) match
              case None        => (state, Vector.empty)
              case Some(value) =>
                Rekey.start(value.state, identity.device.publicKey, identity.device.privateKey, r1, rekeyId, nonce, nowMs) match
                  case Left(_)      => (state, Vector(AppEffect.Notify(Notification.RekeyFailed)))
                  case Right(start) =>
                    val updated = value.copy(state = start.state)
                    val next    = state.copy(sessions = state.sessions.updated(sessionId, updated))
                    (next, Vector(AppEffect.PersistSession(updated), AppEffect.SendRekeyRequest(sessionId, start.request)))
          case Some(_)                                 => (state, Vector.empty)
      case AppEvent.RekeyRequestReceived(sessionId, request, r2, r2Next, nonce)                 =>
        state.identity match
          case None                                    => (state, Vector.empty)
          case Some(identity) if state.lock.isUnlocked =>
            state.sessions.get(sessionId) match
              case None        => (state, Vector.empty)
              case Some(value) =>
                Rekey.respond(value.state, request, identity.device.publicKey, identity.device.privateKey, r2, r2Next, nonce) match
                  case Left(_)       => (state, Vector(AppEffect.Notify(Notification.RekeyFailed)))
                  case Right(result) =>
                    val oldSession = value.copy(state = result.state)
                    val newSession =
                      result.newSession.map(state => AppSession(state, value.conversationId, value.nextSeq, retireOldOnFirstData = true))
                    val sessions   = newSession.fold(state.sessions.updated(sessionId, oldSession))(session =>
                      state.sessions.updated(sessionId, oldSession).updated(session.state.sessionId, session)
                    )
                    val effects    = AppEffect.PersistSession(oldSession) +: newSession.map(AppEffect.PersistSession.apply).toVector :+ AppEffect
                      .SendRekeyAccept(sessionId, result.accept)
                    (state.copy(sessions = sessions), effects)
          case Some(_)                                 => (state, Vector.empty)
      case AppEvent.RekeyAcceptReceived(sessionId, accept, confirmNonce)                        =>
        state.identity match
          case None                                    => (state, Vector.empty)
          case Some(identity) if state.lock.isUnlocked =>
            state.sessions.get(sessionId) match
              case None        => (state, Vector.empty)
              case Some(value) =>
                Rekey.complete(value.state, accept) match
                  case Left(_)         => (state, Vector(AppEffect.Notify(Notification.RekeyFailed)))
                  case Right(complete) =>
                    complete.state.rekey match
                      case None          => (state, Vector(AppEffect.Notify(Notification.RekeyFailed)))
                      case Some(pending) =>
                        val control = ControlExtension(pending.epoch, pending.id)
                        Rekey.confirm(complete.newSession, identity.device.publicKey, identity.device.privateKey, control, confirmNonce) match
                          case Left(_)        => (state, Vector(AppEffect.Notify(Notification.RekeyFailed)))
                          case Right(confirm) =>
                            val oldSession = value.copy(state = complete.state)
                            val newSession = AppSession(confirm.state, value.conversationId, value.nextSeq)
                            val next       = state.copy(sessions =
                              state.sessions.updated(sessionId, oldSession).updated(newSession.state.sessionId, newSession)
                            )
                            (
                              next,
                              Vector(
                                AppEffect.PersistSession(oldSession),
                                AppEffect.PersistSession(newSession),
                                AppEffect.SendRekeyConfirm(newSession.state.sessionId, confirm.frame)
                              )
                            )
          case Some(_)                                 => (state, Vector.empty)
      case AppEvent.RekeyConfirmReceived(newSessionId, frame, expected, responseNonce, newDh)   =>
        state.identity match
          case None                                    => (state, Vector.empty)
          case Some(identity) if state.lock.isUnlocked =>
            state.sessions.get(newSessionId) match
              case None        => (state, Vector.empty)
              case Some(value) =>
                Rekey.acceptConfirm(
                  value.state,
                  frame,
                  expected,
                  identity.device.publicKey,
                  identity.device.privateKey,
                  responseNonce,
                  newDh
                ) match
                  case Left(_)       => (state, Vector(AppEffect.Notify(Notification.RekeyFailed)))
                  case Right(result) =>
                    val active = value.copy(state = result.state, retireOldOnFirstData = true)
                    val next   = state.copy(sessions = state.sessions.updated(newSessionId, active))
                    (next, Vector(AppEffect.PersistSession(active), AppEffect.SendRekeyConfirmAck(newSessionId, result.frame)))
          case Some(_)                                 => (state, Vector.empty)
      case AppEvent.RekeyAckReceived(newSessionId, frame, expected, newDh)                      =>
        state.sessions.get(newSessionId) match
          case None        => (state, Vector.empty)
          case Some(value) =>
            Rekey.acceptAck(value.state, frame, expected, newDh) match
              case Left(_)       => (state, Vector(AppEffect.Notify(Notification.RekeyFailed)))
              case Right(result) =>
                val active                    = value.copy(state = result.state, retireOldOnFirstData = false)
                val (retired, retiredEffects) = retireOld(state, active)
                val next                      = retired.copy(sessions = retired.sessions.updated(newSessionId, active))
                (next, AppEffect.PersistSession(active) +: retiredEffects)

  private def retireOld(state: AppState, active: AppSession): (AppState, Vector[AppEffect]) =
    val retired = state.sessions.values
      .filter(session =>
        session.conversationId == active.conversationId &&
          session.state.sessionId != active.state.sessionId &&
          session.state.phase == SessionPhase.ReadOnly &&
          !state.outgoing.values.exists(_.sessionId == session.state.sessionId)
      )
      .map(_.state.sessionId)
      .toVector
    val next    = state.copy(sessions = retired.foldLeft(state.sessions)((sessions, id) => sessions - id))
    (next, retired.map(AppEffect.DeleteSession.apply))

  private def lock(state: AppState): (AppState, Vector[AppEffect]) =
    val cancelled  = state.timers.keys.toVector.map(AppEffect.CancelTimer.apply)
    val generation = if state.lock.value == Long.MaxValue then Long.MaxValue else state.lock.value + 1L
    val next       = state.copy(
      lock = LockState.Locked(generation),
      idleDeadline = None,
      timers = Map.empty,
      outgoing = state.outgoing.filterNot { case (_, value) => value.state == OutgoingState.Persisting }
    )
    (next, cancelled)

  private def unlockedGeneration(state: AppState): Option[Long] =
    state.lock match
      case LockState.Unlocked(generation) => Some(generation)
      case LockState.Locked(_)            => None

  private def activeSession(state: AppState, conversationId: ByteVector): Option[AppSession] =
    state.sessions.values
      .filter(session => session.conversationId == conversationId && session.state.phase == SessionPhase.Active)
      .toVector
      .sortBy(_.state.sessionId.bytes.toHex)
      .headOption

  private def matchOutgoing(
    state: AppState,
    messageId: Long,
    generation: Long,
    allowed: Set[OutgoingState]
  ): Option[OutgoingMessage] =
    state.outgoing.get(messageId).filter(value => value.generation == generation && allowed.contains(value.state))

  private def retryOrFail(state: AppState, value: OutgoingMessage, nowMs: Long): (AppState, Vector[AppEffect]) =
    val attempts = value.attempts + 1
    if attempts >= AppLimits.MaxSendAttempts then
      val failed = value.copy(state = OutgoingState.Failed, attempts = attempts)
      val timer  = TimerId(TimerKind.Delivery, value.id)
      val next   = state.copy(outgoing = state.outgoing.updated(value.id, failed), timers = state.timers - timer)
      (next, Vector(AppEffect.Notify(Notification.MessageFailed)))
    else
      val timer    = TimerId(TimerKind.Retry, value.id)
      val deadline = saturatingAdd(nowMs, AppLimits.RetryBackoffMs)
      val queued   = value.copy(state = OutgoingState.Queued, attempts = attempts)
      val next     = state.copy(outgoing = state.outgoing.updated(value.id, queued), timers = state.timers.updated(timer, deadline))
      (next, Vector(AppEffect.StartTimer(timer, deadline)))

  private def sendNow(state: AppState, value: OutgoingMessage, nowMs: Long): (AppState, Vector[AppEffect]) =
    if value.attempts >= AppLimits.MaxSendAttempts then
      val failed = value.copy(state = OutgoingState.Failed)
      (state.copy(outgoing = state.outgoing.updated(value.id, failed)), Vector(AppEffect.Notify(Notification.MessageFailed)))
    else
      val timer    = TimerId(TimerKind.Delivery, value.id)
      val deadline = saturatingAdd(nowMs, AppLimits.DeliveryTimeoutMs)
      val queued   = value.copy(state = OutgoingState.Queued, attempts = value.attempts + 1)
      val next     = state.copy(outgoing = state.outgoing.updated(value.id, queued), timers = state.timers.updated(timer, deadline))
      (next, Vector(AppEffect.SendWire(value.id, value.wire), AppEffect.StartTimer(timer, deadline)))

  private def replay(state: AppState, nowMs: Long): (AppState, Vector[AppEffect]) =
    state.outgoing.toVector
      .filter { case (_, value) => value.state == OutgoingState.Queued || value.state == OutgoingState.Sent }
      .sortBy(_._1)
      .foldLeft((state, Vector.empty[AppEffect])) { case ((current, effects), (_, value)) =>
        val (next, added) = sendNow(current, value, nowMs)
        (next, effects ++ added)
      }

  private def receiveSuccess(
    state: AppState,
    messageId: Long,
    sessionId: SessionId,
    session: AppSession,
    conversation: Conversation,
    frame: WireFrame,
    result: ReceiveResult
  ): (AppState, Vector[AppEffect]) =
    val nextSession   = session.copy(state = result.state)
    val firstData     = frame.header.kind == MessageKind.Data && session.retireOldOnFirstData
    val active        = if firstData then nextSession.copy(retireOldOnFirstData = false) else nextSession
    val sessionEffect = if active == session then Vector.empty else Vector(AppEffect.PersistSession(active))
    if frame.header.kind == MessageKind.Data then
      val message                            = BufferedMessage(messageId, session.state.peerUserPub, frame.header.devicePub, frame.header.seq, result.plaintext, frame)
      val (nextConversation, disposition, _) = conversation.accept(message)
      val withSession                        = state.copy(
        sessions = state.sessions.updated(sessionId, active),
        conversations = state.conversations.updated(nextConversation.id, nextConversation)
      )
      val (next, retiredEffects)             = if firstData then retireOld(withSession, active) else (withSession, Vector.empty)
      disposition match
        case InboundDisposition.Applied                                 =>
          (next, sessionEffect ++ Vector(AppEffect.PersistInbound(message)) ++ retiredEffects)
        case InboundDisposition.Buffered                                =>
          (next, sessionEffect ++ Vector(AppEffect.PersistInbound(message), AppEffect.Notify(Notification.Waiting)) ++ retiredEffects)
        case InboundDisposition.Duplicate | InboundDisposition.Overflow =>
          (next, sessionEffect ++ retiredEffects)
    else (state.copy(sessions = state.sessions.updated(sessionId, active)), sessionEffect)

  private def receiveFailure(
    state: AppState,
    sessionId: SessionId,
    session: AppSession,
    error: ReceiveError
  ): (AppState, Vector[AppEffect]) =
    val nextSession = session.copy(state = error.state)
    val effects     =
      if nextSession == session then Vector.empty
      else
        Vector(AppEffect.PersistSession(nextSession)) ++
          (if error.error == CoreError.RekeyRequired then Vector(AppEffect.Notify(Notification.RekeyRequired)) else Vector.empty)
    (state.copy(sessions = state.sessions.updated(sessionId, nextSession)), effects)

  private def saturatingAdd(left: Long, right: Long): Long =
    if right > 0L && left > Long.MaxValue - right then Long.MaxValue else left + right
