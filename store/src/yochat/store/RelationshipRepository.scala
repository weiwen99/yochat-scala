package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, Invitation}

/** Ends a relationship in one transaction. The contact row becomes a tombstone; history is either kept (sessions, messages and the conversation
  * stay readable) or removed together with the peer's sessions.
  */
private[store] object RelationshipRepository:

  private val PendingCode: Int = 1
  private val SentCode: Int    = 2
  private val FailedCode: Int  = 4

  def delete(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedVersion: Long,
    deleteHistory: Boolean,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        _       <- Sql.checkLock(expectedLockGeneration)
        _       <- checkCurrent(runtime, userPub, expectedVersion)
        _       <- deleteReceivedInvitations(runtime, userPub, expectedLockGeneration)
        _       <- ContactRepository.deleteProgram(runtime, userPub, expectedVersion, expectedLockGeneration)
        convIds <- conversationIds(userPub)
        _       <- if deleteHistory then SessionRepository.deleteForPeerProgram(runtime, userPub, expectedLockGeneration) else FC.unit
        _       <- convIds.foldLeft(FC.unit) { (acc, conversationId) =>
                     acc.flatMap(_ => if deleteHistory then deleteHistoryRows(conversationId) else markOutgoingFailed(conversationId))
                   }
        _       <- if deleteHistory then convIds.foldLeft(FC.unit)((acc, id) => acc.flatMap(_ => deleteConversation(id))) else FC.unit
      yield ()
    }

  private def checkCurrent(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedVersion: Long
  ): ConnectionIO[Unit] =
    ContactRepository.loadOptionProgram(runtime, userPub).flatMap {
      case Some(record) if record.isActive && record.version == expectedVersion => FC.unit
      case Some(record) if record.isActive                                      => Sql.abort(StorageError.StaleGeneration)
      case _                                                                    => Sql.abort(StorageError.NotFound("contact"))
    }

  private def conversationIds(userPub: Ed25519Public): ConnectionIO[Vector[ByteVector]] =
    sql"SELECT id FROM conversations WHERE peer_user_pub = ${userPub.bytes.toArray} ORDER BY id"
      .query[Array[Byte]]
      .to[List]
      .map(_.map(ByteVector.view).toVector)

  /** A pending or in-flight message cannot be delivered after the relationship ends; mark it `Failed` so a restart does not look for a session
    * that no longer backs the conversation.
    */
  private def markOutgoingFailed(conversationId: ByteVector): ConnectionIO[Unit] =
    sql"""UPDATE messages SET state = $FailedCode
         |WHERE conversation_id = ${conversationId.toArray} AND state IN ($PendingCode, $SentCode)""".stripMargin.update.run.map(_ => ())

  private def deleteHistoryRows(conversationId: ByteVector): ConnectionIO[Unit] =
    for
      _ <- sql"DELETE FROM messages WHERE conversation_id = ${conversationId.toArray}".update.run
      _ <- sql"DELETE FROM message_order WHERE conversation_id = ${conversationId.toArray}".update.run
      _ <- sql"DELETE FROM delivery_receipts WHERE conversation_id = ${conversationId.toArray}".update.run
      _ <- sql"DELETE FROM companion_messages WHERE conversation_id = ${conversationId.toArray}".update.run
    yield ()

  private def deleteConversation(conversationId: ByteVector): ConnectionIO[Unit] =
    sql"DELETE FROM conversations WHERE id = ${conversationId.toArray}".update.run.map(_ => ())

  /** A received invitation from the deleted peer must not survive, or a late reply would recreate the relationship without the user pasting a
    * new invitation. Issued invitations carry our own user key, so they are untouched.
    */
  private def deleteReceivedInvitations(
    runtime: StoreRuntime,
    userPub: Ed25519Public,
    expectedLockGeneration: Long
  ): ConnectionIO[Unit] =
    sql"SELECT id FROM invitations ORDER BY id".query[Array[Byte]].to[List].flatMap { ids =>
      ids.foldLeft(FC.unit) { (acc, raw) =>
        acc.flatMap(_ =>
          InvitationRepository.loadOptionProgram(runtime, ByteVector.view(raw)).flatMap {
            case None        => FC.unit
            case Some(value) =>
              Invitation.fromCanonical(value.data.payload) match
                case Left(_)                                                   => FC.unit
                case Right(invitation) if invitation.fields.userPub == userPub =>
                  InvitationRepository.deleteProgram(runtime, ByteVector.view(raw), expectedLockGeneration)
                case Right(_)                                                  => FC.unit
          }
        )
      }
    }
