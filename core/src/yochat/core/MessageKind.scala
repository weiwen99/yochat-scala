package yochat.core

enum MessageKind(val code: Int):
  case Data            extends MessageKind(1)
  case RekeyConfirm    extends MessageKind(2)
  case RekeyConfirmAck extends MessageKind(3)
  case DataAck         extends MessageKind(4)
  case Gap             extends MessageKind(5)

object MessageKind:

  def fromCode(code: Int): Either[CoreError, MessageKind] =
    values.find(_.code == code).toRight(CoreError.InvalidVersion(code))
