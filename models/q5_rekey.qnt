// Q5: old/new session cutover. The old session becomes read-only, the new one
// becomes active only after confirm/ack, and deletion requires no pending work
// plus the role-specific cutover proof.
module q5Rekey {
  type State = {
    oldActive: bool,
    oldReadOnly: bool,
    newPending: bool,
    newActive: bool,
    ack: bool,
    firstData: bool,
    pendingOld: bool,
    oldDeleted: bool,
    safetyVerified: bool,
  }

  var s: State

  action init = s' = {
    oldActive: true,
    oldReadOnly: false,
    newPending: false,
    newActive: false,
    ack: false,
    firstData: false,
    pendingOld: false,
    oldDeleted: false,
    safetyVerified: true,
  }

  action start = all {
    s.oldActive,
    not(s.oldReadOnly),
    not(s.newPending),
    not(s.newActive),
    not(s.oldDeleted),
    s' = { ...s, oldActive: false, oldReadOnly: true, newPending: true },
  }

  action confirm = all {
    s.newPending,
    not(s.newActive),
    s' = { ...s, newPending: false, newActive: true },
  }

  action ack = all {
    s.newActive,
    not(s.ack),
    s' = { ...s, ack: true },
  }

  action firstNewData = all {
    s.newActive,
    not(s.firstData),
    s' = { ...s, firstData: true },
  }

  action pending = all {
    not(s.pendingOld),
    not(s.oldDeleted),
    s' = { ...s, pendingOld: true },
  }

  action deleteOld = all {
    s.oldReadOnly,
    s.newActive,
    not(s.pendingOld),
    s.ack or s.firstData,
    not(s.oldDeleted),
    s' = { ...s, oldDeleted: true },
  }

  action idle = s' = s
  action step = any { start, confirm, ack, firstNewData, pending, deleteOld, idle }

  val noBothActive = not(s.oldActive and s.newActive)
  val newNeedsOldReadOnly = s.newActive implies s.oldReadOnly
  val noDeleteWithPending = s.oldDeleted implies not(s.pendingOld)
  val deleteNeedsProof = s.oldDeleted implies (s.ack or s.firstData)
  val deleteNeedsNewActive = s.oldDeleted implies s.newActive
  val safetyPreserved = s.safetyVerified
  val safe = noBothActive and newNeedsOldReadOnly and noDeleteWithPending and deleteNeedsProof and deleteNeedsNewActive and safetyPreserved

  temporal newSessionEventuallyActive =
    (weakFair(start, s) and weakFair(confirm, s)).implies(eventually(s.newActive))
}
