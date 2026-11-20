package yochat.core

import scodec.bits.ByteVector

/** 39 decimal digits from the sorted user public keys. Not a key, not a signature replacement. */
final case class SafetyNumber(digits: String, first: String, second: String, localReadsFirst: Boolean):

  def localSegment: String = if localReadsFirst then first else second

  def display: String = localSegment.grouped(5).mkString(" ")

  def displayBoth: (String, String) = (first.grouped(5).mkString(" "), second.grouped(5).mkString(" "))

object SafetyNumber:

  private val Info   = Crypto.ascii("yochat/safety-number/v1")
  private val Digits = 39

  def derive(localUserPub: Ed25519Public, peerUserPub: Ed25519Public): Either[CoreError, SafetyNumber] =
    val (lower, upper) =
      if compare(localUserPub.bytes, peerUserPub.bytes) <= 0 then (localUserPub, peerUserPub) else (peerUserPub, localUserPub)
    for
      raw   <- Crypto.hkdf(lower.bytes ++ upper.bytes, ByteVector.empty, Info, 16)
      number = BigInt(1, raw.toArray)
      digits = "0" * (Digits - number.toString.length) + number.toString
    yield SafetyNumber(
      digits = digits,
      first = digits.take(20),
      second = digits.drop(20),
      localReadsFirst = localUserPub == lower
    )

  private def compare(left: ByteVector, right: ByteVector): Int =
    val leftBytes  = left.toArray
    val rightBytes = right.toArray
    val shared     = math.min(leftBytes.length, rightBytes.length)
    val difference = (0 until shared).collectFirst {
      case index if leftBytes(index) != rightBytes(index) => (leftBytes(index) & 0xff) - (rightBytes(index) & 0xff)
    }
    difference.getOrElse(Integer.compare(leftBytes.length, rightBytes.length))
