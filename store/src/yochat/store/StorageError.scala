package yochat.store

/** Stable storage errors. They never contain plaintext, keys, SQL parameters, or JDBC messages. */
enum StorageError:

  case NotFound(entity: String)
  case MissingHighWater(key: String)
  case StaleGeneration
  case InvalidStateVersion
  case WriteConflict
  case InvalidCiphertext
  case InvalidEnvelope
  case InvalidSafetyState
  case InstancePartiallyPresent
  case UnlockFailed
  case UnsupportedSchema
  case StorageFailure
  case DuplicateSeq
  case PendingMessages
  case CutoverNotReady
  case InvalidArgument(field: String)

  def code: String =
    this match
      case NotFound(_)              => "NotFound"
      case MissingHighWater(_)      => "MissingHighWater"
      case StaleGeneration          => "StaleGeneration"
      case InvalidStateVersion      => "InvalidStateVersion"
      case WriteConflict            => "WriteConflict"
      case InvalidCiphertext        => "InvalidCiphertext"
      case InvalidEnvelope          => "InvalidEnvelope"
      case InvalidSafetyState       => "InvalidSafetyState"
      case InstancePartiallyPresent => "InstancePartiallyPresent"
      case UnlockFailed             => "UnlockFailed"
      case UnsupportedSchema        => "UnsupportedSchema"
      case StorageFailure           => "StorageFailure"
      case DuplicateSeq             => "DuplicateSeq"
      case PendingMessages          => "PendingMessages"
      case CutoverNotReady          => "CutoverNotReady"
      case InvalidArgument(_)       => "InvalidArgument"

  override def toString: String = code

/** Internal rollback signal. Doobie rolls back the transaction; the public boundary maps it back to `StorageError`. */
final private[store] class StoreAbort(val error: StorageError) extends RuntimeException(error.code)
