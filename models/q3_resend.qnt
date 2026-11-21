// Q3: one unconfirmed message; attempts are bounded, and exhaustion is visible.
// The nondeterministic success abstracts a network attempt; the model does not
// claim eventual delivery under an unfair network.
module q3Resend {
  pure val attemptLimit = 3

  type State = {
    pending: bool,
    delivered: bool,
    online: bool,
    attempts: int,
    notified: bool,
  }

  var s: State

  action init = s' = { pending: true, delivered: false, online: true, attempts: 0, notified: false }

  action attempt = all {
    s.pending,
    not(s.delivered),
    s.online,
    s.attempts < attemptLimit,
    nondet success = Set(false, true).oneOf()
    s' = {
      ...s,
      attempts: s.attempts + 1,
      delivered: success,
      pending: not(success),
      notified: (not(success) and s.attempts + 1 == attemptLimit),
    },
  }

  action disconnect = all {
    s.online,
    s' = { ...s, online: false },
  }

  action reconnect = all {
    not(s.online),
    s' = { ...s, online: true },
  }

  action idle = s' = s
  action step = any { attempt, disconnect, reconnect, idle }

  val attemptsBounded = s.attempts <= attemptLimit
  val exhaustionNotified = (s.attempts == attemptLimit and not(s.delivered)) implies s.notified
  val deliveredNeedsAttempt = s.delivered implies s.attempts > 0
  val safe = attemptsBounded and exhaustionNotified and deliveredNeedsAttempt
}
