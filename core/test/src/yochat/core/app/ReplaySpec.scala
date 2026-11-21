package yochat.core.app

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import munit.FunSuite
import ujson.Value

class ReplaySpec extends FunSuite:

  final private case class Observed(seen: Set[Int], applied: Set[Int], buffered: Set[Int])

  test("ITF replay: Q2 trace feeds real Application.step and observable state matches") {
    val tracePath   = repositoryPath("models/traces/q2_order.itf.json")
    val bytes       = Files.readAllBytes(tracePath)
    val hash        = MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
    assertEquals(hash, "c24dea85199921193863337f9559059f791ad1a995c09fe7d2fa809ad1e6d0a6")
    val json        = ujson.read(new String(bytes, StandardCharsets.UTF_8))
    val states      = json("states").arr.toVector
    val peers       = AppTestSupport.connected
    val frames      = AppTestSupport.sendFrames(peers.alice, peers.aliceState.sessions.values.head.state, 3)._2
    val sessionId   = peers.bobState.sessions.keys.head
    val transitions = states
      .sliding(2)
      .flatMap {
        case Seq(previous, next) => Some((previous, next))
        case _                   => None
      }
      .toVector
    transitions.foldLeft(peers.bobState) { case (current, (previous, next)) =>
      val before               = observed(previous)
      val after                = observed(next)
      val added                = after.seen -- before.seen
      val action               =
        if added.contains(1) then Some(1)
        else if added.contains(2) then Some(2)
        else if added.contains(3) then Some(3)
        else None
      val (nextState, effects) = action match
        case None           => (current, Vector.empty)
        case Some(sequence) =>
          Application.step(
            current,
            AppEvent.InboundFrame(sequence.toLong, sessionId, frames(sequence - 1), None, 0L)
          )
      assertEquals(observable(nextState), after, s"state mismatch after ${action.fold("idle")(value => s"receive$value")}")
      action.foreach { sequence =>
        assert(effects.exists { case AppEffect.PersistInbound(message) => message.seq == sequence.toLong; case _ => false })
      }
      nextState
    }
  }

  private def observed(value: Value): Observed =
    val state    = value("s")
    val seen     = state("seen")("#set").arr.map(intOf).toSet
    val applied  = state("applied")("#set").arr.map(intOf).toSet
    val buffered = state("buffered")("#set").arr.map(intOf).toSet
    Observed(seen, applied, buffered)

  private def intOf(value: Value): Int =
    value match
      case obj: ujson.Obj => obj.value.get("#bigint").fold(obj.num.toInt)(_.str.toInt)
      case other          => other.num.toInt

  private def observable(state: AppState): Observed =
    val conversation = state.conversations.values.head
    Observed(
      seen = conversation.seen.map(_.seq.toInt),
      applied = conversation.applied.map(_.seq.toInt).toSet,
      buffered = conversation.buffered.keys.map(_.seq.toInt).toSet
    )

  private def repositoryPath(relative: String): Path =
    def loop(directory: Path): Option[Path] =
      val candidate = directory.resolve(relative)
      if Files.exists(candidate) then Some(candidate)
      else Option(directory.getParent).flatMap(loop)
    loop(Path.of("").toAbsolutePath).getOrElse(fail(s"missing repository file: $relative"))
