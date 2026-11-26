// Q8a (B1): signed device-list snapshot and permanent revocation.
// A contact can only move to a newer list; a revoked device never becomes
// active again. Signatures, storage and wire encoding are abstracted.
module q8DeviceLists {
  pure val knownDevices = Set("A1", "A2")

  type State = {
    active: Set[str],
    revoked: Set[str],
    everRevoked: Set[str],
    version: int,
  }

  var s: State

  action init = s' = {
    active: Set("A1"),
    revoked: Set(),
    everRevoked: Set(),
    version: 0,
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

  // An old snapshot cannot resurrect a revoked device; it is a no-op here.
  action oldSnapshot = all {
    s.everRevoked.contains("A2"),
    s' = s,
  }

  action duplicate = s' = s
  action idle = s' = s
  action step = any { joinA2, revokeA2, oldSnapshot, duplicate, idle }

  val revokedNeverActive = s.active.intersect(s.revoked) == Set()
  val everRevokedMonotonic = s.revoked.subseteq(s.everRevoked)
  val versionNonNegative = s.version >= 0
  val safe = revokedNeverActive and everRevokedMonotonic and versionNonNegative
}
