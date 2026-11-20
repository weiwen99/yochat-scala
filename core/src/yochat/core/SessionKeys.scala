package yochat.core

final case class SessionKeys(sessionId: SessionId, rootKey: Key32, rekeyBase: Key32):

  override def toString: String = "SessionKeys(<redacted>)"
