package yochat.store

import cats.effect.IO
import org.typelevel.doobie.implicits.*
import scodec.bits.ByteVector

/**
 * S3-C gate: "large history stays usable" with a defined data shape (ADR-0013).
 *
 * Shape: one conversation, 100,000 stored messages with 128-byte bodies; the newest 5,000 go through the
 * normal encryption path so search can decrypt them, the older 95,000 are inserted directly because no
 * gate reads them (they only make the table large).
 *
 * Measured: first screen (`listRecentMessages(50)`) and a worst-case search that must decrypt the whole
 * bounded 5,000-row scan before it finds the only match. A threshold miss exits non-zero.
 */
object SearchBench:

  private val TotalMessages: Long  = 100_000L
  private val RecentMessages: Long = 5_000L
  private val FirstScreenLimit     = 50
  private val FirstScreenRuns      = 20
  private val SearchRuns           = 10
  private val SearchScanLimit      = 5_000
  private val FirstScreenP95Ms     = 250L
  private val SearchP95Ms          = 1_500L

  def main(args: Array[String]): Unit =
    val directory = StoreTestSupport.temporaryDirectory("yochat-search-bench-")
    try
      StoreTestSupport.withStore(directory) { store =>
        val session = StoreTestSupport.state(safetyVerified = false)
        val conv    = StoreTestSupport.conversationId()
        for
          _             <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _             <- store.createConversation(conv, session.peerUserPub, 0L)
          _             <- store.insertSession(session, 0L)
          _             <- seedOldRows(store, session, conv, TotalMessages - RecentMessages)
          appendStarted <- IO.monotonic
          _             <- appendRecent(store, session, conv)
          appendEnded   <- IO.monotonic
          firstSamples  <- samples(FirstScreenRuns)(store.listRecentMessages(conv, FirstScreenLimit).map(_.toOption))
          searchSamples <- samples(SearchRuns)(
                             store
                               .searchMessages("needle-once", 20, SearchScanLimit)
                               .map {
                                 case Right(value) if value.hits.exists(_.seq == 1L) => Some(value)
                                 case _                                              => None
                               }
                           )
          firstP95       = percentile(firstSamples, 95)
          searchP95      = percentile(searchSamples, 95)
          _             <- IO.println(
                             s"SEARCH_BENCH rows=$TotalMessages recent=$RecentMessages bodies=128 " +
                               s"append_ms=${(appendEnded - appendStarted).toMillis} " +
                               s"first_limit=$FirstScreenLimit first_samples=$FirstScreenRuns " +
                               s"first_p50_ms=${percentile(firstSamples, 50)} first_p95_ms=$firstP95 " +
                               s"search_scan=$SearchScanLimit search_samples=$SearchRuns " +
                               s"search_p50_ms=${percentile(searchSamples, 50)} search_p95_ms=$searchP95 " +
                               s"threshold_first_p95_ms=$FirstScreenP95Ms threshold_search_p95_ms=$SearchP95Ms"
                           )
          _             <- IO.raiseWhen(firstP95 > FirstScreenP95Ms)(
                             new AssertionError(s"first screen p95 ${firstP95}ms exceeds $FirstScreenP95Ms")
                           )
          _             <- IO.raiseWhen(searchP95 > SearchP95Ms)(new AssertionError(s"search p95 ${searchP95}ms exceeds $SearchP95Ms"))
        yield ()
      }
    finally StoreTestSupport.deleteTree(directory)

  private def seedOldRows(
    store: Store,
    session: yochat.core.RatchetState,
    conversationId: ByteVector,
    count: Long
  ): IO[Unit] =
    // A distinct device key keeps (session, device, seq) unique; these rows are never decrypted because
    // every gate reads at most the newest 5,000 messages.
    val fakeDevice = ByteVector.fill(32)(0x7b.toByte).toArray
    val author     = session.peerUserPub.bytes.toArray
    val sessionId  = session.sessionId.bytes.toArray
    val body       = Array.fill[Byte](128)(0x2d.toByte)
    val wire       = Array.fill[Byte](64)(0x2d.toByte)
    store
      .transact(
        (1L to count).toVector.foldLeft(org.typelevel.doobie.free.connection.pure[Unit](())) { (acc, seq) =>
          acc.flatMap(_ =>
            sql"""INSERT INTO messages(conversation_id, session_id, author_pub, device_pub, seq, body, wire, state, created_at, attempts, sync_seq)
                 |VALUES (${conversationId.toArray}, $sessionId, $author, $fakeDevice, $seq, $body, $wire, 3, $seq, 0, 0)
                 |""".stripMargin.update.run.map(_ => ())
          )
        }
      )
      .map(_.left.map(error => new AssertionError(error.code)))
      .flatMap(value => IO.fromEither(value))
      .void

  private def appendRecent(store: Store, session: yochat.core.RatchetState, conversationId: ByteVector): IO[Unit] =
    (1L to RecentMessages).toVector.foldLeft(IO.unit) { (acc, seq) =>
      acc.flatMap { _ =>
        val text = if seq == 1L then "needle-once" else s"recent-${"x" * 114}-$seq"
        store
          .appendInbound(
            session,
            seq,
            0L,
            conversationId,
            session.peerUserPub,
            session.peerDevicePub,
            seq,
            StoreTestSupport.body(text),
            StoreTestSupport.wire(session, seq)
          )
          .flatMap {
            case Right(_)    => IO.unit
            case Left(error) => IO.raiseError(new AssertionError(s"append $seq failed: ${error.code}"))
          }
      }
    }

  private def samples(runs: Int)(probe: IO[Option[?]]): IO[Vector[Long]] =
    (1 to runs).toVector.foldLeft(IO.pure(Vector.empty[Long])) { (acc, _) =>
      acc.flatMap { values =>
        for
          started <- IO.monotonic
          _       <- probe.flatMap {
                       case Some(_) => IO.unit
                       case None    => IO.raiseError(new AssertionError("bench probe returned no result"))
                     }
          ended   <- IO.monotonic
        yield values :+ (ended - started).toMillis
      }
    }

  private def percentile(values: Vector[Long], percentile: Int): Long =
    if values.isEmpty then 0L
    else
      val sorted = values.sorted
      val index  = math.min(sorted.size - 1, math.max(0, math.ceil(sorted.size * percentile / 100.0).toInt - 1))
      sorted(index)
