package yochat.core

import scodec.bits.ByteVector

/** Reject caller/RNG bugs that collapse distinct key roles. Public-key equality is not secret. */
private[core] object KeySeparation:

  def distinct(values: ByteVector*): Either[CoreError, Unit] =
    if values.distinct.size == values.size then Right(()) else Left(CoreError.InvalidKey)
