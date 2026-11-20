package yochat.core

enum MessageKind(val code: Int):
  case Data            extends MessageKind(1)
  case RekeyConfirm    extends MessageKind(2)
  case RekeyConfirmAck extends MessageKind(3)

object MessageKind:

  def fromCode(code: Int): Either[CoreError, MessageKind] =
    values.find(_.code == code).toRight(CoreError.InvalidVersion(code))
