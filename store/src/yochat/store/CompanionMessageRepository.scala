package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.s2.{LogicalKey, OrderStamp}
import yochat.core.Ed25519Public

/** A2's read-only display projection. It never stores peer wire bytes or a peer session; the body is sealed with A2's own store key. A1 sync
  * and B1 direct fanout share `logicalKey`, so the second copy updates the same row instead of creating a duplicate.
  */
private[store] object CompanionMessageRepository:

  def upsert(
    runtime: StoreRuntime,
    record: CompanionMessageRecord,
    order: Option[OrderStamp] = None
  ): IO[Either[StorageError, CompanionUpsert]] =
    runtime.run {
      for
        _          <- validate(record)
        byKey      <- record.logicalKey match
                        case None      => FC.pure(None)
                        case Some(key) => rowByLogicalKey(key.bytes)
        sourceId   <- byKey match
                        case Some(value) => FC.pure(value.sourceId)
                        case None        => allocateSourceId(record.sourceId)
        existing   <- row(sourceId)
        _          <- existing match
                        case Some(value)
                            if value.conversationId != record.conversationId ||
                              value.authorPub != record.authorPub ||
                              value.devicePub != record.devicePub ||
                              value.seq != record.seq ||
                              record.logicalKey.exists(key => value.logicalKey.exists(_ != key.bytes)) =>
                          Sql.abort(StorageError.InvalidArgument("companion_source"))
                        case _ => FC.unit
        nonce      <- FC.delay(StoreCrypto.randomNonceUnsafe())
        sealedBody <- fromEither(
                        StoreCrypto.seal(
                          runtime.keys.companionMessageKey,
                          nonce,
                          StoreCrypto.companionMessageAad(
                            sourceId,
                            record.conversationId,
                            record.authorPub.bytes,
                            record.devicePub.bytes,
                            record.seq
                          ),
                          record.body
                        )
                      )
        changed    <-
          sql"""INSERT INTO companion_messages(
               |source_id, conversation_id, author_pub, device_pub, seq, body, state, created_at, sync_seq, logical_key
               |) VALUES (
               |$sourceId, ${record.conversationId.toArray}, ${record.authorPub.bytes.toArray},
               |${record.devicePub.bytes.toArray}, ${record.seq}, $sealedBody, ${MessageState.code(record.state)},
               |${record.createdAt}, ${record.syncSeq}, ${record.logicalKey.map(_.bytes.toArray)}
               |)
               |ON CONFLICT(source_id) DO UPDATE SET
               |body = excluded.body, state = excluded.state, created_at = excluded.created_at,
               |sync_seq = excluded.sync_seq, logical_key = excluded.logical_key
               |""".stripMargin.update.run
        _          <- Sql.changed(changed)
        _          <- order match
                        case None        => FC.unit
                        case Some(stamp) =>
                          MessageOrderRepository.write(
                            LogicalKey(record.conversationId, record.devicePub, record.seq),
                            stamp,
                            record.createdAt
                          )
      yield CompanionUpsert(record.copy(sourceId = sourceId), existing.isEmpty)
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

  private val DirectSourceBase: Long = 1L << 62

  private def allocateSourceId(requested: Long): ConnectionIO[Long] =
    if requested > 0L then FC.pure(requested)
    else
      sql"""SELECT CASE WHEN COALESCE(MAX(source_id), 0) < $DirectSourceBase
           |THEN $DirectSourceBase ELSE COALESCE(MAX(source_id), 0) + 1 END
           |FROM companion_messages""".stripMargin.query[Long].unique.flatMap { value =>
        if value > 0L then FC.pure(value) else Sql.abort(StorageError.InvalidArgument("source_id"))
      }

  private def rowByLogicalKey(key: ByteVector): ConnectionIO[Option[CompanionRow]] =
    sql"""SELECT source_id FROM companion_messages WHERE logical_key = ${key.toArray}""".stripMargin
      .query[Long]
      .option
      .flatMap {
        case None        => FC.pure(None)
        case Some(value) => row(value)
      }

  private def row(sourceId: Long): ConnectionIO[Option[CompanionRow]] =
    sql"""SELECT source_id, conversation_id, author_pub, device_pub, seq, body, state, created_at, sync_seq, logical_key
         |FROM companion_messages WHERE source_id = $sourceId""".stripMargin
      .query[(Long, Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Int, Long, Long, Option[Array[Byte]])]
      .option
      .map {
        case None                                                                                  => None
        case Some((id, conversation, author, device, seq, body, state, created, sync, logicalKey)) =>
          Some(
            CompanionRow(
              id,
              ByteVector.view(conversation),
              ByteVector.view(author),
              ByteVector.view(device),
              seq,
              ByteVector.view(body),
              state,
              created,
              sync,
              logicalKey.map(ByteVector.view)
            )
          )
      }

  private def open(runtime: StoreRuntime, row: CompanionRow): ConnectionIO[CompanionMessageRecord] =
    for
      author     <- Ed25519Public.from(row.authorPub).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
      device     <- Ed25519Public.from(row.devicePub).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
      state      <- fromEither(MessageState.fromCode(row.state))
      body       <- fromEither(
                      StoreCrypto.open(
                        runtime.keys.companionMessageKey,
                        StoreCrypto.companionMessageAad(row.sourceId, row.conversationId, author.bytes, device.bytes, row.seq),
                        row.body.toArray
                      )
                    )
      logicalKey <- row.logicalKey match
                      case None      => FC.pure(None)
                      case Some(raw) =>
                        fromEither(LogicalKey.fromBytes(raw).left.map(_ => StorageError.InvalidEnvelope)).map(Some(_))
    yield CompanionMessageRecord(
      row.sourceId,
      row.conversationId,
      author,
      device,
      row.seq,
      body,
      state,
      row.createdAt,
      row.syncSeq,
      logicalKey
    )

  private def validate(record: CompanionMessageRecord): ConnectionIO[Unit] =
    if record.sourceId < 0L then Sql.abort(StorageError.InvalidArgument("source_id"))
    else if record.conversationId.size != 32L then Sql.abort(StorageError.InvalidArgument("conversation_id"))
    else if record.seq < 0L then Sql.abort(StorageError.InvalidArgument("seq"))
    else if record.syncSeq < 0L then Sql.abort(StorageError.InvalidArgument("sync_seq"))
    else if record.body.size > 1_048_576L then Sql.abort(StorageError.InvalidArgument("body"))
    else if record.logicalKey.exists(_.conversationId != record.conversationId) then Sql.abort(StorageError.InvalidArgument("logical_key"))
    else if record.logicalKey.exists(_.authorDevice != record.devicePub) then Sql.abort(StorageError.InvalidArgument("logical_key"))
    else if record.logicalKey.exists(_.authorSeq != record.seq) then Sql.abort(StorageError.InvalidArgument("logical_key"))
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
    syncSeq: Long,
    logicalKey: Option[ByteVector]
  )
