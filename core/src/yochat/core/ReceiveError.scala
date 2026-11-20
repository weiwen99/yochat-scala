package yochat.core

/** A rejected frame. The state is unchanged except for the bounded AEAD failure counter, which must be persisted by the executor. */
final case class ReceiveError(state: RatchetState, error: CoreError):

  override def toString: String = s"ReceiveError(error=$error)"
