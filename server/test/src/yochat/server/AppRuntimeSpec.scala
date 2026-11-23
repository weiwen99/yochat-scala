package yochat.server

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, SessionPhase}
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
