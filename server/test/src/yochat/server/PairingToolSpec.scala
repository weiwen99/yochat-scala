package yochat.server

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.effect.{ExitCode, IO}
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import yochat.store.{KeySource, MasterKeyProvider, Store, StoreConfig}
import yochat.transport.RelayServer

final class PairingToolSpec extends FunSuite:

  test("pair-request and pair-accept create a companion identity and sync row") {
    val a1Directory = Files.createTempDirectory("yochat-pairing-tool-a1-")
    val a2Directory = Files.createTempDirectory("yochat-pairing-tool-a2-")
    val inviteFile  = a2Directory.resolve("invite.txt")
    val authFile    = a2Directory.resolve("auth.txt")
    val passphrase  = "correct horse battery staple"
    try
      Store
        .open(StoreConfig.default(a1Directory, KeySource.New(MasterKeyProvider.fixed(ExecutorTestSupport.master))))
        .use {
          case Left(error)  => IO.raiseError(new AssertionError(error.code))
          case Right(a1)    =>
            for
              service <- DevicePairingService.create(a1)
              invite  <- service.createInvite(1_000L, 60_000L)
              code    <- invite match
                           case Left(error) => IO.raiseError(new AssertionError(error))
                           case Right(text) => IO.pure(text)
              _       <- IO.blocking(Files.writeString(inviteFile, code, StandardCharsets.UTF_8))
              _       <- PairingTool.run(
                           List(
                             "pair-request",
                             "--data-dir", a2Directory.toString,
                             "--user", a1.identity.userPublic.bytes.toHex,
                             "--invite", inviteFile.toString,
                             "--name", "phone",
                             "--passphrase", passphrase
                           )
                         )
              pending <- IO.blocking(ujson.read(Files.readString(a2Directory.resolve("pairing.json"), StandardCharsets.UTF_8)))
              requestCode = pending("requestCode").str
              auth    <- service.confirm(requestCode, 2_000L)
              authCode <- auth match
                            case Left(error) => IO.raiseError(new AssertionError(error))
                            case Right(text) => IO.pure(text)
              _       <- IO.blocking(Files.writeString(authFile, authCode, StandardCharsets.UTF_8))
              accepted <- PairingTool.run(
                            List(
                              "pair-accept",
                              "--data-dir", a2Directory.toString,
                              "--auth", authFile.toString,
                              "--passphrase", passphrase
                            )
                          )
              _       = assertEquals(accepted, Some(ExitCode.Success))
              _       <- Store
                           .openCompanion(StoreConfig.default(a2Directory, KeySource.KeyFile(passphrase.toCharArray)), a1.identity.userPublic)
                           .use {
                             case Left(error)  => IO.raiseError(new AssertionError(error.code))
                             case Right(a2)    =>
                               for
                                 device <- a2.loadDevice(a2.identity.devicePublic)
                                 _       = assert(device.isRight)
                                 sync   <- a2.loadDeviceSync(a1.identity.devicePublic)
                                 _       = assert(sync.isRight)
                               yield ()
                           }
              _       <- RelayServer.start(0).use { relay =>
                           CompanionService
                             .create(
                               ServiceConfig(0, a2Directory, "127.0.0.1", relay.port, 0L),
                               a1.identity.userPublic,
                               passphrase.toCharArray
                             )
                             .use(_.sessionJson)
                             .map {
                               case Left(error)  => fail(error.code)
                               case Right(state) => assertEquals(state("readOnly").bool, true)
                             }
                         }
            yield ()
        }
        .unsafeRunSync()
    finally
      ExecutorTestSupport.deleteTree(a1Directory)
      ExecutorTestSupport.deleteTree(a2Directory)
  }
