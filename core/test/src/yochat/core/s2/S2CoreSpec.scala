package yochat.core.s2

import scodec.bits.ByteVector

import munit.FunSuite

import yochat.core.{TestKeys, X25519Public}

final class S2CoreSpec extends FunSuite:

  private val user      = TestKeys.ed25519()
  private val primary   = TestKeys.ed25519()
  private val companion = TestKeys.ed25519()
  private val xPublic   = X25519Public.from(ByteVector.fill(32)(0x31)).fold(error => fail(error.toString), identity)
  private val nonce16   = ByteVector.fill(16)(0x41)
  private val hash32    = ByteVector.fill(32)(0x51)

  private def manifest: JoinManifest =
    val key = ManifestKey
      .create(ByteVector.fill(32)(0x21), user.publicKey, primary.publicKey)
      .fold(error => fail(error.toString), identity)
    JoinManifest.create(Map(key -> 7L)).fold(error => fail(error.toString), identity)

  test("join manifest is canonical, hash-stable and uses authenticated stream identities") {
    val value    = manifest
    val restored = JoinManifest.fromCanonical(value.canonical).fold(error => fail(error.toString), identity)
    assertEquals(restored, value)
    assertEquals(restored.hash.bytes, value.hash.bytes)
    val key      = value.entries.keys.head
    assert(value.covers(key, 7L))
    assert(!value.covers(key, 8L))
    assert(!value.covers(key.copy(conversationId = ByteVector.fill(32)(0x22)), 1L))
  }

  test("device authorization verifies the signed manifest and rejects tampering") {
    val auth = DeviceAuthorization
      .create(
        user.privateKey,
        user.publicKey,
        primary.publicKey,
        companion.publicKey,
        3L,
        manifest,
        9L,
        hash32,
        1_000L
      )
      .fold(error => fail(error.toString), identity)
    assert(auth.verify)
    assert(!auth.copy(joinEpoch = 4L).verify)
    assert(!auth.copy(joinCursorSyncSeq = 10L).verify)
  }

  test("pairing invite and request bind their keys and fields") {
    val invite = PairingInvite
      .create(user.privateKey, user.publicKey, primary.publicKey, xPublic, nonce16, 2_000L)
      .fold(error => fail(error.toString), identity)
    assert(invite.verify)
    assert(!invite.copy(nonce = ByteVector.fill(16)(0x42)).verify)

    val request = PairingRequest
      .create(companion.privateKey, nonce16, companion.publicKey, xPublic, ByteVector.fill(32)(0x61))
      .fold(error => fail(error.toString), identity)
    assert(request.verify)
    assert(!request.copy(encryptedName = ByteVector.fill(32)(0x62)).verify)
  }

  test("peer device list round-trips and rejects revocation/version tamper") {
    val entries = Vector(
      PeerDeviceEntry(primary.publicKey, PeerDeviceRole.Primary, "laptop", 1_000L, None),
      PeerDeviceEntry(companion.publicKey, PeerDeviceRole.Companion, "phone", 1_100L, Some(2_000L))
    )
    val list = PeerDeviceList
      .create(user.privateKey, user.publicKey, 4L, 2_500L, entries)
      .fold(error => fail(error.toString), identity)
    assert(list.verify)
    val restored = PeerDeviceList.fromBytes(list.bytes).fold(error => fail(error.toString), identity)
    assertEquals(restored, list)
    assert(!list.copy(version = 5L).verify)
    assert(!list.copy(entries = list.entries.map(entry => if entry.devicePub == companion.publicKey then entry.copy(revokedAt = None) else entry)).verify)
  }

  test("sync status and request bind one complete checkpoint") {
    val status = SyncStatus
      .create(companion.privateKey, hash32, 4L, ByteVector.fill(32)(0x71), 12L)
      .fold(error => fail(error.toString), identity)
    assert(status.verify(companion.publicKey))
    assert(!status.copy(lastAckedSyncSeq = 13L).verify(companion.publicKey))

    val request = SyncStatusRequest
      .create(primary.privateKey, hash32, nonce16)
      .fold(error => fail(error.toString), identity)
    assert(request.verify(primary.publicKey))
    assert(!request.copy(nonce = ByteVector.fill(16)(0x43)).verify(primary.publicKey))
  }
