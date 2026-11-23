package yochat.server

import java.security.SecureRandom
import java.time.Clock

import scala.concurrent.duration.*

import cats.effect.{FiberIO, IO, Ref, Resource}
import cats.effect.std.Queue
import scodec.bits.ByteVector

import yochat.core.{
  ControlExtension,
  Crypto,
  Ed25519Public,
  Invitation,
  MessageKind,
  Nonce,
  RekeyAccept,
  RekeyId,
  RekeyRequest,
  RekeyRole,
  Reply,
  ReplyResult,
  SessionId,
  WireFrame,
  X25519KeyPair,
  X25519Private
}
import yochat.core.app.*
import yochat.store.Store
import yochat.transport.{RelayClient, RelayPayload}

final private[server] class RuntimeError(message: String) extends RuntimeException(message)

private[server] enum RuntimeCommand:
  case App(event: AppEvent)
  case Inbound(from: Ed25519Public, payload: RelayPayload)
  case Connection(online: Boolean, nowMs: Long)

/** Single-writer runtime around the pure application kernel. Every inbound and effect completion is serialized through one bounded queue;
  * persistence and transport effects are executed in order before the next event is applied.
  */
final class AppRuntime private (
  identity: LocalIdentity,
  store: Store,
  stateRef: Ref[IO, AppState],
  queue: Queue[IO, RuntimeCommand],
  timers: TimerService,
  notifications: Queue[IO, Notification],
  random: RuntimeRandom,
  messageIds: Ref[IO, Long],
  fatal: Ref[IO, Option[AppExecutionError]],
  clock: Clock,
  loopFiber: FiberIO[Unit],
  inboundFiber: FiberIO[Unit],
  updatesFiber: FiberIO[Unit]
):

  def state: IO[AppState] = AppRuntime.readRef(stateRef)

  def submit(event: AppEvent): IO[Boolean] =
    AppRuntime.readRef(fatal).flatMap {
      case Some(_) => IO.pure(false)
      case None    => queue.tryOffer(RuntimeCommand.App(event))
    }

  private def ensureReady: IO[Unit] =
    AppRuntime.readRef(fatal).flatMap {
      case Some(error) => IO.raiseError(new RuntimeError(s"runtime-fatal:${error.code}"))
      case None        => IO.unit
    }

  def sendMessage(conversationId: ByteVector, body: String): IO[Long] =
    for
      _        <- ensureReady
      id       <- messageIds.updateAndGet(AppRuntime.nextId)
      nonce    <- random.nonce
      accepted <- submit(
                    AppEvent.UserSend(
                      id,
                      conversationId,
                      Crypto.text(body),
                      nonce,
                      clock.millis()
                    )
                  )
      _        <- if accepted then IO.unit else IO.raiseError(new RuntimeError("event-queue-full"))
    yield id

  def createInvitation(
    channelLabel: String,
    channelAccount: String,
    expectedAccount: Option[String],
    ttlMs: Long,
    note: String
  ): IO[Invitation] =
    for
      _          <- ensureReady
      ephemeral  <- random.x25519
      ratchet    <- random.x25519
      now         = clock.millis()
      expires     = AppRuntime.saturatingAdd(now, ttlMs)
      invitation <- IO.fromEither(
                      Invitation
                        .create(
                          identity.user,
                          identity.device,
                          ephemeral.publicKey,
                          ratchet.publicKey,
                          channelLabel,
                          channelAccount,
                          expires,
                          note
                        )
                        .left
                        .map(error => new RuntimeError(error.toString))
                    )
      accepted   <- submit(
                      AppEvent.InvitationCreated(
                        invitation,
                        ephemeral.privateKey,
                        ratchet.privateKey,
                        expectedAccount,
                        now
                      )
                    )
      _          <- if accepted then IO.unit else IO.raiseError(new RuntimeError("event-queue-full"))
      visible    <- awaitState(_.invitations.contains(invitation.fields.ephPub.bytes), 5.seconds)
      _          <- if visible then IO.unit else IO.raiseError(new RuntimeError("invitation-not-visible"))
      persisted  <- awaitStoredInvitation(invitation.fields.ephPub.bytes, _ => true, 5.seconds)
      _          <- if persisted then IO.unit else IO.raiseError(new RuntimeError("invitation-not-persisted"))
    yield invitation

  def receiveInvitation(text: String, expectedAccount: Option[String]): IO[Invitation] =
    for
      _          <- ensureReady
      invitation <- IO.fromEither(Invitation.fromText(text).left.map(error => new RuntimeError(error.toString)))
      accepted   <- submit(AppEvent.InvitationReceived(invitation, expectedAccount, clock.millis()))
      _          <- if accepted then IO.unit else IO.raiseError(new RuntimeError("event-queue-full"))
      visible    <- awaitState(_.invitations.contains(invitation.fields.ephPub.bytes), 5.seconds)
      _          <- if visible then IO.unit else IO.raiseError(new RuntimeError("invitation-not-visible"))
      persisted  <- awaitStoredInvitation(invitation.fields.ephPub.bytes, _ => true, 5.seconds)
      _          <- if persisted then IO.unit else IO.raiseError(new RuntimeError("invitation-not-persisted"))
    yield invitation

  def acceptInvitation(
    invitationId: ByteVector,
    channelLabel: String,
    channelAccount: String,
    note: String
  ): IO[ReplyResult] =
    for
      _              <- ensureReady
      state          <- AppRuntime.readRef(stateRef)
      invitation     <- state.invitations.get(invitationId) match
                          case Some(value) => IO.pure(value)
                          case None        => IO.raiseError(new RuntimeError("invitation-not-found"))
      _              <- if invitation.lifecycle == InvitationLifecycle.AwaitingReply then IO.unit
                        else IO.raiseError(new RuntimeError("invitation-not-pending"))
      _              <- invitation.expectedAccount match
                          case Some(expected) if expected != invitation.invitation.fields.channelAccount =>
                            IO.raiseError(new RuntimeError("account-mismatch"))
                          case _                                                                         => IO.unit
      ephemeral      <- random.x25519
      ratchet0       <- random.x25519
      ratchet1       <- random.x25519
      nonce          <- random.nonce
      conversationId <- random.bytes(32)
      now             = clock.millis()
      result         <- IO.fromEither(
                          Reply
                            .create(
                              invitation.invitation,
                              identity.user,
                              identity.device,
                              ephemeral,
                              ratchet0,
                              ratchet1,
                              channelLabel,
                              channelAccount,
                              note,
                              nonce,
                              now
                            )
                            .left
                            .map(error => new RuntimeError(error.toString))
                        )
      accepted       <- submit(AppEvent.ReplyCreated(invitationId, conversationId, result, now))
      _              <- if accepted then IO.unit else IO.raiseError(new RuntimeError("event-queue-full"))
      visible        <- awaitState(
                          _.invitations.get(invitationId).forall(_.lifecycle != InvitationLifecycle.AwaitingReply),
                          5.seconds
                        )
      _              <- if visible then IO.unit else IO.raiseError(new RuntimeError("reply-not-visible"))
      persisted      <- awaitStoredInvitation(
                          invitationId,
                          data => data.state == yochat.store.InvitationState.PendingReply && data.replyText.contains(result.text),
                          5.seconds,
                          missingIsSuccess = true
                        )
      _              <- if persisted then IO.unit else IO.raiseError(new RuntimeError("reply-not-persisted"))
    yield result

  def startRekey(sessionId: SessionId): IO[Unit] =
    for
      _        <- ensureReady
      state    <- AppRuntime.readRef(stateRef)
      _        <- if state.sessions.contains(sessionId) then IO.unit else IO.raiseError(new RuntimeError("session-not-found"))
      r1       <- random.x25519
      id       <- random.rekeyId
      nonce    <- random.nonce
      accepted <- submit(AppEvent.StartRekey(sessionId, r1, id, nonce, clock.millis()))
      _        <- if accepted then IO.unit else IO.raiseError(new RuntimeError("event-queue-full"))
    yield ()

  def nextNotification: IO[Notification] = notifications.take

  def fatalError: IO[Option[AppExecutionError]] = AppRuntime.readRef(fatal)

  private def awaitStoredInvitation(
    invitationId: ByteVector,
    predicate: yochat.store.InvitationData => Boolean,
    timeout: FiniteDuration,
    missingIsSuccess: Boolean = false
  ): IO[Boolean] =
    def loop(remaining: Long): IO[Boolean] =
      store.loadInvitation(invitationId).flatMap {
        case Right(record) if predicate(record.data)                         => IO.pure(true)
        case Right(_) if remaining > 0L                                      => IO.sleep(10.millis) *> loop(remaining - 10L)
        case Right(_)                                                        => IO.pure(false)
        case Left(yochat.store.StorageError.NotFound(_)) if missingIsSuccess => IO.pure(true)
        case Left(yochat.store.StorageError.NotFound(_)) if remaining > 0L   =>
          IO.sleep(10.millis) *> loop(remaining - 10L)
        case Left(error)                                                     => IO.raiseError(new RuntimeError(s"store:${error.code}"))
      }
    loop(timeout.toMillis)

  def awaitState(predicate: AppState => Boolean, timeout: FiniteDuration): IO[Boolean] =
    def loop(remaining: Long): IO[Boolean] =
      AppRuntime.readRef(stateRef).flatMap { current =>
        if predicate(current) then IO.pure(true)
        else if remaining <= 0L then IO.pure(false)
        else IO.sleep(10.millis) *> loop(remaining - 10L)
      }
    loop(timeout.toMillis)

  def close: IO[Unit] =
    loopFiber.cancel *> inboundFiber.cancel *> updatesFiber.cancel *> timers.close

