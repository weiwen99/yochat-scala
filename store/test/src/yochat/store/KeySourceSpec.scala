package yochat.store

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files

import cats.effect.unsafe.implicits.global
import cats.effect.IO
import munit.FunSuite

class KeySourceSpec extends FunSuite:

  test("G10: key file wraps one master, wrong passphrase fails, and permissions are owner-only") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-keyfile-")
    try
      val paths  = StorePaths.under(directory)
      val first  = KeyFile.create(paths.keyFile, StoreTestSupport.passphrase).unsafeRunSync()
      val master = StoreTestSupport.right(first)
      val again  = KeyFile.unlock(paths.keyFile, StoreTestSupport.passphrase).unsafeRunSync()
      val wrong  = KeyFile.unlock(paths.keyFile, "wrong passphrase".toCharArray).unsafeRunSync()
      val perms  = PosixFilePermissions.toString(Files.getPosixFilePermissions(paths.keyFile))
      val bytes  = Files.readAllBytes(paths.keyFile)
      val text   = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
      assert(StoreTestSupport.right(again).bytes == master.bytes)
      assertEquals(StoreTestSupport.left(wrong), StorageError.UnlockFailed)
      assertEquals(perms, "rw-------")
      assert(!text.contains(master.bytes.toHex))
      assert(!text.contains(java.util.Base64.getEncoder.encodeToString(master.bytes.toArray)))
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G10: out-of-range Argon2 parameters are rejected before allocation") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-keyfile-bounds-")
    try
      val paths    = StorePaths.under(directory)
      StoreTestSupport.right(KeyFile.create(paths.keyFile, StoreTestSupport.passphrase).unsafeRunSync())
      val original = Files.readAllBytes(paths.keyFile)
      val tampered = original.clone()
      java.util.Arrays.fill(tampered, 6, 10, 0xff.toByte)
      Files.write(paths.keyFile, tampered)
      assertEquals(
        StoreTestSupport.left(KeyFile.unlock(paths.keyFile, StoreTestSupport.passphrase).unsafeRunSync()),
        StorageError.UnlockFailed
      )
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G10: key-file instance creates once and reopens the same identity and database") {
    val directory = StoreTestSupport.temporaryDirectory("yochat-store-keyfile-reopen-")
    try
      val session        = StoreTestSupport.state(safetyVerified = false)
      val firstIdentity  = StoreTestSupport.withKeyFileStore(directory) { store =>
        for
          _ <- store.insertContact(session.peerUserPub, StoreTestSupport.contact(), 0L)
          _ <- store.insertSession(session, 0L)
        yield store.identity.userPublic
      }
      val secondIdentity = StoreTestSupport.withKeyFileStore(directory) { store =>
        store.loadSession(session.sessionId).map { loaded =>
          assertEquals(loaded.map(_.version), Right(1L))
          store.identity.userPublic
        }
      }
      assertEquals(firstIdentity, secondIdentity)
      assert(Files.exists(StorePaths.under(directory).identityFile))
      assert(Files.exists(StorePaths.under(directory).keyFile))
    finally StoreTestSupport.deleteTree(directory)
  }

  test("G10: every partial-instance combination fails closed without creating a new master") {
    val absent       = StoreTestSupport.temporaryDirectory("yochat-store-partial-empty-")
    val keyOnly      = StoreTestSupport.temporaryDirectory("yochat-store-partial-key-")
    val identityOnly = StoreTestSupport.temporaryDirectory("yochat-store-partial-identity-")
    val databaseOnly = StoreTestSupport.temporaryDirectory("yochat-store-partial-db-")
    val noIdentity   = StoreTestSupport.temporaryDirectory("yochat-store-partial-no-identity-")
    val noKey        = StoreTestSupport.temporaryDirectory("yochat-store-partial-no-key-")
    try
      val absentConfig = StoreConfig.default(absent, KeySource.Existing(MasterKeyProvider.fixed(StoreTestSupport.master)))
      assertEquals(StoreTestSupport.left(StoreTestSupport.openResult(absentConfig)), StorageError.InstancePartiallyPresent)

      StoreTestSupport.right(KeyFile.create(StorePaths.under(keyOnly).keyFile, StoreTestSupport.passphrase).unsafeRunSync())
      assertEquals(
        StoreTestSupport.left(StoreTestSupport.openResult(StoreTestSupport.keyFileConfig(keyOnly))),
        StorageError.InstancePartiallyPresent
      )

      val identityKeys = IdentityFile.generate.unsafeRunSync()
      val identity     = IdentityFile
        .create(
          StorePaths.under(identityOnly).identityFile,
          identityKeys,
          StoreKeys.from(StoreTestSupport.master).identityKey
        )
        .unsafeRunSync()
      StoreTestSupport.right(identity)
      assertEquals(
        StoreTestSupport.left(
          StoreTestSupport.openResult(StoreConfig.default(identityOnly, KeySource.New(MasterKeyProvider.fixed(StoreTestSupport.master))))
        ),
        StorageError.InstancePartiallyPresent
      )

      Files.write(StorePaths.under(databaseOnly).database, Array.emptyByteArray)
      assertEquals(
        StoreTestSupport.left(
          StoreTestSupport.openResult(StoreConfig.default(databaseOnly, KeySource.New(MasterKeyProvider.fixed(StoreTestSupport.master))))
        ),
        StorageError.InstancePartiallyPresent
      )

      StoreTestSupport.right(
        StoreTestSupport.openResult(StoreConfig.default(noKey, KeySource.New(MasterKeyProvider.fixed(StoreTestSupport.master))))
      )
      assertEquals(
        StoreTestSupport.left(StoreTestSupport.openResult(StoreTestSupport.keyFileConfig(noKey))),
        StorageError.InstancePartiallyPresent
      )

      StoreTestSupport.right(
        StoreTestSupport.openResult(StoreConfig.default(noIdentity, KeySource.New(MasterKeyProvider.fixed(StoreTestSupport.master))))
      )
      Files.delete(StorePaths.under(noIdentity).identityFile)
      assertEquals(
        StoreTestSupport.left(
          StoreTestSupport.openResult(StoreConfig.default(noIdentity, KeySource.Existing(MasterKeyProvider.fixed(StoreTestSupport.master))))
        ),
        StorageError.InstancePartiallyPresent
      )
    finally Vector(absent, keyOnly, identityOnly, databaseOnly, noIdentity, noKey).foreach(StoreTestSupport.deleteTree)
  }

  test("G10: wrong passphrase and keychain-provider refusal both return UnlockFailed") {
    val keyDirectory      = StoreTestSupport.temporaryDirectory("yochat-store-wrong-pass-")
    val providerDirectory = StoreTestSupport.temporaryDirectory("yochat-store-provider-")
    try
      StoreTestSupport.right(StoreTestSupport.openResult(StoreTestSupport.keyFileConfig(keyDirectory)))
      assertEquals(
        StoreTestSupport.left(StoreTestSupport.openResult(StoreConfig.default(keyDirectory, KeySource.KeyFile("bad".toCharArray)))),
        StorageError.UnlockFailed
      )

      StoreTestSupport.right(
        StoreTestSupport.openResult(StoreConfig.default(providerDirectory, KeySource.New(MasterKeyProvider.fixed(StoreTestSupport.master))))
      )
      val refused = new MasterKeyProvider:
        def load: IO[MasterKey] = IO.raiseError(new IllegalStateException("keychain refused"))
      assertEquals(
        StoreTestSupport.left(StoreTestSupport.openResult(StoreConfig.default(providerDirectory, KeySource.Existing(refused)))),
        StorageError.UnlockFailed
      )
    finally
      StoreTestSupport.deleteTree(keyDirectory)
      StoreTestSupport.deleteTree(providerDirectory)
  }
