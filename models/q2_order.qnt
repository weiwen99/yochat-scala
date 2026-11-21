// Q2: one author, three sequence numbers. Applied positions stay a prefix;
// out-of-order arrivals are buffered and flushed only when the hole is filled.
// The physical network is abstracted to independent receive actions.
module q2Order {
  pure val positions = Set(1, 2, 3)

  type State = {
    seen: Set[int],
    applied: Set[int],
    buffered: Set[int],
  }

  var s: State

  action init = s' = { seen: Set(), applied: Set(), buffered: Set() }

  pure def cursor(st: State): int =
    val pending = positions.filter(n => not(st.applied.contains(n)))
    pending.fold(4, (acc, n) => if (n < acc) n else acc)

  action receive1 = any {
    all {
      not(s.seen.contains(1)),
      cursor(s) == 1,
      val a1 = s.applied.union(Set(1))
      val a2 = if (s.buffered.contains(2)) a1.union(Set(2)) else a1
      val a3 = if (a2.contains(2) and s.buffered.contains(3)) a2.union(Set(3)) else a2
      s' = { ...s, seen: s.seen.union(Set(1)), applied: a3, buffered: s.buffered.exclude(a3) },
    },
    all {
      not(s.seen.contains(1)),
      cursor(s) > 1,
      s' = { ...s, seen: s.seen.union(Set(1)), buffered: s.buffered.union(Set(1)) },
    },
    all {
      not(s.seen.contains(1)),
      cursor(s) < 1,
      s' = { ...s, seen: s.seen.union(Set(1)) },
    },
  }

  action receive2 = any {
    all {
      not(s.seen.contains(2)),
      cursor(s) == 2,
      val a2 = s.applied.union(Set(2))
      val a3 = if (s.buffered.contains(3)) a2.union(Set(3)) else a2
      s' = { ...s, seen: s.seen.union(Set(2)), applied: a3, buffered: s.buffered.exclude(a3) },
    },
    all {
      not(s.seen.contains(2)),
      cursor(s) < 2,
      s' = { ...s, seen: s.seen.union(Set(2)), buffered: s.buffered.union(Set(2)) },
    },
    all {
      not(s.seen.contains(2)),
      cursor(s) > 2,
      s' = { ...s, seen: s.seen.union(Set(2)) },
    },
  }

  action receive3 = any {
    all {
      not(s.seen.contains(3)),
      cursor(s) == 3,
      s' = { ...s, seen: s.seen.union(Set(3)), applied: s.applied.union(Set(3)), buffered: s.buffered.exclude(Set(3)) },
    },
    all {
      not(s.seen.contains(3)),
      cursor(s) < 3,
      s' = { ...s, seen: s.seen.union(Set(3)), buffered: s.buffered.union(Set(3)) },
    },
    all {
      not(s.seen.contains(3)),
      cursor(s) > 3,
      s' = { ...s, seen: s.seen.union(Set(3)) },
    },
  }

  action idle = s' = s
  action step = any { receive1, receive2, receive3, idle }

  val noHoles = positions.forall(n => s.applied.contains(n) implies positions.filter(k => k < n).subseteq(s.applied))
  val appliedSeen = s.applied.subseteq(s.seen)
  val bufferNotApplied = s.buffered.intersect(s.applied) == Set()
  val safe = noHoles and appliedSeen and bufferNotApplied

  temporal allArriveApplied =
    (weakFair(receive1, s) and weakFair(receive2, s) and weakFair(receive3, s)).implies(eventually(s.applied == positions))
}
