package yochat.core

/** Stable, secret-free errors for the S1 core. Callers log these codes, never message plaintext or key material. */
enum CoreError:
  case InvalidLength(field: String, expected: Int, actual: Long)
  case InvalidEncoding
  case InvalidVersion(found: Int)
  case InvalidKey
  case InvalidInvitation
  case InvalidReply
  case InvalidSignature
  case InvalidCiphertext
  case DuplicateSeq
  case Replay
  case StaleRatchet
  case GapTooLarge
  case UnknownSession
  case StaleGeneration
  case RekeyRequired
  case RekeyPending
  case RekeyFailed
  case Expired
  case InvalidState
