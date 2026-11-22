// Q6: delivery ACK and gap request. A peer ACK terminates retries; a gap
// requests a resend while the attempt budget remains bounded. Transport
// acceptance is not delivery; only an ACK sets `acked`.
module q6Delivery {
  pure val attemptLimit = 3

  type State = {
    pending: bool,
    sent: bool,
    acked: bool,
    gap: bool,
    failed: bool,
    attempts: int,
  }

  var s: State

  action init = s' = {
    pending: true,
    sent: false,
    acked: false,
    gap: false,
    failed: false,
    attempts: 0,
  }

  action send = all {
    s.pending,
    not(s.sent),
    not(s.acked),
    not(s.failed),
    s.attempts < attemptLimit,
    s' = { ...s, attempts: s.attempts + 1, sent: true },
  }

  action ack = all {
    s.sent,
    not(s.acked),
    s' = { ...s, pending: false, sent: false, acked: true, gap: false },
  }

  action gap = all {
    s.sent,
    not(s.acked),
    not(s.failed),
    not(s.gap),
    s' = { ...s, gap: true },
  }

  action resend = all {
    s.gap,
    s.pending,
    not(s.acked),
    not(s.failed),
    s.attempts < attemptLimit,
    s' = { ...s, attempts: s.attempts + 1, gap: false, sent: true },
  }

  action exhaust = all {
    s.sent,
    s.pending,
    not(s.acked),
    not(s.failed),
    s.attempts == attemptLimit,
    s' = { ...s, failed: true, pending: false, sent: false, gap: false },
  }

  action duplicateAck = all {
    s.acked,
    s' = s,
  }

  action idle = s' = s

  action step = any { send, ack, gap, resend, exhaust, duplicateAck, idle }

  val attemptsBounded = s.attempts <= attemptLimit
  val ackTerminates = s.acked implies (not(s.pending) and not(s.gap) and not(s.failed))
  val gapNeedsSent = s.gap implies (s.sent and not(s.acked))
  val failedVisible = s.failed implies (s.attempts == attemptLimit and not(s.acked))
  val ackedNeedsAttempt = s.acked implies s.attempts > 0
  val safe = attemptsBounded and ackTerminates and gapNeedsSent and failedVisible and ackedNeedsAttempt
}
