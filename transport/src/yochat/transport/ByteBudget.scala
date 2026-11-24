package yochat.transport

import cats.effect.{IO, Ref}

/** Byte-counted admission control for bounded queues. `tryReserve` never blocks; callers shed a frame when the budget is exhausted and
  * `release` after the consumer has taken it.
  */
final class ByteBudget private (limit: Long, used: Ref[IO, Long]):

  def tryReserve(bytes: Long): IO[Boolean] =
    if bytes < 0L || bytes > limit then IO.pure(false)
    else
      used.modify { current =>
        if bytes > limit - current then (current, false)
        else (current + bytes, true)
      }

  def release(bytes: Long): IO[Unit] =
    if bytes <= 0L then IO.unit
    else used.update(current => math.max(0L, current - bytes))

  def currentBytes: IO[Long] = used.get // scalafix:ok DisableSyntax.no-unsafe-get

object ByteBudget:

  def create(limit: Long): IO[ByteBudget] =
    val normalized = math.max(0L, limit)
    Ref.of[IO, Long](0L).map(used => new ByteBudget(normalized, used))
