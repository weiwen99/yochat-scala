package yochat.store

import cats.effect.IO
import org.typelevel.doobie.*
import org.typelevel.doobie.free.connection as FC
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, MessageKind, SessionId, WireFrame}

private[store] object MessageRepository:

  private val MaxBodyBytes: Int = 1_048_576
  private val MaxListLimit: Int = 1_000

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
    createdAt: Long
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
    messageState: MessageState
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
          changed    <- sql"""INSERT INTO messages(conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at)
                             |VALUES (${conversationId.toArray}, ${state.sessionId.bytes.toArray}, ${authorPub.bytes.toArray},
                             |        ${devicePub.bytes.toArray}, $seq, ${sealedBody}, ${wire.toArray}, ${MessageState.code(
                              messageState
                            )}, $now)""".stripMargin.update.run
          _          <- Sql.changed(changed)
          messageId  <- sql"SELECT last_insert_rowid()".query[Long].unique
          _          <- SessionRepository.writeHighWater(prepared)
        yield AppendResult(
          SessionRecord(state, prepared.nextVersion, lock, now),
          MessageRecord(messageId, conversationId, state.sessionId, authorPub, devicePub, seq, body, wire, messageState, now)
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
        sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at
             |FROM messages
             |WHERE conversation_id = ${conversationId.toArray} AND author_pub = ${authorPub.bytes.toArray}
             |ORDER BY seq LIMIT $limit""".stripMargin
          .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long)]
          .to[List]
          .flatMap(_.foldLeft(FC.pure(List.empty[MessageRecord])) { (acc, tuple) =>
            acc.flatMap(values => decode(runtime, toRow(tuple)).map(values :+ _))
          })
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
        changed <-
          sql"UPDATE messages SET state = ${MessageState.code(next)} WHERE id = $messageId AND state = ${MessageState.code(expected)}".update.run
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
      value.createdAt
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

  private def row(
    conversationId: ByteVector,
    sessionId: SessionId,
    authorPub: Ed25519Public,
    devicePub: Ed25519Public,
    seq: Long
  ): ConnectionIO[Option[Row]] =
    sql"""SELECT id, conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at
         |FROM messages
         |WHERE conversation_id = ${conversationId.toArray} AND session_id = ${sessionId.bytes.toArray}
         |  AND author_pub = ${authorPub.bytes.toArray} AND device_pub = ${devicePub.bytes.toArray} AND seq = $seq""".stripMargin
      .query[(Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long)]
      .option
      .map(_.map(toRow))

  private def toRow(value: (Long, Array[Byte], Array[Byte], Array[Byte], Array[Byte], Long, Array[Byte], Array[Byte], Int, Long)): Row =
    val (id, conversation, session, author, device, seq, body, wire, state, createdAt) = value
    Row(id, conversation, session, author, device, seq, body, wire, state, createdAt)

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
