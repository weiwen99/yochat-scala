package yochat.store

import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, SessionId}

/** One read transaction for restore. Message rows are loaded in keyset pages, and every stream is bounded. */
private[store] object SnapshotRepository:

  def load(runtime: StoreRuntime): ConnectionIO[StoreSnapshot] =
    for
      contactIds      <- ids(sql"SELECT user_pub FROM contacts ORDER BY user_pub".query[Array[Byte]].to[List])
      contacts        <- loadContacts(runtime, contactIds)
      invitationIds   <- ids(sql"SELECT id FROM invitations ORDER BY id".query[Array[Byte]].to[List])
      invitations     <- loadInvitations(runtime, invitationIds)
      sessionIds      <- ids(sql"SELECT id FROM sessions ORDER BY id".query[Array[Byte]].to[List])
      sessions        <- loadSessions(runtime, sessionIds)
      conversationIds <- ids(sql"SELECT id FROM conversations ORDER BY id".query[Array[Byte]].to[List])
      conversations   <- loadConversations(conversationIds)
      messages        <- conversations.foldLeft(FC.pure(Map.empty[ByteVector, List[MessageRecord]])) { (acc, conversation) =>
                           acc.flatMap(values =>
                             MessageRepository.listAllProgram(runtime, conversation.id).map(records => values.updated(conversation.id, records.toList))
                           )
                         }
    yield StoreSnapshot(contacts, invitations, sessions, conversations, messages)

  private def ids(values: ConnectionIO[List[Array[Byte]]]): ConnectionIO[List[ByteVector]] =
    values.map(_.map(ByteVector.view))

  private def loadContacts(runtime: StoreRuntime, ids: List[ByteVector]): ConnectionIO[List[ContactRecord]] =
    ids.foldLeft(FC.pure(List.empty[ContactRecord])) { (acc, raw) =>
      acc.flatMap(values =>
        Sql.eitherCore(Ed25519Public.from(raw)).flatMap { userPub =>
          ContactRepository.loadOptionProgram(runtime, userPub).flatMap {
            case Some(record) => FC.pure(values :+ record)
            case None         => Sql.abort(StorageError.NotFound("contact"))
          }
        }
      )
    }

  private def loadInvitations(runtime: StoreRuntime, ids: List[ByteVector]): ConnectionIO[List[InvitationRecord]] =
    ids.foldLeft(FC.pure(List.empty[InvitationRecord])) { (acc, id) =>
      acc.flatMap(values =>
        InvitationRepository.loadOptionProgram(runtime, id).flatMap {
          case Some(record) => FC.pure(values :+ record)
          case None         => Sql.abort(StorageError.NotFound("invitation"))
        }
      )
    }

  private def loadSessions(runtime: StoreRuntime, ids: List[ByteVector]): ConnectionIO[List[SessionRecord]] =
    ids.foldLeft(FC.pure(List.empty[SessionRecord])) { (acc, raw) =>
      acc.flatMap(values =>
        SessionId.from(raw).left.map(_ => StorageError.InvalidEnvelope) match
          case Left(error)  => Sql.abort(error)
          case Right(value) =>
            SessionRepository.loadOptionProgram(runtime, value).flatMap {
              case Some(record) => FC.pure(values :+ record)
              case None         => Sql.abort(StorageError.NotFound("session"))
            }
      )
    }

  private def loadConversations(ids: List[ByteVector]): ConnectionIO[List[ConversationRecord]] =
    ids.foldLeft(FC.pure(List.empty[ConversationRecord])) { (acc, id) =>
      acc.flatMap(values =>
        ConversationRepository.loadOptionProgram(id).flatMap {
          case Some(record) => FC.pure(values :+ record)
          case None         => Sql.abort(StorageError.NotFound("conversation"))
        }
      )
    }
