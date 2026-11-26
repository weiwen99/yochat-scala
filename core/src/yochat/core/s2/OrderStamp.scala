package yochat.core.s2

import scodec.bits.ByteVector

import yochat.core.{CanonicalReader, CanonicalWriter, CoreError, Ed25519Public}

final case class OrderDependency(device: Ed25519Public, seq: Long)

/** Signed causal ordering evidence carried inside the encrypted message. `lamport` and dependencies use only device keys and author sequence
  * numbers, never wall-clock time.
  */
final case class OrderStamp(lamport: Long, deps: Vector[OrderDependency]):
  def bytes: ByteVector = OrderStamp.encode(lamport, deps)

  def depsMap: Map[Ed25519Public, Long] = deps.map(dep => dep.device -> dep.seq).toMap

  override def toString: String = s"OrderStamp(lamport=$lamport, deps=${deps.size})"

object OrderStamp:
  val MaxDependencies: Int = 8
  val LegacySalt: String   = "legacy"

  def legacy(seq: Long): OrderStamp = OrderStamp(seq, Vector.empty)

  def create(lamport: Long, deps: Vector[OrderDependency]): Either[CoreError, OrderStamp] =
    normalize(deps).flatMap { sorted =>
      if lamport < 0L then Left(CoreError.InvalidEncoding)
      else if sorted.size > MaxDependencies then Left(CoreError.InvalidEncoding)
      else if sorted.exists(_.seq < 0L) then Left(CoreError.InvalidEncoding)
      else Right(OrderStamp(lamport, sorted))
    }

  def encode(lamport: Long, deps: Vector[OrderDependency]): ByteVector =
    val sorted = deps.sortBy(_.device.bytes.toHex)
    val writer = CanonicalWriter().u64(lamport).u32(sorted.size.toLong)
    sorted.foreach { dep =>
      writer.fixed(dep.device.bytes)
      writer.u64(dep.seq)
    }
    writer.result

  def fromBytes(value: ByteVector): Either[CoreError, OrderStamp] =
    val reader = CanonicalReader(value)
    for
      (lamport, r1) <- reader.u64
      (count, r2)   <- r1.u32
      _             <- if count <= MaxDependencies.toLong then Right(()) else Left(CoreError.InvalidEncoding)
      (deps, r3)    <- decodeDeps(r2, count.toInt, Vector.empty)
      _             <- r3.finish
      result        <- create(lamport, deps)
    yield result

  def compare(left: OrderStamp, right: OrderStamp, leftDevice: Ed25519Public, leftSeq: Long, rightDevice: Ed25519Public, rightSeq: Long): Int =
    val byLamport = java.lang.Long.compare(left.lamport, right.lamport)
    if byLamport != 0 then byLamport
    else
      val byDevice = leftDevice.bytes.toHex.compareTo(rightDevice.bytes.toHex)
      if byDevice != 0 then byDevice else java.lang.Long.compare(leftSeq, rightSeq)

  private def decodeDeps(
    reader: CanonicalReader,
    remaining: Int,
    acc: Vector[OrderDependency]
  ): Either[CoreError, (Vector[OrderDependency], CanonicalReader)] =
    if remaining == 0 then Right((acc, reader))
    else
      for
        (raw, r1) <- reader.fixed(32)
        device    <- Ed25519Public.from(raw)
        (seq, r2) <- r1.u64
        next      <- decodeDeps(r2, remaining - 1, acc :+ OrderDependency(device, seq))
      yield next

  private def normalize(deps: Vector[OrderDependency]): Either[CoreError, Vector[OrderDependency]] =
    val sorted = deps.sortBy(_.device.bytes.toHex)
    if sorted.map(_.device).distinct.size != sorted.size then Left(CoreError.InvalidEncoding)
    else Right(sorted)
