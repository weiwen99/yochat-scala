package yochat.store

import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC

/** Applies the durable half of a reply or rekey handshake in one SQLite transaction. */
private[store] object HandshakeRepository:

  def persist(runtime: StoreRuntime, write: HandshakeWrite, lock: Long, now: Long): ConnectionIO[Unit] =
    for
      _ <- Sql.checkLock(lock)
      _ <- write.contact match
             case None                     => FC.unit
             case Some((userPub, contact)) => ContactRepository.upsertProgram(runtime, userPub, contact, lock, now).map(_ => ())
      _ <- write.conversation match
             case None                             => FC.unit
             case Some((conversationId, peerUser)) =>
               ConversationRepository.ensureProgram(conversationId, peerUser, lock, now).map(_ => ())
      _ <- write.sessions.foldLeft(FC.unit) { (acc, session) =>
             acc.flatMap(_ => SessionRepository.writeProgram(runtime, session, lock, now).map(_ => ()))
           }
      _ <- write.retire.foldLeft(FC.unit) { case (acc, (sessionId, role, evidence)) =>
             acc.flatMap(_ => SessionRepository.retireProgram(runtime, sessionId, lock, role, evidence))
           }
      _ <- write.deletePendingSessions.foldLeft(FC.unit) { (acc, sessionId) =>
             acc.flatMap(_ => SessionRepository.deletePendingProgram(runtime, sessionId, lock))
           }
      _ <- write.invitation match
             case None                   => FC.unit
             case Some((id, invitation)) => InvitationRepository.upsertProgram(runtime, id, invitation, lock, now).map(_ => ())
      _ <- write.deleteInvitation match
             case None     => FC.unit
             case Some(id) => InvitationRepository.deleteProgram(runtime, id, lock)
    yield ()
