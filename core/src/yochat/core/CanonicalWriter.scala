package yochat.core

import java.io.ByteArrayOutputStream

import scodec.bits.ByteVector

/** Fixed-order, length-prefixed binary encoding. `fixed` writes bytes without a length; `bytes` adds u32. */
final private[core] class CanonicalWriter private ():

  private val out = new ByteArrayOutputStream(256)

  def u8(value: Int): CanonicalWriter =
    out.write(value & 0xff)
    this

  def u32(value: Long): CanonicalWriter =
    out.write(((value >>> 24) & 0xff).toInt)
    out.write(((value >>> 16) & 0xff).toInt)
    out.write(((value >>> 8) & 0xff).toInt)
    out.write((value & 0xff).toInt)
    this

  def u64(value: Long): CanonicalWriter =
    out.write(((value >>> 56) & 0xff).toInt)
    out.write(((value >>> 48) & 0xff).toInt)
    out.write(((value >>> 40) & 0xff).toInt)
    out.write(((value >>> 32) & 0xff).toInt)
    out.write(((value >>> 24) & 0xff).toInt)
    out.write(((value >>> 16) & 0xff).toInt)
    out.write(((value >>> 8) & 0xff).toInt)
    out.write((value & 0xff).toInt)
    this

  def fixed(value: ByteVector): CanonicalWriter =
    out.write(value.toArray)
    this

  def bytes(value: ByteVector): CanonicalWriter =
    u32(value.size)
    fixed(value)

  def string(value: String): CanonicalWriter = bytes(Crypto.text(value))

  def result: ByteVector = ByteVector.view(out.toByteArray)

private[core] object CanonicalWriter:

  def apply(): CanonicalWriter = new CanonicalWriter()
