package yochat.core.app

import scodec.bits.ByteVector

import yochat.core.*

object AppTestSupport:

  final case class Peers(
    alice: LocalIdentity,
    bob: LocalIdentity,
    aliceState: AppState,
    bobState: AppState,
    invitation: Invitation,
    bobReply: ReplyResult,
    aliceReply: ReplyResult
  )

  private val ConversationBytes: ByteVector = ByteVector.fill(32)(0x44.toByte)

  def connected: Peers =
    val aliceUser     = TestKeys.ed25519()
    val aliceDevice   = TestKeys.ed25519()
    val bobUser       = TestKeys.ed25519()
    val bobDevice     = TestKeys.ed25519()
    val aliceEph      = xpair(1)
    val aliceRatchet  = xpair(2)
    val bobEph        = xpair(3)
    val bobRatchet0   = xpair(4)
    val bobRatchet1   = xpair(5)
    val invitation    = Invitation
      .create(aliceUser, aliceDevice, aliceEph.publicKey, aliceRatchet.publicKey, "test", "@alice", 100_000L, "invite")
      .fold(error => throw new IllegalStateException(error.toString), identity)
    val bobReply      = Reply
      .create(invitation, bobUser, bobDevice, bobEph, bobRatchet0, bobRatchet1, "test", "@bob", "reply", TestKeys.nonce(1), 1_000L)
      .fold(error => throw new IllegalStateException(error.toString), identity)
    val aliceReply    = Reply
      .accept(invitation, bobReply.text, aliceEph.privateKey, aliceRatchet, 1_000L)
      .fold(error => throw new IllegalStateException(error.toString), identity)
    val aliceIdentity = LocalIdentity(aliceUser, aliceDevice)
    val bobIdentity   = LocalIdentity(bobUser, bobDevice)
    val aliceState    = sessionState(
      aliceIdentity,
      bobUser.publicKey,
      bobDevice.publicKey,
      aliceReply.state.copy(safetyVerified = true),
      conversation(bobUser.publicKey, bobDevice.publicKey)
    )
    val bobState      = sessionState(
      bobIdentity,
      aliceUser.publicKey,
      aliceDevice.publicKey,
      bobReply.state.copy(safetyVerified = true),
      conversation(aliceUser.publicKey, aliceDevice.publicKey)
    )
    Peers(aliceIdentity, bobIdentity, aliceState, bobState, invitation, bobReply, aliceReply)

  def sessionState(
    identity: LocalIdentity,
    peerUser: Ed25519Public,
    peerDevice: Ed25519Public,
    ratchet: RatchetState,
    conversation: Conversation
  ): AppState =
    val contact = AppContact(peerUser, peerDevice, "test", "@peer", Some("@peer"), safetyVerified = true, ContactConfirmation.Confirmed)
    val session = AppSession(ratchet, conversation.id, 1L)
    AppState.empty.copy(
      identity = Some(identity),
      lock = LockState.Unlocked(0L),
      idleDeadline = Some(1_000_000L),
      contacts = Map(peerUser -> contact),
      sessions = Map(ratchet.sessionId -> session),
      conversations = Map(conversation.id -> conversation),
      connection = ConnectionState.Online
    )

  def conversation(peerUser: Ed25519Public, peerDevice: Ed25519Public): Conversation =
    Conversation(
      id = ConversationBytes,
      peerUserPub = peerUser,
      peerDevicePub = peerDevice,
      nextSeq = Map.empty,
      seen = Set.empty,
      buffered = Map.empty,
      applied = Vector.empty
    )

  def xpair(seed: Int): X25519KeyPair =
    val bytes = ByteVector.fill(32)(seed.toByte)
    val value = X25519Private.from(bytes).fold(error => throw new IllegalStateException(error.toString), identity)
    X25519KeyPair.fromSeed(value).fold(error => throw new IllegalStateException(error.toString), identity)

  def sendFrames(identity: LocalIdentity, state: RatchetState, count: Int): (RatchetState, Vector[WireFrame]) =
    (1 to count).toVector.foldLeft((state, Vector.empty[WireFrame])) { case ((current, frames), index) =>
      val nonce = TestKeys.nonce(index)
      Ratchet
        .encryptUser(
          current,
          identity.device.publicKey,
          identity.device.privateKey,
          index.toLong,
          nonce,
          ByteVector.view(s"body-$index".getBytes("UTF-8"))
        )
        .fold(error => throw new IllegalStateException(error.toString), Predef.identity) match
        case (next, frame) => (next, frames :+ frame)
    }
