package yochat.server

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import yochat.core.app.{AppState, TimerId}

class TimerServiceSpec extends FunSuite:

  test("a fired timer is removed from the fiber registry") {
    val program = for
      queue   <- Queue.bounded[IO, RuntimeCommand](8)
      state   <- Ref.of[IO, AppState](AppState.empty)
      service <- TimerService.create(queue, state, java.time.Clock.systemUTC())
      _       <- service.start(TimerId.Idle, System.currentTimeMillis(), 7L)
      command <- queue.take.timeout(5.seconds)
      _       <- command match
                   case RuntimeCommand.App(yochat.core.app.AppEvent.TimerFiredAt(TimerId.Idle, _, _, 7L)) => IO.unit
                   case other => IO.raiseError(new AssertionError(s"unexpected command: $other"))
      _       <- IO.sleep(50.millis)
      count   <- service.activeCount
      _       <- service.close
    yield count
    assert(program.unsafeRunSync() == 0)
  }
