package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, MessageKind, SessionId, WireFrame}
import yochat.core.s2.{LogicalKey, OrderStamp}

private[store] object MessageRepository:

  private val MaxBodyBytes: Int    = 1_048_576
  private val MaxListLimit: Int    = 1_000
  private val MaxRestoreLimit: Int = 100_000
  private val SearchPageSize: Int  = 256

  final private case class Row(
    id: Long,
    conversationId: Array[Byte],
    sessionId: Array[Byte],
    authorPub: Array[Byte],
    devicePub: Array[Byte],
    seq: Long,
    body: Array[Byte],
    wire: Array[Byte],
    state: Int,
    createdAt: Long,
    attempts: Int
  )

  def appendOutbound(
    runtime: StoreRuntime,
    state: yochat.core.RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector,
    messageState: MessageState,
    attempts: Int = 0,
    order: Option[OrderStamp] = None
  ): IO[Either[StorageError, AppendResult]] =
    append(
      runtime,
      state,
      expectedVersion,
      expectedLockGeneration,
      conversationId,
      authorPub,
      devicePub,
      seq,
      body,
      wire,
      messageState,
      attempts,
      Vector.empty,
      order
    )

  def appendInboundAndRetire(
    runtime: StoreRuntime,
    state: yochat.core.RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector,
    retired: Vector[(SessionId, CutoverRole, CutoverEvidence)],
    order: Option[OrderStamp] = None
  ): IO[Either[StorageError, AppendResult]] =
    append(
      runtime,
      state,
      expectedVersion,
      expectedLockGeneration,
      conversationId,
      authorPub,
      devicePub,
      seq,
      body,
      wire,
      MessageState.Received,
      0,
      retired,
      order
    )

  def appendInbound(
    runtime: StoreRuntime,
    state: yochat.core.RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector,
    order: Option[OrderStamp] = None
  ): IO[Either[StorageError, AppendResult]] =
    append(
      runtime,
      state,
      expectedVersion,
      expectedLockGeneration,
      conversationId,
      authorPub,
      devicePub,
      seq,
      body,
      wire,
      MessageState.Received,
      0,
      Vector.empty,
      order
    )

  private def append(
    runtime: StoreRuntime,
    state: yochat.core.RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector,
    messageState: MessageState,
    attempts: Int,
    retired: Vector[(SessionId, CutoverRole, CutoverEvidence)],
    order: Option[OrderStamp]
  ): IO[Either[StorageError, AppendResult]] =
    runtime.now.flatMap { now =>
      runtime.run {
        for
          lock       <- Sql.checkLock(expectedLockGeneration)
          current    <- SessionRepository.loadProgram(runtime, state.sessionId)
          _          <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
          _          <- if current.generation == lock then FC.unit else Sql.abort(StorageError.StaleGeneration)
          _          <- SessionRepository.checkSafety(runtime, state)
          _          <- validateMessage(conversationId, state.sessionId, devicePub, seq, body, wire)
          prepared   <- SessionRepository.prepareUpdate(runtime, current, state, lock, now)
          _          <- SessionRepository.writeRow(prepared)
          nonce      <- FC.delay(StoreCrypto.randomNonceUnsafe())
          sealedBody <- fromEither(
                          StoreCrypto.seal(
                            runtime.keys.messageKey(state.sessionId),
                            nonce,
                            StoreCrypto.messageAad(
                              conversationId,
                              state.sessionId.bytes,
                              authorPub.bytes,
                              devicePub.bytes,
                              seq,
                              StoreCrypto.wireHash(wire)
                            ),
                            body
                          )
                        )
          syncSeq    <- Sql.messageSyncSeq
          changed    <-
            sql"""INSERT INTO messages(conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts, sync_seq)
                             |VALUES (${conversationId.toArray}, ${state.sessionId.bytes.toArray}, ${authorPub.bytes.toArray},
                             |        ${devicePub.bytes.toArray}, $seq, ${sealedBody}, ${wire.toArray}, ${MessageState.code(
                  messageState
                )}, $now, $attempts, $syncSeq)""".stripMargin.update.run
          _          <- Sql.changed(changed)
          messageId  <- sql"SELECT last_insert_rowid()".query[Long].unique
          _          <- SessionRepository.writeHighWater(prepared)
          _          <- if seq == 1L then Sql.writeMeta(Sql.bindingKey(conversationId), 1L) else FC.unit
          _          <- MessageOrderRepository.write(
                          LogicalKey(conversationId, devicePub, seq),
                          order.getOrElse(OrderStamp.legacy(seq)),
                          now
                        )
          _          <- retired.foldLeft(FC.unit) { case (acc, (sessionId, role, evidence)) =>
                          acc.flatMap(_ => SessionRepository.retireProgram(runtime, sessionId, lock, role, evidence))
                        }
        yield AppendResult(
          SessionRecord(state, prepared.nextVersion, lock, now),
          MessageRecord(messageId, conversationId, state.sessionId, authorPub, devicePub, seq, body, wire, messageState, now, attempts, order)
        )
      }
    }

  def load(
    runtime: StoreRuntime,
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long
  ): IO[Either[StorageError, MessageRecord]] =
    runtime.run {
      row(conversationId, sessionId, authorPub, devicePub, seq).flatMap {
        case Some(value) => decode(runtime, value)
        case None        => Sql.abort(StorageError.NotFound("message"))
      }
    }

  def list(
    runtime: StoreRuntime,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    limit: Int
  ): IO[Either[StorageError, List[MessageRecord]]] =
    if limit <= 0 || limit > MaxListLimit then IO.pure(Left(StorageError.InvalidArgument("limit")))
    else
      runtime.run {
        sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
             |FROM messages
             |WHERE conversation_id = ${conversationId.toArray} AND author_pub = ${authorPub.bytes.toArray}
             |ORDER BY seq LIMIT $limit""".stripMargin
          .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
          .to[List]
          .flatMap(_.foldLeft(FC.pure(List.empty[MessageRecord])) { (acc, tuple) =>
            acc.flatMap(values => decode(runtime, toRow(tuple)).map(values :+ _))
          })
      }

  def loadById(runtime: StoreRuntime, messageId: Long): IO[Either[StorageError, MessageRecord]] =
    runtime.run {
      rowById(messageId).flatMap {
        case Some(value) => decode(runtime, value)
        case None        => Sql.abort(StorageError.NotFound("message"))
      }
    }

  def loadByLogicalKey(runtime: StoreRuntime, key: yochat.core.s2.LogicalKey): IO[Either[StorageError, MessageRecord]] =
    runtime.run {
      sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
           |FROM messages
           |WHERE conversation_id = ${key.conversationId.toArray} AND device_pub = ${key.authorDevice.bytes.toArray}
           |  AND seq = ${key.authorSeq}
           |ORDER BY id DESC LIMIT 1""".stripMargin
        .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
        .option
        .flatMap {
          case Some(value) => decode(runtime, toRow(value))
          case None        => Sql.abort(StorageError.NotFound("message"))
        }
    }

  def listRecent(
    runtime: StoreRuntime,
    conversationId: ByteVector,
    limit: Int
  ): IO[Either[StorageError, List[MessageRecord]]] =
    if limit <= 0 || limit > MaxListLimit then IO.pure(Left(StorageError.InvalidArgument("limit")))
    else
      runtime.run {
        sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
             |FROM messages
             |WHERE conversation_id = ${conversationId.toArray}
             |ORDER BY id DESC LIMIT $limit""".stripMargin
          .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
          .to[List]
          .flatMap(_.foldLeft(FC.pure(List.empty[MessageRecord])) { (acc, tuple) =>
            acc.flatMap(values => decode(runtime, toRow(tuple)).map(values :+ _))
          }.map(_.reverse))
      }

  /** Substring search over decrypted bodies, newest first. The scan is bounded by `SearchLimits.Scan` rows and pages with a rowid keyset, so
    * the cost is independent of the total history size; nothing is indexed in clear and no additional plaintext is written.
    */
  def search(runtime: StoreRuntime, needle: String, limit: Int): IO[Either[StorageError, SearchOutcome]] =
    searchBounded(runtime, needle, limit, SearchLimits.Scan)

  /** Test seam: the same program with an explicit scan bound, still capped by the production limit. */
  private[store] def searchBounded(
    runtime: StoreRuntime,
    needle: String,
    limit: Int,
    scanLimit: Int
  ): IO[Either[StorageError, SearchOutcome]] =
    if needle.isEmpty || needle.length > SearchLimits.MaxNeedle then IO.pure(Left(StorageError.InvalidArgument("needle")))
    else if limit <= 0 || limit > SearchLimits.Results then IO.pure(Left(StorageError.InvalidArgument("limit")))
    else if scanLimit <= 0 || scanLimit > SearchLimits.Scan then IO.pure(Left(StorageError.InvalidArgument("scan_limit")))
    else runtime.run(searchProgram(runtime, needle.toLowerCase(java.util.Locale.ROOT), limit, scanLimit))

  private def searchProgram(runtime: StoreRuntime, needle: String, limit: Int, scanLimit: Int): ConnectionIO[SearchOutcome] =
    type Row = (Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)
    def page(cursor: Option[Long], size: Int): ConnectionIO[List[Row]]                                   =
      cursor match
        case None        =>
          sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
               |FROM messages WHERE id <= (SELECT COALESCE(MAX(id), 0) FROM messages)
               |ORDER BY id DESC LIMIT $size""".stripMargin
            .query[Row]
            .to[List]
        case Some(value) =>
          sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
               |FROM messages WHERE id < $value ORDER BY id DESC LIMIT $size""".stripMargin
            .query[Row]
            .to[List]
    // `truncated` means "the result set is incomplete": either the hit limit stopped the scan or the scan
    // limit did, and rows remain. A page that ends the table is complete even when the last row matched.
    def loop(cursor: Option[Long], scanned: Int, hits: List[MessageRecord]): ConnectionIO[SearchOutcome] =
      if hits.size >= limit then FC.pure(SearchOutcome(hits.reverse.take(limit), scanned, truncated = true))
      else if scanned >= scanLimit then FC.pure(SearchOutcome(hits.reverse.take(limit), scanned, truncated = true))
      else
        val size = math.min(SearchPageSize, scanLimit - scanned)
        page(cursor, size).flatMap { rows =>
          val start: ConnectionIO[(Int, List[MessageRecord])] = FC.pure((scanned, hits))
          rows
            .foldLeft(start) { (acc, tuple) =>
              acc.flatMap { current =>
                val count = current._1
                val found = current._2
                decode(runtime, toRow(tuple)).map { record =>
                  val body =
                    new String(record.body.toArray, java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT)
                  if body.contains(needle) then (count + 1, record :: found) else (count + 1, found)
                }
              }
            }
            .flatMap { current =>
              if rows.size < size then FC.pure(SearchOutcome(current._2.reverse.take(limit), current._1, truncated = false))
              else loop(Some(rows.last._1), current._1, current._2)
            }
        }
    loop(None, 0, Nil)

  def listAll(
    runtime: StoreRuntime,
    conversationId: ByteVector,
    limit: Int
  ): IO[Either[StorageError, List[MessageRecord]]] =
    if limit <= 0 || limit > MaxRestoreLimit then IO.pure(Left(StorageError.InvalidArgument("limit")))
    else runtime.run(listAllProgram(runtime, conversationId, limit)).map(_.map(_.toList))

  private[store] def listAllProgram(
    runtime: StoreRuntime,
    conversationId: ByteVector,
    limit: Int = MaxRestoreLimit
  ): ConnectionIO[Vector[MessageRecord]] =
    def page(
      cursor: Option[(Array[Byte], Long)],
      size: Int
    ): ConnectionIO[List[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]] =
      cursor match
        case None                =>
          sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
               |FROM messages
               |WHERE conversation_id = ${conversationId.toArray}
               |ORDER BY author_pub, seq LIMIT $size""".stripMargin
            .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
            .to[List]
        case Some((author, seq)) =>
          sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
               |FROM messages
               |WHERE conversation_id = ${conversationId.toArray}
               |  AND (author_pub > $author OR (author_pub = $author AND seq > $seq))
               |ORDER BY author_pub, seq LIMIT $size""".stripMargin
            .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
            .to[List]

    def hasMore(cursor: (Array[Byte], Long)): ConnectionIO[Boolean] =
      val (author, seq) = cursor
      sql"""SELECT 1 FROM messages
           |WHERE conversation_id = ${conversationId.toArray}
           |  AND (author_pub > $author OR (author_pub = $author AND seq > $seq))
           |LIMIT 1""".stripMargin.query[Int].option.map(_.isDefined)

    def decodeRows(
      rows: List[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
    ): ConnectionIO[Vector[MessageRecord]] =
      rows.foldLeft(FC.pure(Vector.empty[MessageRecord])) { (acc, tuple) =>
        acc.flatMap(values => decode(runtime, toRow(tuple)).map(values :+ _))
      }

    def loop(cursor: Option[(Array[Byte], Long)], remaining: Int, acc: Vector[MessageRecord]): ConnectionIO[Vector[MessageRecord]] =
      if remaining <= 0 then FC.pure(acc)
      else
        val pageSize = math.min(512, remaining)
        page(cursor, pageSize).flatMap { rows =>
          decodeRows(rows).flatMap { decoded =>
            val next = acc ++ decoded
            rows.lastOption match
              case None          => FC.pure(next)
              case Some(lastRow) =>
                val nextCursor = (lastRow._4, lastRow._6)
                if rows.size < pageSize then FC.pure(next)
                else if remaining - rows.size <= 0 then
                  hasMore(nextCursor).flatMap {
                    case true  => Sql.abort(StorageError.InvalidArgument("restore_limit"))
                    case false => FC.pure(next)
                  }
                else loop(Some(nextCursor), remaining - rows.size, next)
          }
        }

    loop(None, limit, Vector.empty)

  def rebindOutbound(
    runtime: StoreRuntime,
    state: yochat.core.RatchetState,
    expectedVersion: Long,
    expectedLockGeneration: Long,
    oldSessionId: SessionId,
    conversationId: ByteVector,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    oldSeq: Long,
    seq: Long,
    body: ByteVector,
    wire: ByteVector
  ): IO[Either[StorageError, RebindOutcome]] =
    runtime.run {
      for
        _            <- Sql.checkLock(expectedLockGeneration)
        current      <- SessionRepository.loadProgram(runtime, state.sessionId)
        _            <- if current.version == expectedVersion then FC.unit else Sql.abort(StorageError.StaleGeneration)
        _            <- if current.generation == expectedLockGeneration then FC.unit else Sql.abort(StorageError.StaleGeneration)
        _            <- SessionRepository.checkSafety(runtime, state)
        now          <- FC.delay(runtime.clock.millis())
        messageState <- sql"""SELECT state FROM messages
                             |WHERE conversation_id = ${conversationId.toArray} AND session_id = ${oldSessionId.bytes.toArray}
                             |  AND author_pub = ${authorPub.bytes.toArray} AND device_pub = ${devicePub.bytes.toArray}
                             |  AND seq = $oldSeq""".stripMargin.query[Int].option
        result       <- messageState match
                          case None                                                                                                              => Sql.abort(StorageError.NotFound("message"))
                          case Some(code) if code == MessageState.code(MessageState.Delivered) || code == MessageState.code(MessageState.Failed) =>
                            FC.pure(RebindOutcome.Skipped)
                          case Some(_)                                                                                                           =>
                            for
                              _          <- validateMessage(conversationId, state.sessionId, devicePub, seq, body, wire)
                              prepared   <- SessionRepository.prepareUpdate(runtime, current, state, expectedLockGeneration, now)
                              _          <- SessionRepository.writeRow(prepared)
                              nonce      <- FC.delay(StoreCrypto.randomNonceUnsafe())
                              sealedBody <- fromEither(
                                              StoreCrypto.seal(
                                                runtime.keys.messageKey(state.sessionId),
                                                nonce,
                                                StoreCrypto.messageAad(
                                                  conversationId,
                                                  state.sessionId.bytes,
                                                  authorPub.bytes,
                                                  devicePub.bytes,
                                                  seq,
                                                  StoreCrypto.wireHash(wire)
                                                ),
                                                body
                                              )
                                            )
                              syncSeq    <- Sql.messageSyncSeq
                              changed    <- sql"""UPDATE messages
                                               |SET session_id = ${state.sessionId.bytes.toArray}, seq = $seq, body = ${sealedBody.toArray}, wire = ${wire.toArray},
                                               |    state = ${MessageState.code(MessageState.Pending)}, attempts = 0, sync_seq = $syncSeq
                                               |WHERE conversation_id = ${conversationId.toArray} AND session_id = ${oldSessionId.bytes.toArray}
                                               |  AND author_pub = ${authorPub.bytes.toArray} AND device_pub = ${devicePub.bytes.toArray}
                                               |  AND seq = $oldSeq""".stripMargin.update.run
                              _          <- Sql.changed(changed)
                              _          <- SessionRepository.writeHighWater(prepared)
                            yield RebindOutcome.Migrated
      yield result
    }

  def setState(
    runtime: StoreRuntime,
    messageId: Long,
    expected: MessageState,
    next: MessageState,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        _       <- Sql.checkLock(expectedLockGeneration)
        syncSeq <- Sql.messageSyncSeq
        changed <-
          sql"UPDATE messages SET state = ${MessageState.code(next)}, sync_seq = $syncSeq WHERE id = $messageId AND state = ${MessageState.code(expected)}".update.run
        _       <- Sql.changed(changed)
      yield ()
    }

  def setStateByKey(
    runtime: StoreRuntime,
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    expected: MessageState,
    next: MessageState,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    runtime.run {
      for
        _       <- Sql.checkLock(expectedLockGeneration)
        syncSeq <- Sql.messageSyncSeq
        changed <- sql"""UPDATE messages SET state = ${MessageState.code(next)}, sync_seq = $syncSeq
                        |WHERE conversation_id = ${conversationId.toArray} AND session_id = ${sessionId.bytes.toArray}
                        |  AND author_pub = ${authorPub.bytes.toArray} AND device_pub = ${devicePub.bytes.toArray}
                        |  AND seq = $seq AND state = ${MessageState.code(expected)}""".stripMargin.update.run
        _       <- Sql.changed(changed)
      yield ()
    }

  def setAttemptsByKey(
    runtime: StoreRuntime,
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long,
    attempts: Int,
    expectedLockGeneration: Long
  ): IO[Either[StorageError, Unit]] =
    if attempts < 0 then IO.pure(Left(StorageError.InvalidArgument("attempts")))
    else
      runtime.run {
        for
          _       <- Sql.checkLock(expectedLockGeneration)
          changed <- sql"""UPDATE messages SET attempts = $attempts
                          |WHERE conversation_id = ${conversationId.toArray} AND session_id = ${sessionId.bytes.toArray}
                          |  AND author_pub = ${authorPub.bytes.toArray} AND device_pub = ${devicePub.bytes.toArray} AND seq = $seq""".stripMargin.update.run
          _       <- Sql.changed(changed)
        yield ()
      }

  def pendingCount(runtime: StoreRuntime, sessionId: SessionId): IO[Either[StorageError, Long]] =
    runtime.run(SessionRepository.pendingCountProgram(sessionId))

  private def decode(runtime: StoreRuntime, value: Row): ConnectionIO[MessageRecord] =
    for
      sessionId <- decodeSession(ByteVector.view(value.sessionId))
      author    <- decodeAuthor(ByteVector.view(value.authorPub))
      device    <- decodeDevice(ByteVector.view(value.devicePub))
      wire      <- decodeWire(ByteVector.view(value.wire))
      _         <- if wire.header.kind == MessageKind.Data && wire.header.sessionId == sessionId &&
                     wire.header.devicePub == device && wire.header.seq == value.seq
                   then FC.unit
                   else Sql.abort(StorageError.InvalidCiphertext)
      body      <- fromEither(
                     StoreCrypto.open(
                       runtime.keys.messageKey(sessionId),
                       StoreCrypto.messageAad(
                         ByteVector.view(value.conversationId),
                         sessionId.bytes,
                         author.bytes,
                         device.bytes,
                         value.seq,
                         StoreCrypto.wireHash(ByteVector.view(value.wire))
                       ),
                       value.body
                     )
                   )
      state     <- decodeState(value.state)
    yield MessageRecord(
      value.id,
      ByteVector.view(value.conversationId),
      sessionId,
      author,
      device,
      value.seq,
      body,
      ByteVector.view(value.wire),
      state,
      value.createdAt,
      value.attempts
    )

  private def decodeSession(value: ByteVector): ConnectionIO[SessionId] =
    SessionId.from(value) match
      case Right(session) => FC.pure(session)
      case Left(_)        => Sql.abort(StorageError.InvalidEnvelope)

  private def decodeAuthor(value: ByteVector): ConnectionIO[Ed25519Public] =
    Ed25519Public.from(value) match
      case Right(publicKey) => FC.pure(publicKey)
      case Left(_)          => Sql.abort(StorageError.InvalidEnvelope)

  private def decodeDevice(value: ByteVector): ConnectionIO[Ed25519Public] =
    Ed25519Public.from(value) match
      case Right(publicKey) => FC.pure(publicKey)
      case Left(_)          => Sql.abort(StorageError.InvalidEnvelope)

  private def decodeWire(value: ByteVector): ConnectionIO[WireFrame] =
    WireFrame.parse(value) match
      case Right(frame) => FC.pure(frame)
      case Left(_)      => Sql.abort(StorageError.InvalidEnvelope)

  private def decodeState(value: Int): ConnectionIO[MessageState] =
    MessageState.fromCode(value) match
      case Right(state) => FC.pure(state)
      case Left(_)      => Sql.abort(StorageError.InvalidEnvelope)

  private def rowById(messageId: Long): ConnectionIO[Option[Row]] =
    sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
         |FROM messages WHERE id = $messageId""".stripMargin
      .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
      .option
      .map(_.map(toRow))

  private def row(
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long
  ): ConnectionIO[Option[Row]] =
    sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts
         |FROM messages
         |WHERE conversation_id = ${conversationId.toArray} AND session_id = ${sessionId.bytes.toArray}
         |  AND author_pub = ${authorPub.bytes.toArray} AND device_pub = ${devicePub.bytes.toArray} AND seq = $seq""".stripMargin
      .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)]
      .option
      .map(_.map(toRow))

  private def toRow(value: (Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long, Int)): Row =
    val (id, conversation, session, author, device, seq, body, wire, state, createdAt, attempts) = value
    Row(id, conversation, session, author, device, seq, body, wire, state, createdAt, attempts)

  private def validateMessage(
    conversationId: ByteVector,
    sessionId: SessionId,
    devicePub: Ed25519Public,
    seq: Long,
    body: ByteVector,
    wire: ByteVector
  ): ConnectionIO[Unit] =
    if conversationId.size != 32L then Sql.abort(StorageError.InvalidArgument("conversation_id"))
    else if seq <= 0L then Sql.abort(StorageError.InvalidArgument("seq"))
    else if body.size > MaxBodyBytes.toLong then Sql.abort(StorageError.InvalidArgument("body"))
    else
      WireFrame.parse(wire) match
        case Left(_)      => Sql.abort(StorageError.InvalidEnvelope)
        case Right(frame) =>
          if frame.header.kind != MessageKind.Data then Sql.abort(StorageError.InvalidCiphertext)
          else if frame.header.sessionId != sessionId then Sql.abort(StorageError.InvalidCiphertext)
          else if frame.header.devicePub != devicePub then Sql.abort(StorageError.InvalidCiphertext)
          else if frame.header.seq != seq then Sql.abort(StorageError.InvalidCiphertext)
          else FC.unit

  private def fromEither[A](value: Either[StorageError, A]): ConnectionIO[A] =
    value match
      case Right(result) => FC.pure(result)
      case Left(error)   => Sql.abort(error)
