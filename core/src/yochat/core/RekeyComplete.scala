package yochat.core

final case class RekeyComplete(state: RatchetState, newSession: RatchetState)
