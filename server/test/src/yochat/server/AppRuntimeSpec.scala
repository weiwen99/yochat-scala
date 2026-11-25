package yochat.server

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, RekeyId, RekeyPhase, RekeyRole, RekeyState, SessionId, SessionPhase}
import yochat.core.app.*
import yochat.store.*
import yochat.transport.{RelayClient, RelayServer}

class AppRuntimeSpec extends FunSuite:

  override def munitTimeout: Duration = 90.seconds

  private val masterA = master(0x51)
  private val masterB = master(0x52)

  test("two runtimes exchange an invitation, reply, message, ACK, reconnect, and restore") {
    val directoryA = Files.createTempDirectory("yochat-runtime-a-")
    val directoryB = Files.createTempDirectory("yochat-runtime-b-")
    try
      val program = for
        server  <- RelayServer.start(0)
        storeA  <- openStore(directoryA, masterA)
        storeB  <- openStore(directoryB, masterB)
        relayA  <- RelayClient.connect("127.0.0.1", server.port, storeA.identity.devicePublic)
        relayB  <- RelayClient.connect("127.0.0.1", server.port, storeB.identity.devicePublic)
        runtimeA <- AppRuntime.start(storeA, relayA, queueCapacity = 256)
        runtimeB <- AppRuntime.start(storeB, relayB, queueCapacity = 256)
      yield (server, storeA, storeB, runtimeA, runtimeB)
      program.use { case (server, storeA, storeB, runtimeA, runtimeB) =>
        val alice = storeA.identity.userPublic
        val bob   = storeB.identity.userPublic
        for
          _          <- runtimeA.awaitState(_.connection == ConnectionState.Online, 5.seconds)
          _          <- runtimeB.awaitState(_.connection == ConnectionState.Online, 5.seconds)
          invitation <- runtimeA.createInvitation("test", "@alice", Some("@bob"), 60_000L, "invite")
          _          <- runtimeB.receiveInvitation(invitation.text, Some("@alice"))
          _          <- runtimeB.awaitState(_.invitations.contains(invitation.fields.ephPub.bytes), 5.seconds)
          stored     <- awaitInvitation(storeB, invitation.fields.ephPub.bytes, 5.seconds)
          _           = assertEquals(stored.data.ephemeralPrivate, None)
          _           = assertEquals(stored.data.ratchetPrivate, None)
          _           = assertEquals(stored.data.payload, invitation.canonical)
          _          <- runtimeB.acceptInvitation(invitation.fields.ephPub.bytes, "test", "@bob", "reply")
          _          <- requireAwait(runtimeA, state => state.contacts.contains(bob) && state.sessions.nonEmpty, 10.seconds, "alice reply")
          _          <- requireAwait(runtimeB, state => state.contacts.contains(alice) && state.sessions.nonEmpty, 10.seconds, "bob reply")
          _          <- runtimeA.submit(AppEvent.SafetyVerified(bob, 2_000L))
          _          <- runtimeB.submit(AppEvent.SafetyVerified(alice, 2_000L))
          _          <- runtimeA.awaitState(state => state.contacts.get(bob).exists(_.safetyVerified), 5.seconds)
          _          <- runtimeB.awaitState(state => state.contacts.get(alice).exists(_.safetyVerified), 5.seconds)
          stateA     <- runtimeA.state
          conversationId <- stateA.conversations.keys.headOption match
                              case Some(value) => IO.pure(value)
                              case None        => IO.raiseError(new AssertionError("missing conversation"))
          _          <- runtimeA.sendMessage(conversationId, "hello")
          _          <- runtimeA.awaitState(_.outgoing.isEmpty, 10.seconds)
          _          <- runtimeB.awaitState(
                          state => state.conversations.values.exists(_.nextSeq.get(alice).contains(2L)),
                          10.seconds
                        )
          delivered  <- awaitMessageState(
                          storeA,
                          conversationId,
                          alice,
                          storeA.identity.devicePublic,
                          1L,
                          MessageState.Delivered,
                          5.seconds
                        )
          stateAfterA <- runtimeA.state
          stateAfterB <- runtimeB.state
          fatalA      <- runtimeA.fatalError
          fatalB      <- runtimeB.fatalError
          messagesA   <- storeA.listMessages(conversationId, alice, 100)
          messagesB   <- storeB.listMessages(conversationId, alice, 100)
          _           = assert(
                          delivered,
                          s"delivered=false outgoingA=${stateAfterA.outgoing.values.toVector} fatalA=$fatalA fatalB=$fatalB " +
                            s"messagesA=${messagesA.map(_.map(_.state))} messagesB=${messagesB.map(_.map(_.state))} " +
                            s"sessionsA=${stateAfterA.sessions.values.map(_.state.phase)} sessionsB=${stateAfterB.sessions.values.map(_.state.phase)}"
                        )
          restored   <- AppStateRestore.load(storeA)
          restoredState <- IO.fromEither(restored.left.map(error => new AssertionError(error.code)))
          _           = assert(restoredState.contacts.contains(bob))
          _           = assert(restoredState.sessions.nonEmpty)
          _           = assert(restoredState.conversations.contains(conversationId))
          _           = assert(restoredState.outgoing.isEmpty)
          _           = assertEquals(restoredState.identity.map(_.user.publicKey), Some(alice))
          currentA    <- runtimeA.state
          sessionId   <- currentA.sessions.keys.headOption match
                            case Some(value) => IO.pure(value)
                            case None        => IO.raiseError(new AssertionError("missing session for rekey"))
          _           <- runtimeA.startRekey(sessionId)
          responder   <- runtimeB.awaitState(state => state.sessions.values.exists(_.state.phase == SessionPhase.ReadOnly), 10.seconds)
          _           <- requireState(runtimeB, responder, "bob rekey")
          rekeyedA    <- runtimeA.awaitState(
                           state =>
                             !state.sessions.contains(sessionId) &&
                               state.sessions.size == 1 &&
                               state.sessions.values.forall(_.state.phase == SessionPhase.Active),
                           10.seconds
                         )
          _           <- requireState(runtimeA, rekeyedA, "alice rekey")
          _           <- runtimeA.sendMessage(conversationId, "after-rekey")
          delivered2  <- awaitMessageState(
                           storeA,
                           conversationId,
                           alice,
                           storeA.identity.devicePublic,
                           2L,
                           MessageState.Delivered,
                           5.seconds
                         )
          _           <- if delivered2 then IO.unit else IO.raiseError(new AssertionError("after-rekey message not delivered"))
          retiredB    <- runtimeB.awaitState(
                           state => state.sessions.size == 1 && state.sessions.values.forall(_.state.phase == SessionPhase.Active),
                           10.seconds
                         )
          _           <- requireState(runtimeB, retiredB, "bob rekey retirement")
          restoredAfterRekey <- AppStateRestore.load(storeA)
          rekeyRestore <- IO.fromEither(restoredAfterRekey.left.map(error => new AssertionError(error.code)))
          _            = assert(rekeyRestore.sessions.values.forall(_.nextSeq == 3L))
          stateB      <- runtimeB.state
          conversationB <- stateB.conversations.keys.headOption match
                             case Some(value) => IO.pure(value)
                             case None        => IO.raiseError(new AssertionError("missing bob conversation"))
          port        <- IO.pure(server.port)
          _           <- server.close
          offlineA    <- runtimeA.awaitState(_.connection == ConnectionState.Offline, 5.seconds)
          offlineB    <- runtimeB.awaitState(_.connection == ConnectionState.Offline, 5.seconds)
          _           <- requireState(runtimeA, offlineA, "alice disconnect")
          _           <- requireState(runtimeB, offlineB, "bob disconnect")
          _           <- RelayServer.start(port).use { _ =>
                           for
                             onlineA <- runtimeA.awaitState(_.connection == ConnectionState.Online, 10.seconds)
                             onlineB <- runtimeB.awaitState(_.connection == ConnectionState.Online, 10.seconds)
                             _       <- requireState(runtimeA, onlineA, "alice reconnect")
                             _       <- requireState(runtimeB, onlineB, "bob reconnect")
                             _       <- runtimeA.sendMessage(conversationId, "after-reconnect")
                             delivered3 <- awaitMessageState(
                                             storeA,
                                             conversationId,
                                             alice,
                                             storeA.identity.devicePublic,
                                             3L,
                                             MessageState.Delivered,
                                             5.seconds
                                           )
                             _       <- if delivered3 then IO.unit else IO.raiseError(new AssertionError("post-reconnect delivery missing"))
                             received3 <- awaitMessageState(
                                            storeB,
                                            conversationB,
                                            alice,
                                            storeA.identity.devicePublic,
                                            3L,
                                            MessageState.Received,
                                            5.seconds
                                          )
                             _       <- if received3 then IO.unit else IO.raiseError(new AssertionError("post-reconnect receive missing"))
                           yield ()
                         }
        yield ()
      }.unsafeRunSync()
    finally
      deleteTree(directoryA)
      deleteTree(directoryB)
  }


  test("startup retires an eligible initiator predecessor without peer traffic") {
    val directory = Files.createTempDirectory("yochat-runtime-recover-")
    try
      val fixture = ExecutorTestSupport.fixture()
      val oldId   = fixture.sessionId
      val newId   = SessionId.from(ByteVector.fill(32)(0x55.toByte)).fold(error => fail(error.toString), identity)
      val rekey   = RekeyState(
        RekeyRole.Initiator,
        RekeyId.from(ByteVector.fill(RekeyId.Length)(7.toByte)).fold(error => fail(error.toString), identity),
        1L,
        RekeyPhase.Accepted,
        None,
        Some(newId),
        None,
        1_000L,
        ackReceived = true
      )
      val oldState   = fixture.state.copy(phase = SessionPhase.ReadOnly, rekey = Some(rekey))
      val newState   = fixture.state.copy(sessionId = newId, phase = SessionPhase.Active, rekey = None)
      val program    = for
        server <- RelayServer.start(0)
        store  <- openStore(directory, masterA)
        _      <- Resource.eval(store.insertContact(fixture.peerUser, ExecutorTestSupport.contactData(), 0L).flatMap(requireStore))
        _      <- Resource.eval(store.createConversation(fixture.conversationId, fixture.peerUser, 0L).flatMap(requireStore))
        _      <- Resource.eval(store.insertSession(oldState, 0L).flatMap(requireStore))
        _      <- Resource.eval(store.insertSession(newState, 0L).flatMap(requireStore))
        relay  <- RelayClient.connect("127.0.0.1", server.port, store.identity.devicePublic)
        runtime <- AppRuntime.start(store, relay, queueCapacity = 64)
      yield (server, store, runtime)
      program.use { case (_, store, runtime) =>
        for
          retired <- runtime.awaitState(state => !state.sessions.contains(oldId), 5.seconds)
          _       <- if retired then IO.unit else IO.raiseError(new AssertionError("predecessor was not retired"))
          state   <- runtime.state
          _       <- if state.sessions.contains(newId) then IO.unit else IO.raiseError(new AssertionError("successor missing"))
          loaded  <- store.loadSession(oldId)
          _       <- loaded match
                       case Left(yochat.store.StorageError.NotFound(_)) => IO.unit
                       case other                                        => IO.raiseError(new AssertionError(s"old row still present: $other"))
        yield ()
      }.unsafeRunSync()
    finally deleteTree(directory)
  }


  test("runtime batch migrates, skips, and retires in one writer pass") {
    val directory = Files.createTempDirectory("yochat-runtime-mixed-")
    try
      val fixture = ExecutorTestSupport.fixture()
      val oldId   = fixture.sessionId
      val newId   = SessionId.from(ByteVector.fill(32)(0x57.toByte)).fold(error => fail(error.toString), identity)
      val rekey   = RekeyState(
        RekeyRole.Initiator,
        RekeyId.from(ByteVector.fill(RekeyId.Length)(9.toByte)).fold(error => fail(error.toString), identity),
        1L,
        RekeyPhase.Accepted,
        None,
        Some(newId),
        None,
        1_000L,
        ackReceived = true
      )
      val oldState = fixture.state.copy(phase = SessionPhase.ReadOnly, rekey = Some(rekey))
      val newState = fixture.state.copy(sessionId = newId, phase = SessionPhase.Active, rekey = None)
      val body1    = ExecutorTestSupport.body("one")
      val body2    = ExecutorTestSupport.body("two")
      val body3    = ExecutorTestSupport.body("three")
      def wire(seq: Long) =
        yochat.core.WireFrame
          .parse(ExecutorTestSupport.wire(fixture, seq, fixture.identity.device.publicKey))
          .fold(error => fail(error.toString), identity)
      val outgoing = Vector(1L -> body1, 2L -> body2, 3L -> body3).map { case (seq, body) =>
        seq -> OutgoingMessage(
          id = seq,
          conversationId = fixture.conversationId,
          sessionId = oldId,
          authorPub = fixture.identity.user.publicKey,
          devicePub = fixture.identity.device.publicKey,
          peerDevicePub = fixture.peerDevice,
          seq = seq,
          body = body,
          wire = wire(seq),
          generation = 0L,
          state = OutgoingState.Queued,
          attempts = 0
        )
      }.toMap
      val contact = AppContact(
        fixture.peerUser,
        fixture.peerDevice,
        "test",
        "@peer",
        Some("@peer"),
        safetyVerified = true,
        yochat.core.app.ContactConfirmation.Confirmed
      )
      val app = AppState.empty.copy(
        identity = Some(fixture.identity),
        lock = LockState.Unlocked(0L),
        idleDeadline = Some(1_000_000L),
        contacts = Map(fixture.peerUser -> contact),
        sessions = Map(
          oldId -> AppSession(oldState, fixture.conversationId, 4L),
          newId -> AppSession(newState, fixture.conversationId, 4L)
        ),
        conversations = Map(
          fixture.conversationId -> Conversation(fixture.conversationId, fixture.peerUser, fixture.peerDevice, Map.empty, Set.empty, Map.empty)
        ),
        outgoing = outgoing,
        connection = ConnectionState.Online
      )
      val program = for
        server <- RelayServer.start(0)
        store  <- openStore(directory, masterA)
        _      <- Resource.eval(store.insertContact(fixture.peerUser, ExecutorTestSupport.contactData(), 0L).flatMap(requireStore))
        _      <- Resource.eval(store.createConversation(fixture.conversationId, fixture.peerUser, 0L).flatMap(requireStore))
        _      <- Resource.eval(store.insertSession(oldState, 0L).flatMap(requireStore))
        _      <- Resource.eval(store.insertSession(newState, 0L).flatMap(requireStore))
        first  <- Resource.eval(
                    store
                      .appendOutbound(oldState, 1L, 0L, fixture.conversationId, fixture.identity.user.publicKey,
                        fixture.identity.device.publicKey, 1L, body1, wire(1).bytes, yochat.store.MessageState.Pending)
                      .flatMap(requireStore)
                  )
        second <- Resource.eval(
                    store
                      .appendOutbound(oldState, first.session.version, 0L, fixture.conversationId, fixture.identity.user.publicKey,
                        fixture.identity.device.publicKey, 2L, body2, wire(2).bytes, yochat.store.MessageState.Delivered)
                      .flatMap(requireStore)
                  )
        _      <- Resource.eval(
                    store
                      .appendOutbound(oldState, second.session.version, 0L, fixture.conversationId, fixture.identity.user.publicKey,
                        fixture.identity.device.publicKey, 3L, body3, wire(3).bytes, yochat.store.MessageState.Pending)
                      .flatMap(requireStore)
                  )
        relay   <- RelayClient.connect("127.0.0.1", server.port, store.identity.devicePublic)
        runtime <- AppRuntime.startWithState(store, relay, app)
      yield (server, store, runtime)
      program.use { case (_, store, runtime) =>
        for
          done  <- runtime.awaitState(state => !state.sessions.contains(oldId) && !state.outgoing.contains(2L), 5.seconds)
          state <- runtime.state
          fatal <- runtime.fatalError
          _     <- if done then IO.unit else IO.raiseError(new AssertionError(s"batch did not drain: sessions=${state.sessions.keys.map(_.bytes.toHex).toVector} outgoing=${state.outgoing.values.toVector} fatal=$fatal"))
          _     <- if state.outgoing.get(1L).exists(_.sessionId == newId) && state.outgoing.get(3L).exists(_.sessionId == newId) then IO.unit
                    else IO.raiseError(new AssertionError(s"migrated rows not on successor: ${state.outgoing.values.toVector}"))
          one   <- store.loadMessage(fixture.conversationId, newId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 1L)
          three <- store.loadMessage(fixture.conversationId, newId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 3L)
          two   <- store.loadMessage(fixture.conversationId, oldId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 2L)
          _     <- one match
                     case Right(_) => IO.unit
                     case Left(error) => IO.raiseError(new AssertionError(s"migrated row 1 missing: ${error.code}"))
          _     <- three match
                     case Right(_) => IO.unit
                     case Left(error) => IO.raiseError(new AssertionError(s"migrated row 3 missing: ${error.code}"))
          _     <- two match
                     case Right(record) if record.state == yochat.store.MessageState.Delivered => IO.unit
                     case other => IO.raiseError(new AssertionError(s"skipped row changed: $other"))
        yield ()
      }.unsafeRunSync()
    finally deleteTree(directory)
  }

  test("runtime batch stops on a store failure and does not attempt later rows") {
    val directory = Files.createTempDirectory("yochat-runtime-fail-")
    try
      val fixture = ExecutorTestSupport.fixture()
      val oldId   = fixture.sessionId
      val newId   = SessionId.from(ByteVector.fill(32)(0x58.toByte)).fold(error => fail(error.toString), identity)
      val rekey   = RekeyState(
        RekeyRole.Initiator,
        RekeyId.from(ByteVector.fill(RekeyId.Length)(10.toByte)).fold(error => fail(error.toString), identity),
        1L,
        RekeyPhase.Accepted,
        None,
        Some(newId),
        None,
        1_000L,
        ackReceived = true
      )
      val oldState = fixture.state.copy(phase = SessionPhase.ReadOnly, rekey = Some(rekey))
      val newState = fixture.state.copy(sessionId = newId, phase = SessionPhase.Active, rekey = None)
      def msg(id: Long, body: ByteVector): OutgoingMessage =
        val frame = yochat.core.WireFrame
          .parse(ExecutorTestSupport.wire(fixture, id, fixture.identity.device.publicKey))
          .fold(error => fail(error.toString), identity)
        OutgoingMessage(id, fixture.conversationId, oldId, fixture.identity.user.publicKey, fixture.identity.device.publicKey,
          fixture.peerDevice, id, body, frame, 0L, OutgoingState.Queued, 0)
      val outgoing = Map(1L -> msg(1L, ExecutorTestSupport.body("ok")), 2L -> msg(2L, ExecutorTestSupport.body("missing")), 3L -> msg(3L, ExecutorTestSupport.body("later")))
      val contact = AppContact(fixture.peerUser, fixture.peerDevice, "test", "@peer", Some("@peer"), safetyVerified = true, yochat.core.app.ContactConfirmation.Confirmed)
      val app = AppState.empty.copy(
        identity = Some(fixture.identity),
        lock = LockState.Unlocked(0L),
        contacts = Map(fixture.peerUser -> contact),
        sessions = Map(
          oldId -> AppSession(oldState, fixture.conversationId, 4L),
          newId -> AppSession(newState, fixture.conversationId, 4L)
        ),
        conversations = Map(fixture.conversationId -> Conversation(fixture.conversationId, fixture.peerUser, fixture.peerDevice, Map.empty, Set.empty, Map.empty)),
        outgoing = outgoing,
        connection = ConnectionState.Online
      )
      val program = for
        server <- RelayServer.start(0)
        store  <- openStore(directory, masterA)
        _      <- Resource.eval(store.insertContact(fixture.peerUser, ExecutorTestSupport.contactData(), 0L).flatMap(requireStore))
        _      <- Resource.eval(store.createConversation(fixture.conversationId, fixture.peerUser, 0L).flatMap(requireStore))
        _      <- Resource.eval(store.insertSession(oldState, 0L).flatMap(requireStore))
        _      <- Resource.eval(store.insertSession(newState, 0L).flatMap(requireStore))
        first  <- Resource.eval(
                    store
                      .appendOutbound(oldState, 1L, 0L, fixture.conversationId, fixture.identity.user.publicKey,
                        fixture.identity.device.publicKey, 1L, ExecutorTestSupport.body("ok"),
                        yochat.core.WireFrame.parse(ExecutorTestSupport.wire(fixture, 1L, fixture.identity.device.publicKey)).fold(error => fail(error.toString), identity).bytes,
                        yochat.store.MessageState.Pending)
                      .flatMap(requireStore)
                  )
        _      <- Resource.eval(
                    store
                      .appendOutbound(oldState, first.session.version, 0L, fixture.conversationId, fixture.identity.user.publicKey,
                        fixture.identity.device.publicKey, 3L, ExecutorTestSupport.body("later"),
                        yochat.core.WireFrame.parse(ExecutorTestSupport.wire(fixture, 3L, fixture.identity.device.publicKey)).fold(error => fail(error.toString), identity).bytes,
                        yochat.store.MessageState.Pending)
                      .flatMap(requireStore)
                  )
        relay   <- RelayClient.connect("127.0.0.1", server.port, store.identity.devicePublic)
        runtime <- AppRuntime.startWithState(store, relay, app)
      yield (server, store, runtime)
      program.use { case (_, store, runtime) =>
        for
          fatal  <- awaitFatal(runtime, 5.seconds)
          _      <- if fatal.exists(_.code == "StoreFailed:NotFound") then IO.unit
                    else IO.raiseError(new AssertionError(s"unexpected fatal: $fatal"))
          later  <- store.loadMessage(fixture.conversationId, oldId, fixture.identity.user.publicKey, fixture.identity.device.publicKey, 3L)
          _      <- later match
                      case Right(record) if record.state == yochat.store.MessageState.Pending => IO.unit
                      case other => IO.raiseError(new AssertionError(s"later row was touched: $other"))
        yield ()
      }.unsafeRunSync()
    finally deleteTree(directory)
  }

  private def requireStore[A](value: Either[yochat.store.StorageError, A]): IO[A] =
    value match
      case Right(result) => IO.pure(result)
      case Left(error)   => IO.raiseError(new AssertionError(error.code))

  private def requireAwait(runtime: AppRuntime, predicate: AppState => Boolean, timeout: FiniteDuration, label: String): IO[Unit] =
    runtime.awaitState(predicate, timeout).flatMap {
      case true  => IO.unit
      case false =>
        for
          state <- runtime.state
          fatal <- runtime.fatalError
          _     <- IO.raiseError(
                     new AssertionError(
                       s"$label: contacts=${state.contacts.keys.map(_.bytes.toHex.take(8))} sessions=${state.sessions.size} " +
                         s"conversations=${state.conversations.size} invitations=${state.invitations.values.map(_.lifecycle)} fatal=$fatal"
                     )
                   )
        yield ()
    }

  private def awaitFatal(runtime: AppRuntime, timeout: FiniteDuration): IO[Option[AppExecutionError]] =
    def loop(remaining: Long): IO[Option[AppExecutionError]] =
      runtime.fatalError.flatMap {
        case value @ Some(_) => IO.pure(value)
        case None if remaining <= 0L => IO.pure(None)
        case None => IO.sleep(10.millis) *> loop(remaining - 10L)
      }
    loop(timeout.toMillis)

  private def requireState(runtime: AppRuntime, accepted: Boolean, label: String): IO[Unit] =
    if accepted then IO.unit
    else
      for
        current <- runtime.state
        fatal   <- runtime.fatalError
        _       <- IO.raiseError(new AssertionError(s"$label failed: sessions=${current.sessions.values} fatal=$fatal"))
      yield ()

  private def openStore(directory: Path, masterKey: MasterKey): Resource[IO, Store] =
    val config = StoreConfig.default(directory, KeySource.New(MasterKeyProvider.fixed(masterKey)))
    Store.open(config).flatMap {
      case Left(error)  => Resource.eval(IO.raiseError(new AssertionError(s"store open failed: ${error.code}")))
      case Right(store) => Resource.pure(store)
    }

  private def awaitInvitation(store: Store, id: ByteVector, timeout: FiniteDuration): IO[InvitationRecord] =
    def loop(remaining: Long): IO[InvitationRecord] =
      store.loadInvitation(id).flatMap {
        case Right(record) => IO.pure(record)
        case Left(_) if remaining > 0L => IO.sleep(10.millis) *> loop(remaining - 10L)
        case Left(error)               => IO.raiseError(new AssertionError(error.code))
      }
    loop(timeout.toMillis)

  private def awaitMessageState(
    store: Store,
    conversationId: ByteVector,
    author: Ed25519Public,
    device: Ed25519Public,
    seq: Long,
    expected: MessageState,
    timeout: FiniteDuration
  ): IO[Boolean] =
    def loop(remaining: Long): IO[Boolean] =
      store.listMessages(conversationId, author, 100).flatMap {
        case Right(messages) =>
          val found = messages.find(message => message.seq == seq && message.devicePub == device).exists(_.state == expected)
          if found then IO.pure(true)
          else if remaining <= 0L then IO.pure(false)
          else IO.sleep(10.millis) *> loop(remaining - 10L)
        case Left(error)     => IO.raiseError(new AssertionError(error.code))
      }
    loop(timeout.toMillis)

  private def master(byte: Int): MasterKey =
    MasterKey.from(ByteVector.fill(32)(byte.toByte)).fold(error => throw new IllegalStateException(error.toString), identity)

  private def deleteTree(directory: Path): Unit =
    ExecutorTestSupport.deleteTree(directory)
