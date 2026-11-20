package yochat.core

final case class RekeyConfirmResult(state: RatchetState, frame: WireFrame)

final case class RekeyConfirmAckResult(state: RatchetState, frame: WireFrame)

final case class RekeyAckResult(state: RatchetState)
