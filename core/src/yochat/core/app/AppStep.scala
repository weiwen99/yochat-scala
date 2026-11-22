package yochat.core.app

import scodec.bits.ByteVector

import yochat.core.*

/** Pure S1 application kernel. Time, random values, keys, and persistence results enter as events. */
object Application:

  def step(state: AppState, event: AppEvent): (AppState, Vector[AppEffect]) =
    event match
      case AppEvent.Unlock(identity, nowMs, idleTimeoutMs)                                      =>
        val deadline = saturatingAdd(nowMs, math.max(0L, idleTimeoutMs))
        val current  = state.lock.value
        val outgoing = state.outgoing.map { case (id, value) => id -> value.copy(generation = current) }
        val next     = state.copy(
          identity = Some(identity),
          lock = LockState.Unlocked(current),
          idleDeadline = Some(deadline),
          outgoing = outgoing
        )
        pump(next, nowMs)
      case AppEvent.Lock                                                                        =>
        lock(state)
      case AppEvent.IdleTimeout(nowMs)                                                          =>
        state.idleDeadline match
          case Some(deadline) if nowMs >= deadline => lock(state)
          case _                                   => (state, Vector.empty)
      case AppEvent.UserSend(messageId, conversationId, body, nonce, nowMs)                     =>
        if state.outgoing.size >= AppLimits.MaxOutgoing then (state, Vector(AppEffect.Notify(Notification.QueueFull)))
        else
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
              authorPub = identity.user.publicKey,
              devicePub = identity.device.publicKey,
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
            val queued = value.copy(state = OutgoingState.Queued)
            pump(state.copy(outgoing = state.outgoing.updated(messageId, queued)), nowMs)
      case AppEvent.PersistOutboundFailed(messageId, generation, _)                             =>
        matchOutgoing(state, messageId, generation, Set(OutgoingState.Persisting)) match
          case None        => (state, Vector.empty)
          case Some(value) =>
            val failed = value.copy(state = OutgoingState.Failed)
            (trimFailed(state.copy(outgoing = state.outgoing.updated(messageId, failed))), Vector(AppEffect.Notify(Notification.MessageFailed)))
      case AppEvent.SendSucceeded(messageId, generation)                                        =>
        matchOutgoing(state, messageId, generation, Set(OutgoingState.InFlight, OutgoingState.Queued, OutgoingState.Sent)) match
          case None        => (state, Vector.empty)
          case Some(value) =>
            (
              state.copy(outgoing = state.outgoing.updated(messageId, value.copy(state = OutgoingState.Sent))),
              Vector(
                AppEffect.UpdateMessageState(
                  value.conversationId,
                  value.sessionId,
                  value.authorPub,
                  value.devicePub,
                  value.seq,
                  MessageTransition.Sent
                )
              )
            )
      case AppEvent.SendFailed(messageId, generation, nowMs)                                    =>
        matchOutgoing(state, messageId, generation, Set(OutgoingState.InFlight, OutgoingState.Queued, OutgoingState.Sent)) match
          case None        => (state, Vector.empty)
          case Some(value) =>
            val (next, effects) = retryOrFail(state, value, nowMs)
            val (pumped, more)  = pump(next, nowMs)
            (pumped, effects ++ more)
      case AppEvent.TimerFired(timer, generation, nowMs)                                        =>
        matchOutgoing(state, timer.messageId, generation, Set(OutgoingState.InFlight, OutgoingState.Queued, OutgoingState.Sent)) match
          case None        => (state.copy(timers = state.timers - timer), Vector.empty)
          case Some(value) =>
            val withoutTimer    = state.copy(timers = state.timers - timer)
            val (next, effects) = sendNow(withoutTimer, value, nowMs)
            val (pumped, more)  = pump(next, nowMs)
            (pumped, effects ++ more)
      case AppEvent.ConnectionChanged(online, nowMs)                                            =>
        if !online then
          val cancelled = state.timers.keys.toVector.map(AppEffect.CancelTimer.apply)
          val queued    = state.outgoing.map { case (id, value) =>
            val nextState =
              value.state match
                case OutgoingState.InFlight | OutgoingState.Sent => OutgoingState.Queued
                case other                                       => other
            id -> value.copy(state = nextState)
          }
          (state.copy(connection = ConnectionState.Offline, timers = Map.empty, outgoing = queued), cancelled)
        else if state.connection == ConnectionState.Online then (state, Vector.empty)
        else pump(state.copy(connection = ConnectionState.Online), nowMs)
      case AppEvent.InboundFrame(messageId, sessionId, frame, newDh, responseNonce, nowMs)      =>
        if !state.lock.isUnlocked then (state, Vector.empty)
        else
          state.sessions.get(sessionId) match
            case None        => (state, Vector.empty)
            case Some(value) =>
              state.conversations.get(value.conversationId) match
                case None               => (state, Vector.empty)
                case Some(conversation) =>
                  val key      = MessageKey(value.state.peerUserPub, frame.header.devicePub, frame.header.seq)
                  val expected = conversation.nextSeq.getOrElse(value.state.peerUserPub, 1L)
                  val seqSeen  =
                    frame.header.kind == MessageKind.Data &&
                      (frame.header.seq < expected || conversation.seen.contains(key))
                  Ratchet.decrypt(value.state, frame, seqSeen, newDh) match
                    case Left(error) if error.error == CoreError.DuplicateSeq && frame.header.kind == MessageKind.Data =>
                      val duplicate                  = value.copy(state = error.state)
                      val (finalSession, ackEffects) =
                        controlResponse(state, sessionId, duplicate, conversation, None, responseNonce)
                      val persist                    = if finalSession != value then Vector(AppEffect.PersistSession(finalSession)) else Vector.empty
                      (state.copy(sessions = state.sessions.updated(sessionId, finalSession)), persist ++ ackEffects)
                    case Left(error)                                                                                   => receiveFailure(state, sessionId, value, error)
                    case Right(result)                                                                                 =>
                      receiveSuccess(state, messageId, sessionId, value, conversation, frame, result, responseNonce, nowMs)
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
      case AppEvent.InvitationCreated(invitation, ephemeralPrivate, expectedAccount, _)         =>
        val id     = invitation.fields.ephPub.bytes
        val record =
          AppInvitation(id, invitation, Some(ephemeralPrivate), expectedAccount, InvitationLifecycle.Issued, invitation.fields.expiresAt)
        val next   = state.copy(invitations = state.invitations.updated(id, record))
        (next, Vector(AppEffect.PersistInvitation(record)))
      case AppEvent.InvitationReceived(invitation, expectedAccount, _)                          =>
        val id     = invitation.fields.ephPub.bytes
        val record = AppInvitation(id, invitation, None, expectedAccount, InvitationLifecycle.AwaitingReply, invitation.fields.expiresAt)
        val next   = state.copy(invitations = state.invitations.updated(id, record))
        (next, Vector.empty)
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
                val (retired, retiredEffects) = retireOld(state, active, SessionRetirement.InitiatorAck)
                val next                      = retired.copy(sessions = retired.sessions.updated(newSessionId, active))
                (next, AppEffect.PersistSession(active) +: retiredEffects)

  private def retireOld(state: AppState, active: AppSession, retirement: SessionRetirement): (AppState, Vector[AppEffect]) =
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
    (next, retired.map(id => AppEffect.DeleteSession(id, retirement)))

  private def lock(state: AppState): (AppState, Vector[AppEffect]) =
    val cancelled  = state.timers.keys.toVector.map(AppEffect.CancelTimer.apply)
    val generation = if state.lock.value == Long.MaxValue then Long.MaxValue else state.lock.value + 1L
    val outgoing   = state.outgoing.flatMap { case (id, value) =>
      value.state match
        case OutgoingState.Persisting                    => None
        case OutgoingState.InFlight | OutgoingState.Sent => Some(id -> value.copy(state = OutgoingState.Queued))
        case _                                           => Some(id -> value)
    }
    val next       = state.copy(
      lock = LockState.Locked(generation),
      idleDeadline = None,
      timers = Map.empty,
      outgoing = outgoing
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
    val attempts = math.min(value.attempts + 1, AppLimits.MaxSendAttempts)
    if attempts >= AppLimits.MaxSendAttempts then markFailed(state, value, attempts)
    else
      val delivery = TimerId(TimerKind.Delivery, value.id)
      val retry    = TimerId(TimerKind.Retry, value.id)
      val deadline = saturatingAdd(nowMs, AppLimits.RetryBackoffMs)
      val queued   = value.copy(state = OutgoingState.Queued, attempts = attempts)
      val hadTimer = state.timers.contains(delivery)
      val next     = state.copy(
        outgoing = state.outgoing.updated(value.id, queued),
        timers = (state.timers - delivery).updated(retry, deadline)
      )
      (
        next,
        (if hadTimer then Vector(AppEffect.CancelTimer(delivery)) else Vector.empty[AppEffect]) :+ AppEffect.StartTimer(retry, deadline)
      )

  private def sendNow(state: AppState, value: OutgoingMessage, nowMs: Long): (AppState, Vector[AppEffect]) =
    if state.connection != ConnectionState.Online then
      (state.copy(outgoing = state.outgoing.updated(value.id, value.copy(state = OutgoingState.Queued))), Vector.empty)
    else if value.attempts >= AppLimits.MaxSendAttempts then markFailed(state, value, value.attempts)
    else
      val delivery = TimerId(TimerKind.Delivery, value.id)
      val retry    = TimerId(TimerKind.Retry, value.id)
      val deadline = saturatingAdd(nowMs, AppLimits.DeliveryTimeoutMs)
      val inFlight = value.copy(state = OutgoingState.InFlight, attempts = value.attempts + 1)
      val hadRetry = state.timers.contains(retry)
      val next     = state.copy(
        outgoing = state.outgoing.updated(value.id, inFlight),
        timers = (state.timers - retry).updated(delivery, deadline)
      )
      val effects  =
        (if hadRetry then Vector(AppEffect.CancelTimer(retry)) else Vector.empty[AppEffect]) ++
          Vector(AppEffect.SendWire(value.id, value.wire), AppEffect.StartTimer(delivery, deadline))
      (next, effects)

  private def markFailed(state: AppState, value: OutgoingMessage, attempts: Int): (AppState, Vector[AppEffect]) =
    val delivery = TimerId(TimerKind.Delivery, value.id)
    val retry    = TimerId(TimerKind.Retry, value.id)
    val failed   = value.copy(state = OutgoingState.Failed, attempts = attempts)
    val next     = trimFailed(
      state.copy(
        outgoing = state.outgoing.updated(value.id, failed),
        timers = state.timers - delivery - retry
      )
    )
    (
      next,
      Vector(
        AppEffect.Notify(Notification.MessageFailed),
        AppEffect.UpdateMessageState(
          value.conversationId,
          value.sessionId,
          value.authorPub,
          value.devicePub,
          value.seq,
          MessageTransition.Failed
        )
      )
    )

  private def trimFailed(state: AppState): AppState =
    val failed = state.outgoing.values.filter(_.state == OutgoingState.Failed).toVector.sortBy(_.id)
    val drop   = math.max(0, failed.size - AppLimits.MaxFailedRetained)
    failed.take(drop).foldLeft(state)((current, value) => current.copy(outgoing = current.outgoing - value.id))

  private def pump(state: AppState, nowMs: Long): (AppState, Vector[AppEffect]) =
    if state.connection != ConnectionState.Online then (state, Vector.empty)
    else
      val slots = math.max(0, AppLimits.MaxInFlight - inFlightCount(state))
      val ready = state.outgoing.values
        .filter(value => value.state == OutgoingState.Queued && !state.timers.contains(TimerId(TimerKind.Retry, value.id)))
        .toVector
        .sortBy(value => (value.seq, value.id))
        .take(slots)
      ready.foldLeft((state, Vector.empty[AppEffect])) { case ((current, effects), value) =>
        val (next, added) = sendNow(current, value, nowMs)
        (next, effects ++ added)
      }

  private def inFlightCount(state: AppState): Int =
    state.outgoing.values.count(value => value.state == OutgoingState.InFlight || value.state == OutgoingState.Sent)

  private def acknowledge(state: AppState, sessionId: SessionId, ackedSeq: Long, nowMs: Long): (AppState, Vector[AppEffect]) =
    val delivered      = state.outgoing.values
      .filter(value =>
        value.sessionId == sessionId &&
          value.seq <= ackedSeq &&
          (value.state == OutgoingState.Queued || value.state == OutgoingState.InFlight || value.state == OutgoingState.Sent ||
            value.state == OutgoingState.Failed)
      )
      .toVector
      .sortBy(_.id)
    val timers         = delivered.flatMap(value => Vector(TimerId(TimerKind.Delivery, value.id), TimerId(TimerKind.Retry, value.id))).toSet
    val next           = state.copy(
      outgoing = delivered.foldLeft(state.outgoing)((current, value) => current - value.id),
      timers = timers.foldLeft(state.timers)((current, timer) => current - timer)
    )
    val effects        = delivered.flatMap { value =>
      Vector(
        AppEffect.CancelTimer(TimerId(TimerKind.Delivery, value.id)),
        AppEffect.CancelTimer(TimerId(TimerKind.Retry, value.id)),
        AppEffect.UpdateMessageState(
          value.conversationId,
          value.sessionId,
          value.authorPub,
          value.devicePub,
          value.seq,
          MessageTransition.Delivered
        )
      )
    }
    val (pumped, more) = pump(next, nowMs)
    (pumped, effects ++ more)

  private def requestResend(state: AppState, sessionId: SessionId, expectedSeq: Long, nowMs: Long): (AppState, Vector[AppEffect]) =
    val candidates      = state.outgoing.values
      .filter(value =>
        value.sessionId == sessionId &&
          value.seq >= expectedSeq &&
          (value.state == OutgoingState.Queued || value.state == OutgoingState.InFlight || value.state == OutgoingState.Sent)
      )
      .toVector
      .sortBy(value => (value.seq, value.id))
      .take(AppLimits.MaxInFlight)
    val reset           = candidates.foldLeft(state) { (current, value) =>
      current.copy(
        outgoing = current.outgoing.updated(value.id, value.copy(state = OutgoingState.Queued)),
        timers = current.timers - TimerId(TimerKind.Delivery, value.id) - TimerId(TimerKind.Retry, value.id)
      )
    }
    val cancels         = candidates.flatMap(value =>
      Vector(
        AppEffect.CancelTimer(TimerId(TimerKind.Delivery, value.id)),
        AppEffect.CancelTimer(TimerId(TimerKind.Retry, value.id))
      )
    )
    val (pumped, sends) = pump(reset, nowMs)
    (pumped, cancels ++ sends)

  private def receiveSuccess(
    state: AppState,
    messageId: Long,
    sessionId: SessionId,
    session: AppSession,
    conversation: Conversation,
    frame: WireFrame,
    result: ReceiveResult,
    responseNonce: Nonce,
    nowMs: Long
  ): (AppState, Vector[AppEffect]) =
    val nextSession = session.copy(state = result.state)
    val firstData   = frame.header.kind == MessageKind.Data && session.retireOldOnFirstData
    val active      = if firstData then nextSession.copy(retireOldOnFirstData = false) else nextSession
    frame.header.kind match
      case MessageKind.Data                                       =>
        val message                        = BufferedMessage(messageId, session.state.peerUserPub, frame.header.devicePub, frame.header.seq, result.plaintext, frame)
        val inbound                        = conversation.accept(message)
        val (finalSession, controlEffects) =
          controlResponse(state, sessionId, active, inbound.conversation, inbound.gap, responseNonce)
        val withSession                    = state.copy(
          sessions = state.sessions.updated(sessionId, finalSession),
          conversations = state.conversations.updated(inbound.conversation.id, inbound.conversation)
        )
        val (next, retiredEffects)         =
          if firstData then retireOld(withSession, finalSession, SessionRetirement.ResponderFirstData) else (withSession, Vector.empty)
        val inboundEffects                 =
          inbound.disposition match
            case InboundDisposition.Applied   => Vector(AppEffect.PersistInbound(message, finalSession))
            case InboundDisposition.Buffered  => Vector(AppEffect.PersistInbound(message, finalSession), AppEffect.Notify(Notification.Waiting))
            case InboundDisposition.Duplicate => Vector.empty
            case InboundDisposition.Overflow  => Vector(AppEffect.Notify(Notification.InboundOverflow))
        val sessionEffect                  =
          inbound.disposition match
            case InboundDisposition.Duplicate | InboundDisposition.Overflow =>
              if finalSession != session then Vector(AppEffect.PersistSession(finalSession)) else Vector.empty
            case _                                                          => Vector.empty
        (next, inboundEffects ++ sessionEffect ++ controlEffects ++ retiredEffects)
      case MessageKind.DataAck                                    =>
        val withSession     = state.copy(sessions = state.sessions.updated(sessionId, active))
        val (next, effects) = acknowledge(withSession, sessionId, frame.header.seq, nowMs)
        val persist         = if active != session then Vector(AppEffect.PersistSession(active)) else Vector.empty
        (next, persist ++ effects)
      case MessageKind.Gap                                        =>
        val withSession     = state.copy(sessions = state.sessions.updated(sessionId, active))
        val (next, effects) = requestResend(withSession, sessionId, frame.header.seq, nowMs)
        val persist         = if active != session then Vector(AppEffect.PersistSession(active)) else Vector.empty
        (next, persist ++ effects)
      case MessageKind.RekeyConfirm | MessageKind.RekeyConfirmAck =>
        (
          state.copy(sessions = state.sessions.updated(sessionId, active)),
          if active != session then Vector(AppEffect.PersistSession(active)) else Vector.empty
        )

  private def controlResponse(
    state: AppState,
    sessionId: SessionId,
    session: AppSession,
    conversation: Conversation,
    gap: Option[Long],
    nonce: Nonce
  ): (AppSession, Vector[AppEffect]) =
    state.identity match
      case None           => (session, Vector.empty)
      case Some(identity) =>
        val highest = conversation.nextSeq.getOrElse(session.state.peerUserPub, 1L) - 1L
        gap match
          case Some(expected)       =>
            Ratchet
              .encryptGap(session.state, identity.device.publicKey, identity.device.privateKey, expected, nonce)
              .toOption
              .fold((session, Vector.empty[AppEffect])) { case (next, frame) =>
                (session.copy(state = next), Vector(AppEffect.SendGap(sessionId, frame)))
              }
          case None if highest > 0L =>
            Ratchet
              .encryptDataAck(session.state, identity.device.publicKey, identity.device.privateKey, highest, nonce)
              .toOption
              .fold((session, Vector.empty[AppEffect])) { case (next, frame) =>
                (session.copy(state = next), Vector(AppEffect.SendAck(sessionId, frame)))
              }
          case None                 => (session, Vector.empty)

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
