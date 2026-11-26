package yochat.server

import java.nio.file.Files

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import yochat.store.{KeySource, MasterKeyProvider, Store, StoreConfig}

final class DevicePairingServiceSpec extends FunSuite:

  test("manual pairing creates a companion record and revoke removes its sync state") {
    val directory = Files.createTempDirectory("yochat-pairing-")
    try
      Store
        .open(StoreConfig.default(directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))))
        .use {
          case Left(error)  => IO.raiseError(new AssertionError(error.code))
          case Right(store) =>
            for
              service   <- DevicePairingService.create(store)
              invite    <- service.createInvite(1_000L, 60_000L)
              inviteCode = invite.fold(error => throw new AssertionError(error), identity)
              companion  = ExecutorTestSupport.edPair()
              request   <- DevicePairing.request(inviteCode, companion.privateKey, companion.publicKey, "phone")
              requestCode = request.fold(error => throw new AssertionError(error), value => value._1)
              inspected <- service.inspect(requestCode, 2_000L)
              confirmation = inspected.fold(error => throw new AssertionError(error), value => value("confirmation").str)
              rejected  <- service.confirm(requestCode, "0000-0000", 2_000L)
              _          = assertEquals(rejected, Left("confirmation"))
              auth      <- service.confirm(requestCode, confirmation, 2_000L)
              _          = assert(auth.isRight)
              devices   <- service.listDevicesJson
              _          = assert(devices.exists(value => value.arr.exists(_.obj.get("role").contains(ujson.Str("companion")))))
              revoked   <- service.revoke(companion.publicKey.bytes.toHex)
              _          = assert(revoked.isRight)
              after     <- service.listDevicesJson
              _          = assert(after.exists(value => value.arr.exists(_.obj.get("revokedAt").exists(_ != ujson.Null))))
              sync      <- store.loadDeviceSync(companion.publicKey)
              _          = assert(sync.isLeft)
            yield ()
        }
        .unsafeRunSync()
    finally ExecutorTestSupport.deleteTree(directory)
  }
