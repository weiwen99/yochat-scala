package yochat.core

import java.nio.charset.StandardCharsets

import munit.FunSuite
import scodec.bits.ByteVector

class DataPayloadSpec extends FunSuite:

  private val Digits = "123456789012345678901234567890123456789"
  private val Body   = ByteVector.view("hi".getBytes(StandardCharsets.UTF_8))
  private val Binding = DataBinding("@a", "@b", Digits)

  private def right(value: Either[CoreError, ByteVector]): ByteVector =
    value.fold(error => fail(s"expected bytes, got $error"), identity)

  private def decoded(value: ByteVector): DataPayload =
    DataPayload.decode(value).fold(error => fail(s"expected payload, got $error"), identity)

  test("binding envelope has fixed canonical bytes") {
    val expected =
      ByteVector.fromValidHex("0101") ++
        ByteVector.fromValidHex("00000002") ++ ByteVector.view("@a".getBytes(StandardCharsets.UTF_8)) ++
        ByteVector.fromValidHex("00000002") ++ ByteVector.view("@b".getBytes(StandardCharsets.UTF_8)) ++
        ByteVector.view(Digits.getBytes(StandardCharsets.US_ASCII)) ++
        ByteVector.fromValidHex("00000002") ++ Body
    assertEquals(right(DataPayload.encode(DataPayload(Some(Binding), Body))), expected)
    assertEquals(decoded(expected), DataPayload(Some(Binding), Body))
  }

  test("non-binding envelope has fixed canonical bytes") {
    val expected = ByteVector.fromValidHex("0100") ++ ByteVector.fromValidHex("00000002") ++ Body
    assertEquals(right(DataPayload.encode(DataPayload(None, Body))), expected)
    assertEquals(decoded(expected), DataPayload(None, Body))
  }

  test("unknown version, flags, trailing bytes, and malformed digits are rejected") {
    assert(DataPayload.decode(ByteVector.fromValidHex("0200") ++ ByteVector.fromValidHex("00000000")).isLeft)
    assert(DataPayload.decode(ByteVector.fromValidHex("0102") ++ ByteVector.fromValidHex("00000000")).isLeft)
    assert(DataPayload.decode(right(DataPayload.encode(DataPayload(None, Body))) :+ 0.toByte).isLeft)
    assert(
      DataPayload
        .encode(DataPayload(Some(Binding.copy(safetyDigits = "x" * DataPayload.SafetyDigitsLength)), Body))
        .isLeft
    )
  }

  test("empty accounts are rejected and decomposed input is normalized on encode") {
    assert(DataPayload.encode(DataPayload(Some(Binding.copy(senderAccount = "")), Body)).isLeft)
    assert(DataPayload.encode(DataPayload(Some(Binding.copy(senderAccount = "a" * 4_097)), Body)).isLeft)
    val normalized = DataPayload
      .encode(DataPayload(Some(Binding.copy(peerAccount = "e\u0301")), Body))
      .fold(error => fail(error.toString), identity)
    assertEquals(decoded(normalized).binding.map(_.peerAccount), Some("é"))
    val nonCanonical = CanonicalWriter()
      .u8(DataPayload.Version)
      .u8(DataPayload.FlagsBinding)
      .string("e\u0301")
      .string("@b")
      .fixed(ByteVector.view(Digits.getBytes(StandardCharsets.US_ASCII)))
      .bytes(Body)
      .result
    assert(DataPayload.decode(nonCanonical).isLeft)
  }

  test("body and account limits are enforced") {
    assert(DataPayload.encode(DataPayload(None, ByteVector.fill(DataPayload.MaxBodyBytes + 1)(0))).isLeft)
    assert(DataPayload.encode(DataPayload(Some(Binding.copy(senderAccount = "a" * 4_096)), Body)).isRight)
  }

  test("toString does not expose accounts, safety digits, or body") {
    val text = DataPayload(Some(Binding), Body).toString
    assert(!text.contains("@a"))
    assert(!text.contains("@b"))
    assert(!text.contains(Digits))
    assert(!text.contains("hi"))
  }
