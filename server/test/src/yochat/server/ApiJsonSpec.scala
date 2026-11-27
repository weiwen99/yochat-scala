package yochat.server

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, SessionId}
import yochat.core.app.{Conversation, LockState, Notification}
import yochat.core.s2.{LogicalKey, OrderDependency, OrderStamp}
import yochat.store.{MessageRecord, MessageState}

class ApiJsonSpec extends FunSuite:

  test("only applied peer messages are visible; buffered future bodies stay hidden") {
    val local       = ExecutorTestSupport.edPair()
    val peer        = ExecutorTestSupport.edPair()
    val conversation = Conversation(
      id = ByteVector.fill(32)(0x44.toByte),
      peerUserPub = peer.publicKey,
      peerDevicePub = peer.publicKey,
      nextSeq = Map(peer.publicKey -> 2L),
      seen = Set.empty,
      buffered = Map.empty
    )
    val sessionId   = SessionId
      .from(ByteVector.fill(32)(0x33.toByte))
      .fold(error => fail(error.toString), identity)
    def record(author: Ed25519Public, seq: Long): MessageRecord =
      MessageRecord(
        id = seq,
        conversationId = conversation.id,
        sessionId = sessionId,
        authorPub = author,
        devicePub = author,
        seq = seq,
        body = ByteVector.empty,
        wire = ByteVector.empty,
        state = MessageState.Received,
        createdAt = 0L,
        attempts = 0
      )

    assert(ApiJson.visible(record(local.publicKey, 1L), conversation, local.publicKey.bytes))
    assert(ApiJson.visible(record(peer.publicKey, 1L), conversation, local.publicKey.bytes))
    assert(!ApiJson.visible(record(peer.publicKey, 2L), conversation, local.publicKey.bytes))
    assertEquals(ApiJson.ordered(List(record(peer.publicKey, 2L), record(peer.publicKey, 1L))).map(_.seq), List(1L, 2L))
  }

  test("last notification is visible only while unlocked") {
    ExecutorTestSupport.withStore { store =>
      val fixture = ExecutorTestSupport.fixture()
      val state   = ExecutorTestSupport.appState(fixture)
      val config  = ServiceConfig(0, java.nio.file.Path.of("."), "127.0.0.1", 1, 60_000L)
      val visible = ApiJson
        .state(config, store, state, online = true, None, Some(Notification.RekeyFailed))
        .unsafeRunSync()
        .fold(error => fail(error.code), identity)
      assertEquals(visible("notification").str, "rekey-failed")
      val hidden = ApiJson
        .state(config, store, state.copy(lock = LockState.Locked(0L)), online = true, None, Some(Notification.RekeyFailed))
        .unsafeRunSync()
        .fold(error => fail(error.code), identity)
      assert(hidden("notification").isNull)
      IO.unit
    }
  }

  test("search admits only what the conversation view can display") {
    val local        = ExecutorTestSupport.edPair()
    val peer         = ExecutorTestSupport.edPair()
    val conversation = Conversation(
      id = ByteVector.fill(32)(0x44.toByte),
      peerUserPub = peer.publicKey,
      peerDevicePub = peer.publicKey,
      nextSeq = Map(peer.publicKey -> 2L),
      seen = Set.empty,
      buffered = Map.empty
    )
    val sessionId    = SessionId.from(ByteVector.fill(32)(0x33.toByte)).fold(error => fail(error.toString), identity)
    def record(author: Ed25519Public, seq: Long): MessageRecord =
      MessageRecord(seq, conversation.id, sessionId, author, author, seq, ByteVector.empty, ByteVector.empty, MessageState.Received, 0L, 0)
    val own          = record(local.publicKey, 5L)
    val applied      = record(peer.publicKey, 1L)
    val other        = ExecutorTestSupport.edPair().publicKey
    val key          = LogicalKey(conversation.id, peer.publicKey, 1L)
    val blockedDeps  = Map(key -> OrderStamp(1L, Vector(OrderDependency(other, 1L))))
    val satisfied    = blockedDeps + (LogicalKey(conversation.id, other, 1L) -> OrderStamp(1L, Vector.empty))

    assert(ApiJson.searchable(own, Some(conversation), Map.empty, local.publicKey.bytes))
    assert(ApiJson.searchable(applied, Some(conversation), Map.empty, local.publicKey.bytes))
    assert(!ApiJson.searchable(applied, Some(conversation), blockedDeps, local.publicKey.bytes), "missing GBNV dep")
    assert(ApiJson.searchable(applied, Some(conversation), satisfied, local.publicKey.bytes))
    assert(!ApiJson.searchable(own, None, Map.empty, local.publicKey.bytes), "a deleted relationship's history is not searchable")

  }
