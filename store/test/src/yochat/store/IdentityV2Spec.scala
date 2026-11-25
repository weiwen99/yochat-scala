package yochat.store

import java.nio.file.Files

import cats.effect.unsafe.implicits.global
import scodec.bits.ByteVector

import munit.FunSuite

import yochat.core.Crypto

final class IdentityV2Spec extends FunSuite:

  test("v2 primary and companion files round-trip without changing key material") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-identity-v2-")
    val key       = StoreTestSupport.key32()
    try
      val primary = IdentityFile.generate.unsafeRunSync()
      val primaryPath = directory.resolve("primary.bin")
      StoreTestSupport.right(IdentityFile.create(primaryPath, primary, key).unsafeRunSync())
      val loadedPrimary = StoreTestSupport.right(IdentityFile.load(primaryPath, key).unsafeRunSync())
      assertEquals(loadedPrimary.role, IdentityRole.Primary)
      assertEquals(loadedPrimary.userPrivate.map(_.bytes), primary.userPrivate.map(_.bytes))
      assertEquals(loadedPrimary.userPublic, primary.userPublic)

      val companionPath = directory.resolve("companion.bin")
      StoreTestSupport.right(
        IdentityFile
          .createCompanion(companionPath, primary.userPublic, primary.devicePrivate, primary.devicePublic, key)
          .unsafeRunSync()
      )
      val loadedCompanion = StoreTestSupport.right(IdentityFile.load(companionPath, key).unsafeRunSync())
      assertEquals(loadedCompanion.role, IdentityRole.Companion)
      assertEquals(loadedCompanion.userPrivate, None)
      assertEquals(loadedCompanion.userPublic, primary.userPublic)
      assertEquals(loadedCompanion.devicePublic, primary.devicePublic)
    finally StoreTestSupport.deleteTree(directory)
  }

  test("v1 identity remains readable as a primary file") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-identity-v1-")
    val key       = StoreTestSupport.key32()
    try
      val primary = IdentityFile.generate.unsafeRunSync()
      val plain   =
        primary.userPrivate.map(_.bytes).getOrElse(ByteVector.empty) ++
          primary.userPublic.bytes ++ primary.devicePrivate.bytes ++ primary.devicePublic.bytes
      val nonce   = StoreCrypto.randomNonceUnsafe()
      val aad     = Crypto.ascii("yochat-store/identity-file/v1") ++ ByteVector(1.toByte)
      val cipher  = Crypto.aeadSeal(key, nonce, aad, plain).fold(error => fail(error.toString), identity)
      val bytes   = Crypto.ascii("YCID") ++ ByteVector(1.toByte) ++ nonce.bytes ++ cipher
      val path    = directory.resolve("legacy.bin")
      Files.write(path, bytes.toArray)
      val loaded  = StoreTestSupport.right(IdentityFile.load(path, key).unsafeRunSync())
      assertEquals(loaded.role, IdentityRole.Primary)
      assertEquals(loaded.userPrivate.map(_.bytes), primary.userPrivate.map(_.bytes))
    finally StoreTestSupport.deleteTree(directory)
  }
