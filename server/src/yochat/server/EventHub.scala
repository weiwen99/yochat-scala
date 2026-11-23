package yochat.server

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete

/** Bounded fan-out of local UI notifications. A slow browser drops old events; it never blocks the runtime. */
final class EventHub:

  private val queues = new AtomicReference[Map[Long, SourceQueueWithComplete[String]]](Map.empty)
  private val nextId = new AtomicLong(1L)

  def register(queue: SourceQueueWithComplete[String]): Option[Long] =
    val id       = nextId.incrementAndGet()
    val accepted = queues
      .updateAndGet { current =>
        if current.size >= EventHub.MaxSubscribers then current else current.updated(id, queue)
      }
      .contains(id)
    if accepted then Some(id) else None

  def unregister(id: Long): Unit =
    queues.updateAndGet(current => current - id)
    ()

  def publish(kind: String): Unit =
    val message = s"""{"type":"$kind"}"""
    queues.get().values.foreach { queue =>
      queue.offer(message)
      ()
    }

object EventHub:

  private val MaxSubscribers: Int = 64
