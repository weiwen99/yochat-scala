package yochat.store

import scodec.bits.ByteVector

import yochat.core.{Crypto, Key32, SessionId}

/** Master -> identity/database split, then per-purpose keys. No derived key is persisted in clear. */
final class StoreKeys private (master: MasterKey):

  private val IdentityInfo = Crypto.ascii("yochat-store/identity/v1")
  private val DatabaseInfo = Crypto.ascii("yochat-store/database/v1")

  private val identity: Key32 = derive(master.bytes, ByteVector.empty, IdentityInfo)
  private val database: Key32 = derive(master.bytes, ByteVector.empty, DatabaseInfo)

  val contactKey: Key32         = derive(database.bytes, ByteVector.empty, Crypto.ascii("yochat-store/contact/v1"))
  val invitationKey: Key32      = derive(database.bytes, ByteVector.empty, Crypto.ascii("yochat-store/invitation/v1"))
  val deviceKey: Key32          = derive(database.bytes, ByteVector.empty, Crypto.ascii("yochat-store/device/v1"))
  val companionMessageKey: Key32 =
    derive(database.bytes, ByteVector.empty, Crypto.ascii("yochat-store/companion-message/v1"))

  def identityKey: Key32 = identity

  def stateKey(sessionId: SessionId): Key32 =
    derive(database.bytes, sessionId.bytes, Crypto.ascii("yochat-store/session-state/v1"))

  def messageKey(sessionId: SessionId): Key32 =
    derive(database.bytes, sessionId.bytes, Crypto.ascii("yochat-store/message-body/v1"))

  override def toString: String = "StoreKeys(<redacted>)"

  private def derive(ikm: ByteVector, salt: ByteVector, info: ByteVector): Key32 =
    Crypto.hkdf32(ikm, salt, info) match
      case Right(key) => key
      case Left(_)    => throw new IllegalStateException("fixed-length HKDF failed")

object StoreKeys:

  def from(master: MasterKey): StoreKeys = new StoreKeys(master)
