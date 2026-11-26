package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.s2.LogicalKey
import yochat.core.Ed25519Public

/** B1's per-recipient delivery state. One companion ACK never clears another recipient's row. */
private[store] object DeliveryReceiptRepository:

  def upsert(
    runtime: StoreRuntime,
    receipt: DeliveryReceipt,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, DeliveryReceipt]] =
    runtime.run {
      for
        _       <- Sql.checkLock(expectedLockGeneration)
        _       <- validate(receipt)
        changed <-
          sql"""INSERT INTO delivery_receipts(
               |conversation_id, author_device, author_seq, recipient_device, logical_key,
               |state, created_at, updated_at, attempts
               |) VALUES (
               |${receipt.conversationId.toArray}, ${receipt.authorDevice.bytes.toArray}, ${receipt.authorSeq},
               |${receipt.recipientDevice.bytes.toArray}, ${receipt.logicalKey.bytes.toArray},
               |${receipt.state}, ${receipt.createdAt}, ${receipt.updatedAt}, ${receipt.attempts}
               |)
               |ON CONFLICT(conversation_id, author_device, author_seq, recipient_device) DO UPDATE SET
               |state = excluded.state, updated_at = excluded.updated_at, attempts = excluded.attempts
               |""".stripMargin.update.run
        _       <- Sql.changed(changed)
      yield receipt
    }

  def markAcked(
    runtime: StoreRuntime,
    logicalKey: LogicalKey,
    recipientDevice: Ed25519Public,
    now: Long,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Boolean]] =
    runtime.run {
      for
        _       <- Sql.checkLock(expectedLockGeneration)
        _       <- if now < 0L then Sql.abort(StorageError.InvalidArgument("now")) else FC.unit
        changed <-
          sql"""UPDATE delivery_receipts
               |SET state = 2, updated_at = $now
               |WHERE logical_key = ${logicalKey.bytes.toArray} AND recipient_device = ${recipientDevice.bytes.toArray}
               |  AND state = 1""".stripMargin.update.run
        exists  <- sql"""SELECT count(*) FROM delivery_receipts
                        |WHERE logical_key = ${logicalKey.bytes.toArray} AND recipient_device = ${recipientDevice.bytes.toArray}
                        |  AND state = 2""".stripMargin.query[Long].unique
      yield changed == 1 || exists > 0L
    }

  def listPending(runtime: StoreRuntime, limit: Int): IO[Either[StorageError, List[DeliveryReceipt]]] =
    if limit <= 0 || limit > 1_000 then IO.pure(Left(StorageError.InvalidArgument("limit")))
    else
      runtime.run {
        sql"""SELECT conversation_id, author_device, author_seq, recipient_device, logical_key,
             |state, created_at, updated_at, attempts
             |FROM delivery_receipts WHERE state = 1 ORDER BY updated_at, author_seq LIMIT $limit""".stripMargin
          .query[(Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Long, Int)]
          .to[List]
          .flatMap(_.foldLeft(FC.pure(List.empty[DeliveryReceipt])) { (acc, row) =>
            acc.flatMap { values =>
              decode(row).map(values :+ _)
            }
          })
      }

  def load(
    runtime: StoreRuntime,
    logicalKey: LogicalKey,
    recipientDevice: Ed25519Public
  ): IO[Either[StorageError, DeliveryReceipt]] =
    runtime.run {
      sql"""SELECT conversation_id, author_device, author_seq, recipient_device, logical_key,
           |state, created_at, updated_at, attempts
           |FROM delivery_receipts
           |WHERE logical_key = ${logicalKey.bytes.toArray} AND recipient_device = ${recipientDevice.bytes.toArray}""".stripMargin
        .query[(Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Long, Int)]
        .option
        .flatMap {
          case Some(row) => decode(row)
          case None      => Sql.abort(StorageError.NotFound("delivery-receipt"))
        }
    }

  private def decode(
    row: (Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Long, Int)
  ): ConnectionIO[DeliveryReceipt] =
    val (conversation, author, seq, recipient, logical, state, created, updated, attempts) = row
    for
      authorPub    <- Ed25519Public.from(ByteVector.view(author)).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
      recipientPub <- Ed25519Public.from(ByteVector.view(recipient)).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
      key          <- LogicalKey.fromBytes(ByteVector.view(logical)).fold(_ => Sql.abort(StorageError.InvalidEnvelope), FC.pure)
      _            <- if state == 1 || state == 2 then FC.unit else Sql.abort(StorageError.InvalidEnvelope)
      _            <- if attempts >= 0 then FC.unit else Sql.abort(StorageError.InvalidEnvelope)
      _            <- if seq < 0L || created < 0L || updated < 0L then Sql.abort(StorageError.InvalidEnvelope) else FC.unit
    yield DeliveryReceipt(
      ByteVector.view(conversation),
      authorPub,
      seq,
      recipientPub,
      key,
      state,
      created,
      updated,
      attempts
    )

  private def validate(receipt: DeliveryReceipt): ConnectionIO[Unit] =
    if receipt.conversationId.size != 32L then Sql.abort(StorageError.InvalidArgument("conversation_id"))
    else if receipt.authorSeq < 0L then Sql.abort(StorageError.InvalidArgument("author_seq"))
    else if receipt.logicalKey.conversationId != receipt.conversationId then Sql.abort(StorageError.InvalidArgument("logical_key"))
    else if receipt.logicalKey.authorDevice != receipt.authorDevice then Sql.abort(StorageError.InvalidArgument("logical_key"))
    else if receipt.logicalKey.authorSeq != receipt.authorSeq then Sql.abort(StorageError.InvalidArgument("logical_key"))
    else if receipt.state != 1 && receipt.state != 2 then Sql.abort(StorageError.InvalidArgument("state"))
    else if receipt.createdAt < 0L || receipt.updatedAt < 0L || receipt.attempts < 0 then
      Sql.abort(StorageError.InvalidArgument("receipt_time"))
    else FC.unit
