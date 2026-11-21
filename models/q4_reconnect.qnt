// Q4: queued work survives offline periods; reconnect replays at most three
// times and delivery requires a connected attempt. The queue is one message.
module q4Reconnect {
  pure val replayLimit = 3

  type State = {
    pending: bool,
    connected: bool,
    attempts: int,
    delivered: bool,
  }

  var s: State

  action init = s' = { pending: true, connected: false, attempts: 0, delivered: false }

  action connect = all {
    not(s.connected),
    s.attempts < replayLimit,
    s' = {
      ...s,
      connected: true,
      attempts: if (s.pending) s.attempts + 1 else s.attempts,
    },
  }

  action disconnect = all {
    s.connected,
    s' = { ...s, connected: false },
  }

  action deliver = all {
    s.pending,
    s.connected,
    not(s.delivered),
    s' = { ...s, delivered: true, pending: false },
  }

  action idle = s' = s
  action step = any { connect, disconnect, deliver, idle }

  val replayBounded = s.attempts <= replayLimit
  val deliveredNeedsAttempt = s.delivered implies s.attempts > 0
  val queueNotLost = s.delivered implies not(s.pending)
  val safe = replayBounded and deliveredNeedsAttempt and queueNotLost

  temporal deliveryUnderFairReconnect =
    (eventually(always(s.connected)) and weakFair(deliver, s)).implies(eventually(s.delivered))
}
