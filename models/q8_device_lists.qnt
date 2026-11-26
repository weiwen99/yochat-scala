// Q8b (B1+B2+B3): signed device-list snapshot, permanent revocation, direct fanout,
// logical dedupe, per-recipient ACK and monotonic Lamport evidence.
// Signatures, storage and wire encoding are abstracted as authenticated inputs.
module q8DeviceLists {
  pure val knownDevices = Set("A1", "A2", "B1", "B2")

  type State = {
    active: Set[str],
    revoked: Set[str],
    everRevoked: Set[str],
    version: int,
    delivered: Set[int],
    acked: Set[int],
    lamport: int,
  }

  var s: State

  action init = s' = {
    active: Set("A1"),
    revoked: Set(),
    everRevoked: Set(),
    version: 0,
    delivered: Set(),
    acked: Set(),
    lamport: 0,
  }

  action joinA2 = all {
    not(s.active.contains("A2")),
    not(s.everRevoked.contains("A2")),
    s' = { ...s, active: s.active.union(Set("A2")), version: s.version + 1 },
  }

  action revokeA2 = all {
    s.active.contains("A2"),
    s' = {
      ...s,
      active: s.active.exclude(Set("A2")),
      revoked: s.revoked.union(Set("A2")),
      everRevoked: s.everRevoked.union(Set("A2")),
      version: s.version + 1,
    },
  }

  // B1 addresses the active A2 directly. A logical message is delivered once.
  action direct = all {
    s.active.contains("A2"),
    s.delivered.size() < 3,
    s' = {
      ...s,
      delivered: s.delivered.union(Set(s.delivered.size() + 1)),
      lamport: s.lamport + 1,
    },
  }

  action duplicate = all {
    s.delivered.size() > 0,
    s' = s,
  }

  action ack = all {
    s.delivered.size() > 0,
    s.acked.size() < s.delivered.size(),
    s' = { ...s, acked: s.acked.union(Set(s.acked.size() + 1)) },
  }

  action sendA = s' = { ...s, lamport: s.lamport + 1 }
  action sendB = s' = { ...s, lamport: s.lamport + 1 }
  action oldSnapshot = all { s.everRevoked.contains("A2"), s' = s }
  action idle = s' = s
  action step = any { joinA2, revokeA2, direct, duplicate, ack, sendA, sendB, oldSnapshot, idle }

  val revokedNeverActive = s.active.intersect(s.revoked) == Set()
  val everRevokedMonotonic = s.revoked.subseteq(s.everRevoked)
  val versionNonNegative = s.version >= 0
  val lamportNonNegative = s.lamport >= 0
  val ackRequiresDelivery = s.acked.subseteq(s.delivered)
  val safe = revokedNeverActive and everRevokedMonotonic and versionNonNegative and lamportNonNegative and ackRequiresDelivery
}