object AppRuntime:

  private[server] def readRef[A](ref: Ref[IO, A]): IO[A] =
    ref.get // scalafix:ok DisableSyntax.no-unsafe-get

  private[server] def isDurable(effect: AppEffect): Boolean =
    effect match
      case _: AppEffect.PersistOutbound | _: AppEffect.PersistInbound | _: AppEffect.PersistInboundAndRetire | _: AppEffect.PersistContact |
          _: AppEffect.PersistSession | _: AppEffect.PersistSafetyVerified | _: AppEffect.PersistInvitation | _: AppEffect.PersistHandshake |
          _: AppEffect.PersistAttempt | _: AppEffect.PersistLockGeneration | _: AppEffect.DeleteInvitation | _: AppEffect.UpdateMessageState =>
        true
      case _ => false

  def start(
    store: Store,
    relay: RelayClient,
    idleTimeoutMs: Long = 24L * 60L * 60L * 1_000L,
    queueCapacity: Int = 4_096,
    clock: Clock = Clock.systemUTC(),
    onState: AppState => IO[Unit] = _ => IO.unit
  ): Resource[IO, AppRuntime] =
    Resource.make(create(store, relay, idleTimeoutMs, queueCapacity, clock, onState))(_.close)

  private def create(
    store: Store,
    relay: RelayClient,
    idleTimeoutMs: Long,
    queueCapacity: Int,
    clock: Clock,
    onState: AppState => IO[Unit]
  ): IO[AppRuntime] =
    for
      restored      <- AppStateRestore.load(store)
      state         <- restored match
                         case Left(error)  => IO.raiseError(new RuntimeError(s"restore-failed:${error.code}"))
                         case Right(value) => IO.pure(value)
      identity      <- state.identity match
                         case Some(value) => IO.pure(value)
                         case None        => IO.raiseError(new RuntimeError("identity-missing"))
      queue         <- Queue.bounded[IO, RuntimeCommand](math.max(16, queueCapacity))
      stateRef      <- Ref.of[IO, AppState](state)
      fatal         <- Ref.of[IO, Option[AppExecutionError]](None)
      notifications <- Queue.bounded[IO, Notification](64)
      messageIds    <- Ref.of[IO, Long](initialMessageId(state))
      timers        <- TimerService.create(queue, stateRef, clock)
      random         = new RuntimeRandom
      transport      = new RelayAppTransport(relay, queue, notifications, clock, timers)
      executor       = new AppEffectExecutor(store, identity, transport)
      loop          <- eventLoop(transport, queue, stateRef, messageIds, random, executor, fatal, clock, onState).start
      inbound       <- relay.incoming
                         .flatMap(incoming => queue.tryOffer(RuntimeCommand.Inbound(incoming.from, incoming.payload)).void)
                         .foreverM
                         .void
                         .start
      updates       <- relay.updates
                         .flatMap(online => queue.tryOffer(RuntimeCommand.Connection(online, clock.millis())).void)
                         .foreverM
                         .void
                         .start
      runtime        = new AppRuntime(
                         identity,
                         store,
                         stateRef,
                         queue,
                         timers,
                         notifications,
                         random,
                         messageIds,
                         fatal,
                         clock,
                         loop,
                         inbound,
                         updates
                       )
      online        <- relay.isOnline
      _             <- queue.tryOffer(RuntimeCommand.Connection(online, clock.millis()))
      accepted      <- queue.tryOffer(RuntimeCommand.App(AppEvent.Unlock(identity, clock.millis(), idleTimeoutMs)))
      _             <- if accepted then IO.unit else IO.raiseError(new RuntimeError("event-queue-full"))
    yield runtime

  private def eventLoop(
    transport: RelayAppTransport,
    queue: Queue[IO, RuntimeCommand],
    stateRef: Ref[IO, AppState],
    messageIds: Ref[IO, Long],
    random: RuntimeRandom,
    executor: AppEffectExecutor,
    fatal: Ref[IO, Option[AppExecutionError]],
    clock: Clock,
    onState: AppState => IO[Unit]
  ): IO[Unit] =
    AppRuntime.readRef(fatal).flatMap {
      case Some(_) => IO.unit
      case None    =>
        queue.take
          .flatMap(command => handle(transport, command, queue, stateRef, messageIds, random, executor, fatal, clock, onState))
          .attempt
          .flatMap {
            case Right(_)    => eventLoop(transport, queue, stateRef, messageIds, random, executor, fatal, clock, onState)
            case Left(error) =>
              fatal.set(Some(AppExecutionError.Unsupported(s"runtime-loop:${error.getClass.getSimpleName}")))
          }
    }

  private def handle(
    transport: RelayAppTransport,
    command: RuntimeCommand,
    queue: Queue[IO, RuntimeCommand],
    stateRef: Ref[IO, AppState],
    messageIds: Ref[IO, Long],
    random: RuntimeRandom,
    executor: AppEffectExecutor,
    fatal: Ref[IO, Option[AppExecutionError]],
    clock: Clock,
    onState: AppState => IO[Unit]
  ): IO[Unit] =
    command match
      case RuntimeCommand.App(event)             =>
        applyEvent(transport, event, queue, stateRef, executor, fatal, clock, onState) *>
          (event match
            case AppEvent.Unlock(_, _, _) => resumeGaps(queue, stateRef, random, clock)
            case _                        => IO.unit)
      case RuntimeCommand.Connection(on, now)    =>
        applyEvent(transport, AppEvent.ConnectionChanged(on, now), queue, stateRef, executor, fatal, clock, onState) *>
          (if on then resumeGaps(queue, stateRef, random, clock) else IO.unit)
      case RuntimeCommand.Inbound(from, payload) =>
        translateInbound(from, payload, messageIds, random, stateRef, clock).flatMap {
          case None        => IO.unit
          case Some(event) => applyEvent(transport, event, queue, stateRef, executor, fatal, clock, onState)
        }

  private def applyEvent(
    transport: RelayAppTransport,
    event: AppEvent,
    queue: Queue[IO, RuntimeCommand],
    stateRef: Ref[IO, AppState],
    executor: AppEffectExecutor,
    fatal: Ref[IO, Option[AppExecutionError]],
    clock: Clock,
    onState: AppState => IO[Unit]
  ): IO[Unit] =
    for
      current        <- AppRuntime.readRef(stateRef)
      (next, effects) = Application.step(current, event)
      durable         = effects.filter(AppRuntime.isDurable)
      external        = effects.filterNot(AppRuntime.isDurable)
      result         <- executor.executeAll(durable)
      _              <- result match
                          case Left(error) => fatal.set(Some(error))
                          case Right(_)    =>
                            stateRef.set(next) *> onState(next) *>
                              feedPersisted(durable, transport, queue, stateRef, executor, fatal, clock, onState) *>
                              executeExternal(external, transport, queue, stateRef, executor, fatal, clock, onState)
    yield ()

  private def feedPersisted(
    effects: Vector[AppEffect],
    transport: RelayAppTransport,
    queue: Queue[IO, RuntimeCommand],
    stateRef: Ref[IO, AppState],
    executor: AppEffectExecutor,
    fatal: Ref[IO, Option[AppExecutionError]],
    clock: Clock,
    onState: AppState => IO[Unit]
  ): IO[Unit] =
    effects.collect { case AppEffect.PersistOutbound(message, _) => message }.foldLeft(IO.unit) { (acc, message) =>
      acc *> applyEvent(
        transport,
        AppEvent.PersistOutboundSucceeded(message.id, message.generation, clock.millis()),
        queue,
        stateRef,
        executor,
        fatal,
        clock,
        onState
      )
    }

  private def executeExternal(
    effects: Vector[AppEffect],
    transport: RelayAppTransport,
    queue: Queue[IO, RuntimeCommand],
    stateRef: Ref[IO, AppState],
    executor: AppEffectExecutor,
    fatal: Ref[IO, Option[AppExecutionError]],
    clock: Clock,
    onState: AppState => IO[Unit]
  ): IO[Unit] =
    effects.foldLeft(IO.unit) { (acc, effect) =>
      acc.flatMap { _ =>
        AppRuntime.readRef(fatal).flatMap {
          case Some(_) => IO.unit
          case None    =>
            effect match
              case AppEffect.SendWire(toDevice, messageId, generation, wire) =>
                transport
                  .sendWireResult(toDevice, messageId, generation, wire)
                  .flatMap(result => applyEvent(transport, result, queue, stateRef, executor, fatal, clock, onState))
              case AppEffect.SendReply(invitationId, toDevice, text)         =>
                transport
                  .sendReplyResult(invitationId, toDevice, text)
                  .flatMap(result => applyEvent(transport, result, queue, stateRef, executor, fatal, clock, onState))
              case _                                                         =>
                executor.execute(effect).flatMap {
                  case Right(_)                                => IO.unit
                  case Left(AppExecutionError.TransportFailed) => IO.unit
                  case Left(error)                             => fatal.set(Some(error))
                }
        }
      }
    }

  private def resumeGaps(
    queue: Queue[IO, RuntimeCommand],
    stateRef: Ref[IO, AppState],
    random: RuntimeRandom,
    clock: Clock
  ): IO[Unit] =
    AppRuntime.readRef(stateRef).flatMap { state =>
      if state.connection != ConnectionState.Online || !state.lock.isUnlocked then IO.unit
      else
        val gaps = state.conversations.values.filter(_.gapRequested.isDefined).toVector.sortBy(_.id.toHex)
        gaps.foldLeft(IO.unit) { (acc, conversation) =>
          acc *> random.nonce.flatMap { nonce =>
            queue.tryOffer(RuntimeCommand.App(AppEvent.ResumeGap(conversation.id, nonce, clock.millis()))).void
          }
        }
    }

  private def translateInbound(
    from: Ed25519Public,
    payload: RelayPayload,
    messageIds: Ref[IO, Long],
    random: RuntimeRandom,
    stateRef: Ref[IO, AppState],
    clock: Clock
  ): IO[Option[AppEvent]] =
    AppRuntime.readRef(stateRef).flatMap { state =>
      val now = clock.millis()
      payload match
        case RelayPayload.Wire(bytes)         =>
          WireFrame.parse(bytes) match
            case Left(_)      => IO.pure(None)
            case Right(frame) =>
              state.sessions.get(frame.header.sessionId) match
                case None        => IO.pure(None)
                case Some(value) =>
                  for
                    nonce <- random.nonce
                    newDh <- if value.state.dhPeer.contains(frame.header.dhPub) then IO.pure(None)
                             else random.x25519.map(Some(_))
                    event <- frame.header.kind match
                               case MessageKind.Data | MessageKind.DataAck | MessageKind.Gap =>
                                 messageIds
                                   .updateAndGet(nextId)
                                   .map(id => Some(AppEvent.InboundFrame(id, frame.header.sessionId, frame, newDh, nonce, now)))
                               case MessageKind.RekeyConfirm                                 =>
                                 expectedControl(state, frame.header.sessionId, RekeyRole.Responder) match
                                   case None           => IO.pure(None)
                                   case Some(expected) =>
                                     IO.pure(Some(AppEvent.RekeyConfirmReceived(frame.header.sessionId, frame, expected, nonce, newDh)))
                               case MessageKind.RekeyConfirmAck                              =>
                                 expectedControl(state, frame.header.sessionId, RekeyRole.Initiator) match
                                   case None           => IO.pure(None)
                                   case Some(expected) =>
                                     random.x25519.map(dh => Some(AppEvent.RekeyAckReceived(frame.header.sessionId, frame, expected, dh)))
                  yield event
        case RelayPayload.Reply(text)         => replyEvent(state, from, text, now, random)
        case RelayPayload.RekeyRequest(bytes) =>
          RekeyRequest.fromBytes(bytes) match
            case Left(_)        => IO.pure(None)
            case Right(request) =>
              if !state.sessions.contains(request.sessionId) then IO.pure(None)
              else
                for
                  r2     <- random.x25519
                  r2Next <- random.x25519
                  nonce  <- random.nonce
                yield Some(AppEvent.RekeyRequestReceived(request.sessionId, request, r2, r2Next, nonce, clock.millis()))
        case RelayPayload.RekeyAccept(bytes)  =>
          RekeyAccept.fromBytes(bytes) match
            case Left(_)       => IO.pure(None)
            case Right(accept) =>
              if !state.sessions.contains(accept.sessionId) then IO.pure(None)
              else random.nonce.map(nonce => Some(AppEvent.RekeyAcceptReceived(accept.sessionId, accept, nonce, clock.millis())))
    }

  private def replyEvent(
    state: AppState,
    from: Ed25519Public,
    text: String,
    now: Long,
    random: RuntimeRandom
  ): IO[Option[AppEvent]] =
    def tryInvitation(values: List[AppInvitation]): IO[Option[AppEvent]] =
      values match
        case Nil          => IO.pure(None)
        case head :: tail =>
          (head.ephemeralPrivate, head.ratchetPrivate) match
            case (Some(ephemeral), Some(ratchet)) =>
              val ratchetPair = X25519KeyPair(ratchet, head.invitation.fields.ratchetPub)
              Reply.accept(head.invitation, text, ephemeral, ratchetPair, now) match
                case Right(result) if result.fields.devicePub == from =>
                  random
                    .bytes(32)
                    .map(conversationId => Some(AppEvent.ReplyReceived(head.id, conversationId, result, head.expectedAccount, now)))
                case _                                                => tryInvitation(tail)
            case _                                => tryInvitation(tail)
    val candidates                                                       = state.invitations.valuesIterator
      .filter(invitation =>
        (invitation.lifecycle == InvitationLifecycle.Issued || invitation.lifecycle == InvitationLifecycle.AwaitingReply) &&
          invitation.ephemeralPrivate.isDefined && invitation.ratchetPrivate.isDefined
      )
      .toVector
      .sortBy(invitation => (invitation.expiresAt, invitation.id.toHex))
      .take(AppLimits.MaxReplyCandidates)
      .toList
    tryInvitation(candidates)

  private def expectedControl(state: AppState, newSessionId: SessionId, role: RekeyRole): Option[ControlExtension] =
    state.sessions.get(newSessionId).flatMap { newSession =>
      state.sessions.values
        .filter(_.conversationId == newSession.conversationId)
        .flatMap(_.state.rekey)
        .find(rekey => rekey.role == role && rekey.newSessionId.contains(newSessionId))
        .map(rekey => ControlExtension(rekey.epoch, rekey.id))
    }

  private def initialMessageId(state: AppState): Long =
    state.outgoing.keys.maxOption.fold(1L)(nextId)

  private def nextId(value: Long): Long =
    if value == Long.MaxValue then 1L else value + 1L

  private def saturatingAdd(left: Long, right: Long): Long =
    if right > 0L && left > Long.MaxValue - right then Long.MaxValue else left + right

