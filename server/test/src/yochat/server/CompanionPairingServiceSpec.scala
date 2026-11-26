package yochat.server

import java.nio.file.Files

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import yochat.store.{KeySource, MasterKeyProvider, Store, StoreConfig}

final class CompanionPairingServiceSpec extends FunSuite:

  test("browser pairing status exposes the same request and accepts the auth") {
    val a1Directory = Files.createTempDirectory("yochat-browser-pairing-a1-")
    val a2Directory = Files.createTempDirectory("yochat-browser-pairing-a2-")
    val passphrase  = "correct horse battery staple"
    try
      Store
        .open(StoreConfig.default(a1Directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))))
        .use {
          case Left(error) => IO.raiseError(new AssertionError(error.code))
          case Right(a1)   =>
            for
              pairing <- DevicePairingService.create(a1)
              invite  <- pairing.createInvite(1_000L, 60_000L)
              code    <- invite match
                           case Left(error)  => IO.raiseError(new AssertionError(error))
                           case Right(value) => IO.pure(value)
              _       <- Store
                           .openCompanion(
                             StoreConfig.default(a2Directory, KeySource.KeyFile(passphrase.toCharArray)),
                             a1.identity.userPublic
                           )
                           .use {
                             case Left(error)  => IO.raiseError(new AssertionError(error.code))
                             case Right(a2)    =>
                               for
                                 service <- CompanionPairingService.create(a2, code, "phone")
                                 before  <- service.status
                                 request = before("requestCode").str
                                 auth    <- pairing.confirm(request, before("confirmation").str, 2_000L)
                                 authCode = auth.fold(error => throw new AssertionError(error), identity)
                                 accepted <- service.accept(authCode)
                                 _        = assert(accepted.isRight)
                                 after    <- service.status
                                 _        = assertEquals(after("paired").bool, true)
                                 device   <- a2.loadDevice(a2.identity.devicePublic)
                                 _        = assert(device.isRight)
                               yield ()
                           }
            yield ()
        }
        .unsafeRunSync()
    finally
      ExecutorTestSupport.deleteTree(a1Directory)
      ExecutorTestSupport.deleteTree(a2Directory)
  }
