package yochat.server

import java.nio.file.Files

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scodec.bits.ByteVector

import munit.FunSuite

import yochat.store.{CompanionMessageRecord, KeySource, MasterKeyProvider, MessageState, Store, StoreConfig}

final class CompanionViewSpec extends FunSuite:

  test("companion view reads contacts, conversation and sealed projection without a peer session") {
    val directory = Files.createTempDirectory("yochat-companion-view-")
    val fixture   = ExecutorTestSupport.fixture()
    val user      = fixture.identity.user.publicKey
    try
      Store
        .openCompanion(
          StoreConfig.default(directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))),
          user
        )
        .use {
          case Left(error)  => IO.raiseError(new AssertionError(error.code))
          case Right(store) =>
            for
              _        <- store.insertContact(fixture.peerUser, ExecutorTestSupport.contactData(), 0L)
              _        <- store.createConversation(fixture.conversationId, fixture.peerUser, 0L)
              _        <- store.upsertCompanionMessage(
                            CompanionMessageRecord(
                              sourceId = 1L,
                              conversationId = fixture.conversationId,
                              authorPub = fixture.peerUser,
                              devicePub = fixture.peerDevice,
                              seq = 1L,
                              body = ByteVector.view("只读消息".getBytes("UTF-8")),
                              state = MessageState.Received,
                              createdAt = 1_000L,
                              syncSeq = 1L
                            )
                          )
              snapshot <- CompanionView.load(store)
              state    <- snapshot match
                            case Left(error) => IO.raiseError(new AssertionError(error.code))
                            case Right(value) =>
                              CompanionView.stateJson(store, value, "127.0.0.1", 7654, online = true)
              messages <- CompanionView.messagesJson(store, fixture.conversationId, 10)
            yield
              assertEquals(ujson.read(state).obj("unlocked").bool, true)
              assertEquals(ujson.read(state).obj("contacts").arr.size, 1)
              assertEquals(ujson.read(state).obj("conversations").arr.size, 1)
              assertEquals(ujson.read(state).obj("sessions").arr.size, 0)
              val body = messages.fold(error => fail(error.code), identity)
              assertEquals(ujson.read(body).arr.size, 1)
              assertEquals(ujson.read(body)(0).obj("body").str, "只读消息")
        }
        .unsafeRunSync()
    finally ExecutorTestSupport.deleteTree(directory)
  }