final private[server] class RuntimeRandom:
  private val random = new SecureRandom()

  def bytes(length: Int): IO[ByteVector] =
    IO.blocking {
      val value = new Array[Byte](length)
      random.nextBytes(value)
      ByteVector.view(value)
    }

  def nonce: IO[Nonce] =
    bytes(Nonce.Length).flatMap(value => IO.fromEither(Nonce.from(value).left.map(error => new RuntimeError(error.toString))))

  def x25519: IO[X25519KeyPair] =
    bytes(32)
      .flatMap(value => IO.fromEither(X25519Private.from(value).left.map(error => new RuntimeError(error.toString))))
      .flatMap(seed => IO.fromEither(X25519KeyPair.fromSeed(seed).left.map(error => new RuntimeError(error.toString))))

  def rekeyId: IO[RekeyId] =
    bytes(RekeyId.Length).flatMap(value => IO.fromEither(RekeyId.from(value).left.map(error => new RuntimeError(error.toString))))

final private[server] class TimerService private (
  queue: Queue[IO, RuntimeCommand],
  stateRef: Ref[IO, AppState],
  clock: Clock,
  fibers: Ref[IO, Map[TimerId, (Long, FiberIO[Unit])]],
  tokens: Ref[IO, Long]
):

  def start(timer: TimerId, deadlineMs: Long): IO[Unit] =
    for
      generation <- generationFor(timer)
      token      <- tokens.updateAndGet(value => if value == Long.MaxValue then 1L else value + 1L)
      previous   <- fibers.modify(values => (values.removed(timer), values.get(timer).map(_._2)))
      _          <- previous.fold(IO.unit)(_.cancel)
      delay       = math.max(0L, deadlineMs - clock.millis())
      gate       <- cats.effect.Deferred[IO, FiberIO[Unit]]
      fire        = gate.get.flatMap(_ => IO.sleep(delay.millis)) // scalafix:ok DisableSyntax.no-unsafe-get
      fiber      <- (
                      fire *>
                        fibers
                          .modify(values =>
                            values.get(timer) match
                              case Some((current, _)) if current == token => (values.removed(timer), true)
                              case _                                      => (values, false)
                          )
                          .flatMap(shouldFire =>
                            if shouldFire then queue.offer(RuntimeCommand.App(AppEvent.TimerFired(timer, generation, clock.millis()))).void
                            else IO.unit
                          )
                    ).start
      _          <- fibers.update(_.updated(timer, (token, fiber)))
      _          <- gate.complete(fiber).void
    yield ()

  private[server] def activeCount: IO[Int] = AppRuntime.readRef(fibers).map(_.size)

  def cancel(timer: TimerId): IO[Unit] =
    fibers.modify(values => (values.removed(timer), values.get(timer).map(_._2))).flatMap {
      case None        => IO.unit
      case Some(fiber) => fiber.cancel
    }

  def close: IO[Unit] =
    AppRuntime
      .readRef(fibers)
      .flatMap(_.values.toVector.foldLeft(IO.unit) { case (acc, (_, fiber)) => acc *> fiber.cancel })
      .guarantee(fibers.set(Map.empty))

  private def generationFor(timer: TimerId): IO[Long] =
    timer match
      case TimerId.Message(_, messageId)                         => AppRuntime.readRef(stateRef).map(_.outgoing.get(messageId).fold(0L)(_.generation))
      case TimerId.Reply(_) | TimerId.Rekey(_, _) | TimerId.Idle => IO.pure(0L)

