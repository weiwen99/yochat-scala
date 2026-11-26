package yochat.server

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.*

import cats.effect.{IO, Ref}
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Key32}
import yochat.store.Store

/** Browser-facing A2 bootstrap. The invite and auth codes still travel out of band; the browser only carries the codes and displays the A2
  * confirmation value that the user compares with A1.
  */
final class CompanionPairingService private (
  store: Store,
  pending: Ref[IO, Option[CompanionPairingService.Pending]],
  paired: Ref[IO, Boolean]
):

  def status: IO[Value] =
    for
      done  <- paired.get  // scalafix:ok DisableSyntax.no-unsafe-get
      value <- pending.get // scalafix:ok DisableSyntax.no-unsafe-get
    yield value match
      case None        => ujson.Obj("paired" -> done)
      case Some(state) =>
        ujson.Obj(
          "paired"       -> done,
          "requestCode"  -> state.requestCode,
          "name"         -> state.name,
          "confirmation" -> state.confirmation,
          "fingerprint"  -> state.fingerprint
        )

  def awaitPaired: IO[Unit] =
    paired.get.flatMap(done => if done then IO.unit else IO.sleep(100.millis) *> awaitPaired) // scalafix:ok DisableSyntax.no-unsafe-get

  def accept(authCode: String): IO[Either[String, Unit]] =
    pending.get.flatMap { // scalafix:ok DisableSyntax.no-unsafe-get
      case None        => IO.pure(Left("no-pending-request"))
      case Some(value) =>
        DevicePairing.accept(authCode.trim, store.identity.userPublic, store.identity.devicePublic) match
          case Left(error) => IO.pure(Left(error))
          case Right(auth) =>
            DevicePairingStore.persist(store, authCode.trim, auth, value.root, value.name, Some(value.requestHash)).flatMap {
              case Left(error) => IO.pure(Left(error))
              case Right(_)    => paired.set(true) *> pending.set(None).as(Right(()))
            }
    }

object CompanionPairingService:

  final case class Pending(
    requestCode: String,
    root: Key32,
    confirmation: String,
    fingerprint: String,
    name: String,
    requestHash: ByteVector
  )

  def create(store: Store, inviteCode: String, name: String): IO[CompanionPairingService] =
    for
      pending <- Ref.of[IO, Option[Pending]](None)
      paired  <- Ref.of[IO, Boolean](false)
      request <- DevicePairing.request(inviteCode.trim, store.identity.devicePrivate, store.identity.devicePublic, name)
      _       <- request match
                   case Left(error)                       => IO.raiseError(new IllegalArgumentException(error))
                   case Right((code, root, confirmation)) =>
                     pending.set(
                       Some(
                         Pending(
                           code,
                           root,
                           confirmation.code,
                           confirmation.fingerprint,
                           name,
                           Crypto.sha256(ByteVector.view(code.getBytes(StandardCharsets.UTF_8))).bytes
                         )
                       )
                     )
    yield new CompanionPairingService(store, pending, paired)
