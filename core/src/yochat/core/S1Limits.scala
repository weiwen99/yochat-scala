package yochat.core

/** ADR-0006 hard caps. They are protocol constants, not user configuration. */
object S1Limits:

  val MaxSkipGap: Int           = 64
  val MaxSkipTotal: Int         = 256
  val MaxRetiredRatchets: Int   = 64
  val MaxRekeyFailures: Int     = 3
  val RekeyCooldownMillis: Long = 60_000L
  val MaxRekeyAttempts: Int     = 3
