package yochat.core

final case class ReplyResult(
  envelope: ReplyEnvelope,
  text: String,
  fields: ReplyFields,
  keys: SessionKeys,
  state: RatchetState
):

  override def toString: String = "ReplyResult(<redacted>)"
