package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.Ed25519Public

/** A2's read-only display projection. It never stores peer wire bytes or a peer session; the body is sealed with A2's own store key. */
private[store] object CompanionMessageRepository:

  def upsert(runtime: StoreRuntime, record: CompanionMessageRecord): IO[Either[StorageError, CompanionMessageRecord]] =
    runtime.run {
      for
        _        <- validate(record)
        existing <- row(record.sourceId)
        _        <- existing match
                      case Some(value) if value.conversationId != record.conversationId ||
                          value.authorPub != record.authorPub ||
                          value.devicePub != record.devicePub ||
                          value.seq != record.seq =>
                        Sql.abort(StorageError.InvalidArgument("companion_source"))
                      case _ => FC.unit
        nonce    <- FC.delay(StoreCrypto.randomNonceUnsafe())
        sealedBody <- fromEither(
                      StoreCrypto.seal(
                        runtime.keys.companionMessageKey,
                        nonce,
                        StoreCrypto.companionMessageAad(
                          record.sourceId,
                          record.conversationId,
                          record.authorPub.bytes,
                          record.devicePub.bytes,
                          record.seq
                        ),
                        record.body
                      )
                    )
        changed  <-
          sql"""INSERT INTO companion_messages(
               |source_id, conversation_id, author_pub, device_pub, seq, body, state, created_at, sync_seq
               |) VALUES (
               |${record.sourceId}, ${record.conversationId.toArray}, ${record.authorPub.bytes.toArray},
               |${record.devicePub.bytes.toArray}, ${record.seq}, $sealedBody, ${MessageState.code(record.state)},
               |${record.createdAt}, ${record.syncSeq}
               |)
               |ON CONFLICT(source_id) DO UPDATE SET
               |body = excluded.body, state = excluded.state, created_at = excluded.created_at, sync_seq = excluded.sync_seq
               |""".stripMargin.update.run
        _        <- Sql.changed(changed)
      yield record
    }

  def load(runtime: StoreRuntime, sourceId: Long): IO[Either[StorageError, CompanionMessageRecord]] =
    runtime.run {
      row(sourceId).flatMap {
        case Some(value) => open(runtime, value)
        case None        => Sql.abort(StorageError.NotFound("companion-message"))
      }
    }

  def list(runtime: StoreRuntime, conversationId: ByteVector, limit: Int): IO[Either[StorageError, List[CompanionMessageRecord]]] =
    runtime.run {
      val bounded = math.max(1, math.min(limit, 1_000))
      sql"""SELECT source_id FROM companion_messages
           |WHERE conversation_id = ${conversationId.toArray}
           |ORDER BY seq DESC, source_id DESC LIMIT $bounded""".stripMargin
        .query[Long]
        .to[List]
        .flatMap { ids =>
          ids.reverse.foldLeft(FC.pure(List.empty[CompanionMessageRecord])) { (acc, id) =>
            acc.flatMap { values =>
              row(id).flatMap {
                case Some(value) => open(runtime, value).map(values :+ _)
                case None        => Sql.abort(StorageError.NotFound("companion-message"))
              }
            }
          }
        }
    }

  def count(runtime: StoreRuntime, conversationId: ByteVector): IO[Either[StorageError, Long]] =
    runtime.run {
      sql"SELECT count(*) FROM companion_messages WHERE conversation_id = ${conversationId.toArray}".query[Long].unique
    }

  private def row(sourceId: Long): ConnectionIO[Option[CompanionRow]] =
    sql"""SELECT source_id, conversation_id, author_pub, device_pub, seq, body, state, created_at, sync_seq
         |FROM companion_messages WHERE source_id = $sourceId""".stripMargin
      .query[(Long, Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Int, Long, Long)]
      .option
      .map {
        case None                                                                      => None
        case Some((id, conversation, author, device, seq, body, state, created, sync)) =>
          Some(CompanionRow(id, ByteVector.view(conversation), ByteVector.view(author), ByteVector.view(device), seq, ByteVector.view(body), state, created, sync))
      }

  private def open(runtime: StoreRuntime, row: CompanionRow): ConnectionIO[CompanionMessageRecord] =
    for
      author <- Ed25519Public.from(row.authorPub).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
      device <- Ed25519Public.from(row.devicePub).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
      state  <- fromEither(MessageState.fromCode(row.state))
      body   <- fromEither(
                  StoreCrypto.open(
                    runtime.keys.companionMessageKey,
                    StoreCrypto.companionMessageAad(row.sourceId, row.conversationId, author.bytes, device.bytes, row.seq),
                    row.body.toArray
                  )
                )
    yield CompanionMessageRecord(row.sourceId, row.conversationId, author, device, row.seq, body, state, row.createdAt, row.syncSeq)

  private def validate(record: CompanionMessageRecord): ConnectionIO[Unit] =
    if record.sourceId < 0L then Sql.abort(StorageError.InvalidArgument("source_id"))
    else if record.conversationId.size != 32L then Sql.abort(StorageError.InvalidArgument("conversation_id"))
    else if record.seq < 0L then Sql.abort(StorageError.InvalidArgument("seq"))
    else if record.syncSeq < 0L then Sql.abort(StorageError.InvalidArgument("sync_seq"))
    else if record.body.size > 1_048_576L then Sql.abort(StorageError.InvalidArgument("body"))
    else FC.unit

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)

  final private case class CompanionRow(
    sourceId: Long,
    conversationId: ByteVector,
    authorPub: ByteVector,
    devicePub: ByteVector,
    seq: Long,
    body: ByteVector,
    state: Int,
    createdAt: Long,
    syncSeq: Long
  )