private[server] object TimerService:

  def create(
    queue: Queue[IO, RuntimeCommand],
    stateRef: Ref[IO, AppState],
    clock: Clock
  ): IO[TimerService] =
    for
      fibers <- Ref.of[IO, Map[TimerId, (Long, FiberIO[Unit])]](Map.empty)
      tokens <- Ref.of[IO, Long](0L)
    yield new TimerService(queue, stateRef, clock, fibers, tokens)

final private[server] class RelayAppTransport(
  relay: RelayClient,
  queue: Queue[IO, RuntimeCommand],
  notifications: Queue[IO, Notification],
  clock: Clock,
  timers: TimerService
) extends AppTransport:

  def sendWire(toDevice: Ed25519Public, messageId: Long, generation: Long, wire: WireFrame): IO[Unit] =
    sendWireResult(toDevice, messageId, generation, wire).flatMap(feedback)

  def sendWireResult(toDevice: Ed25519Public, messageId: Long, generation: Long, wire: WireFrame): IO[AppEvent] =
    relay.send(toDevice, RelayPayload.Wire(wire.bytes)).attempt.map {
      case Right(_) => AppEvent.SendSucceeded(messageId, generation)
      case Left(_)  => AppEvent.SendFailed(messageId, generation, clock.millis())
    }

  def sendAck(sessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame): IO[Unit] =
    relay.send(toDevice, RelayPayload.Wire(frame.bytes))

  def sendGap(sessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame): IO[Unit] =
    relay.send(toDevice, RelayPayload.Wire(frame.bytes))

  def sendReply(invitationId: ByteVector, toDevice: Ed25519Public, text: String): IO[Unit] =
    relay.send(toDevice, RelayPayload.Reply(text))

  def sendReplyResult(invitationId: ByteVector, toDevice: Ed25519Public, text: String): IO[AppEvent] =
    relay.send(toDevice, RelayPayload.Reply(text)).attempt.map {
      case Right(_) => AppEvent.ReplySent(invitationId)
      case Left(_)  => AppEvent.ReplySendFailed(invitationId, clock.millis())
    }

  def sendRekeyRequest(sessionId: SessionId, toDevice: Ed25519Public, request: RekeyRequest): IO[Unit] =
    relay.send(toDevice, RelayPayload.RekeyRequest(request.canonical))

  def sendRekeyAccept(sessionId: SessionId, toDevice: Ed25519Public, accept: RekeyAccept): IO[Unit] =
    relay.send(toDevice, RelayPayload.RekeyAccept(accept.canonical))

  def sendRekeyConfirm(newSessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame): IO[Unit] =
    relay.send(toDevice, RelayPayload.Wire(frame.bytes))

  def sendRekeyConfirmAck(newSessionId: SessionId, toDevice: Ed25519Public, frame: WireFrame): IO[Unit] =
    relay.send(toDevice, RelayPayload.Wire(frame.bytes))

  def startTimer(timer: TimerId, deadlineMs: Long): IO[Unit] =
    timers.start(timer, deadlineMs)

  def cancelTimer(timer: TimerId): IO[Unit] =
    timers.cancel(timer)

  def notify(notification: Notification): IO[Unit] =
    notifications.tryOffer(notification).void

  private def feedback(event: AppEvent): IO[Unit] =
    queue.tryOffer(RuntimeCommand.App(event)).void
