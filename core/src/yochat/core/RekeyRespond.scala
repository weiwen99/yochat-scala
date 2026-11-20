package yochat.core

final case class RekeyRespond(state: RatchetState, accept: RekeyAccept, newSession: Option[RatchetState])
