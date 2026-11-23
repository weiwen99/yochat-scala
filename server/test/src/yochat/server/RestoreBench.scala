package yochat.server

import cats.effect.IO

import yochat.store.Store

/**
 * Repeatable 10k store restore benchmark. It is a command, not a test: `./mill server.bench`.
 *
 * The measured store is written once with 10k inbound messages. The write samples are per-message
 * transaction latencies; restore samples are full `AppStateRestore.load` calls. A separate warmup
 * store keeps JIT and SQLite page-cache startup out of the measured percentiles.
 */
object RestoreBench:

  private val WarmupMessages: Long    = 1_000L
  private val MeasuredMessages: Long  = 10_000L
  private val RestoreWarmupRuns: Int  = 5
  private val RestoreMeasuredRuns: Int = 20

  def main(args: Array[String]): Unit =
    warmup()
    measured()

  private def warmup(): Unit =
    ExecutorTestSupport.withStore { store =>
      val fixture = ExecutorTestSupport.fixture()
      for
        _ <- prepare(store, fixture)
        _ <- (1L to WarmupMessages).toVector.foldLeft(IO.unit)((acc, seq) => acc *> append(store, fixture, seq).void)
        _ <- (1 to 3).toVector.foldLeft(IO.unit)((acc, _) => acc *> restore(store, fixture, WarmupMessages + 1L).void)
      yield ()
    }

  private def measured(): Unit =
    ExecutorTestSupport.withStore { store =>
      val fixture = ExecutorTestSupport.fixture()
      for
        _              <- prepare(store, fixture)
        appendStart    <- IO.monotonic
        appendSamples  <- (1L to MeasuredMessages).toVector.foldLeft(IO.pure(Vector.empty[Long])) { (acc, seq) =>
                            acc.flatMap(values => append(store, fixture, seq).map(values :+ _))
                          }
        appendEnd      <- IO.monotonic
        _              <- (1 to RestoreWarmupRuns).toVector.foldLeft(IO.unit)((acc, _) =>
                            acc *> restore(store, fixture, MeasuredMessages + 1L).void
                          )
        restoreSamples <- (1 to RestoreMeasuredRuns).toVector.foldLeft(IO.pure(Vector.empty[Long])) { (acc, _) =>
                            acc.flatMap(values => restore(store, fixture, MeasuredMessages + 1L).map(values :+ _))
                          }
        _              <- IO.println(
                            s"SERVER_BENCH messages=$MeasuredMessages " +
                              s"warmup_messages=$WarmupMessages " +
                              s"append_ms=${millis((appendEnd - appendStart).toNanos)} " +
                              s"append_p50_us=${micros(percentile(appendSamples, 50))} " +
                              s"append_p95_us=${micros(percentile(appendSamples, 95))} " +
                              s"restore_warmup=$RestoreWarmupRuns " +
                              s"restore_samples=$RestoreMeasuredRuns " +
                              s"restore_p50_ms=${millis(percentile(restoreSamples, 50))} " +
                              s"restore_p95_ms=${millis(percentile(restoreSamples, 95))} " +
                              s"restore_min_ms=${millis(restoreSamples.min)} " +
                              s"restore_max_ms=${millis(restoreSamples.max)} " +
                              s"next_seq=${MeasuredMessages + 1L}"
                          )
      yield ()
    }

  private def prepare(store: Store, fixture: ExecutorTestSupport.Fixture): IO[Unit] =
    for
      _ <- ExecutorTestSupport.initialize(fixture, store)
      _ <- store.createConversation(fixture.conversationId, fixture.peerUser, 0L)
    yield ()

  private def append(store: Store, fixture: ExecutorTestSupport.Fixture, seq: Long): IO[Long] =
    for
      started <- IO.monotonic
      result  <- store.appendInbound(
                   fixture.state,
                   seq,
                   0L,
                   fixture.conversationId,
                   fixture.peerUser,
                   fixture.peerDevice,
                   seq,
                   ExecutorTestSupport.body(s"message-$seq"),
                   ExecutorTestSupport.wire(fixture, seq)
                 )
      _       <- IO.fromEither(result.left.map(error => new AssertionError(s"append $seq failed: ${error.code}")))
      ended   <- IO.monotonic
    yield (ended - started).toNanos

  private def restore(store: Store, fixture: ExecutorTestSupport.Fixture, expectedNextSeq: Long): IO[Long] =
    for
      started <- IO.monotonic
      result  <- AppStateRestore.load(store)
      state   <- IO.fromEither(result.left.map(error => new AssertionError(s"restore failed: ${error.code}")))
      nextSeq  = state.conversations.get(fixture.conversationId).flatMap(_.nextSeq.get(fixture.peerUser)).getOrElse(0L)
      _       <- if nextSeq == expectedNextSeq then IO.unit
                 else IO.raiseError(new AssertionError(s"restore next_seq=$nextSeq expected=$expectedNextSeq"))
      ended   <- IO.monotonic
    yield (ended - started).toNanos

  private def percentile(samples: Vector[Long], percent: Int): Long =
    if samples.isEmpty then 0L
    else
      val sorted = samples.sorted
      val rank   = math.ceil(percent.toDouble / 100.0 * sorted.size).toInt.max(1).min(sorted.size)
      sorted(rank - 1)

  private def millis(nanos: Long): String = f"${nanos / 1_000_000.0}%.2f"

  private def micros(nanos: Long): String = f"${nanos / 1_000.0}%.2f"

