// Q1: invitation/reply, account mismatch, rejection silence, safety gate.
// One relationship, one content send. Invitation crypto is abstracted; the
// model checks the application-level order and authorization rules.
module q1Relationship {
  type State = {
    invited: bool,
    replied: bool,
    responderConfirmed: bool,
    inviterConfirmed: bool,
    mismatch: bool,
    safetyVerified: bool,
    contentSent: bool,
    rejected: bool,
    expired: bool,
  }

  var s: State

  action init = s' = {
    invited: true,
    replied: false,
    responderConfirmed: false,
    inviterConfirmed: false,
    mismatch: false,
    safetyVerified: false,
    contentSent: false,
    rejected: false,
    expired: false,
  }

  action reply = all {
    s.invited,
    not(s.replied),
    not(s.rejected),
    not(s.expired),
    s' = { ...s, replied: true, responderConfirmed: true },
  }

  action replyMismatch = all {
    s.invited,
    not(s.replied),
    not(s.rejected),
    not(s.expired),
    s' = { ...s, replied: true, responderConfirmed: true, mismatch: true },
  }

  action verifySafety = all {
    s.replied,
    not(s.mismatch),
    not(s.safetyVerified),
    s' = { ...s, safetyVerified: true, inviterConfirmed: true },
  }

  action sendContent = all {
    s.replied,
    s.responderConfirmed,
    s.inviterConfirmed,
    s.safetyVerified,
    not(s.mismatch),
    not(s.contentSent),
    s' = { ...s, contentSent: true },
  }

  action reject = all {
    s.invited,
    not(s.replied),
    not(s.rejected),
    s' = { ...s, rejected: true },
  }

  action expire = all {
    s.invited,
    not(s.replied),
    not(s.expired),
    s' = { ...s, expired: true },
  }

  action idle = s' = s

  action step = any { reply, replyMismatch, verifySafety, sendContent, reject, expire, idle }

  val noContentBeforeSafety = s.contentSent implies s.safetyVerified
  val noContentOnMismatch = s.contentSent implies not(s.mismatch)
  val rejectedSilent = s.rejected implies not(s.contentSent)
  val replyOnce = s.responderConfirmed implies s.replied
  val confirmedNeedsReply = (s.responderConfirmed or s.inviterConfirmed) implies s.replied
  val noReplyAfterExpiry = s.expired implies not(s.replied)
  val safe = noContentBeforeSafety and noContentOnMismatch and rejectedSilent and replyOnce and confirmedNeedsReply and noReplyAfterExpiry
}
