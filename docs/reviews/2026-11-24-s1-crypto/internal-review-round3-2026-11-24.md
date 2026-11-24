# Internal review round 3 — verification of the B1-write / B3 / M7 fixes

**Status: internal, non-signing. The external cryptographic gate is still open.** This is a re-review
of `bb152f8`, not an external audit.

| Item | Value |
| --- | --- |
| Source under review | `bb152f8` (round-3 fix commit; base `6884926`) |
| Packet | `0341625`, plus this round-3 note |
| Manifest | 182 hash entries / 185 lines, verified `OK` before this note |
| Suites | `core.test`, `store.test`, `server.test` SUCCESS; new `InternalReviewSpec` 7/7, `InternalReviewStoreSpec` 2/2, `HttpApiSpec` 3/3, `AppEffectExecutorSpec` 4/4 |
| E2E | E2E#13 PASS, E2E#14 PASS, E2E#17 PASS; E2E#8 isolated 4,234 ms and 4,262 ms (gate <5 s); one sequential run 8,010 ms (load flake); PERF#10K 32,039 ms |
| Not run | full `./check.sh`, the desktop/Electron gate, the Quint models |

## 0. Verdict

The three round-3 fixes are real and their named tests pass: **B1-write, B3 and the M7 fatal are
closed at the level they were reported**. However, a re-read of the same cutover path found two
defects that the round-3 tests do not cover:

- **R3-N1 (BLOCKER)**: after the last old outgoing is rebound, `RebindOutgoing` deletes the old
  session row but leaves the old session in `AppState`. A late old-session Data that arrives
  afterwards is decrypted and then fails `PersistInbound` with `NotFound`, which sets the runtime
  fatal.
- **R3-N2 (MAJOR, release-blocking)**: on the responder, that same rebind retirement uses
  `SessionRetirement.InitiatorAck` even though the new session still has
  `retireOldOnFirstData = true`. The responder's old session is retired before the first
  authenticated new-session Data, violating ADR-0007 G12 / Plan 0007 §11.1. With a lost
  ConfirmAck and a retry-budget abort, the responder is on the new session while the initiator
  reverts to the old one.

**Do not send this packet to an external reviewer until R3-N1 and R3-N2 are fixed.** M8/M9 also
remain open, and no human external reviewer has signed.

## 1. Round-3 closures verified

| Item | Result |
| --- | --- |
| B1-write | **closed**: all mutating `AppService` methods use `withUnlockedEither` (`AppService.scala:191-270,302-330`); the pure kernel no-ops mutating events while locked (`AppStep.scala:15-16,732-740`); `HttpApiSpec` locked invitation POST 401 and `InternalReviewSpec` B1-write pass. |
| B3 | **closed at the reported level**: `RekeyTimerStage.Confirm` and `RekeyConfirmRetry` implement a fresh-nonce retry, Online-only recovery, timer-guarded duplicate recovery, and an Initiator abort timer; `InternalReviewSpec` covers immediate recovery, duplicate recovery, retry and Offline no-send. One retry then abort meets the "single lost Confirm" acceptance. |
| M7 fatal | **closed**: `executeAll` stops the fold on a non-empty skipped set (`AppEffectExecutor.scala:88-92`); the combined `PersistRebound(Skipped) + PersistAttempt` test returns `Right(Vector(id))`. |
| M4 test | now probative: a fresh epoch/id is created during cooldown and rejected. |
| M6 test | now probative: two accepts run in `IO.both`; exactly one succeeds and the contact stays `Confirmed`. |
| B1 read / B2 / M1 / M2 / M3 / M5 | remain closed from round 2; suites pass. |

## 2. R3-N1 — old session deleted in the store but kept in `AppState`

- Severity: BLOCKER
- Area: cutover / rebind / runtime persistence
- Source: `core/src/yochat/core/app/AppStep.scala:184-229` (`RebindOutgoing`; the retirement effect
  at `:212-227` does not remove `message.sessionId` from `pumped.sessions`),
  `server/src/yochat/server/AppRuntime.scala:415-430` (state publish after the durable effects),
  `server/src/yochat/server/AppEffectExecutor.scala:212-250` (`persistInbound` loads the session;
  `NotFound` becomes `StoreFailed`), `:219-221`.
- Design/spec: ADR-0007 §4 (old session deletion after cutover) and the M2/A6 rule that the old
  session is kept only to decrypt in-flight frames. A session whose store row is gone must not be a
  live receive target.
- Problem: when the last rebindable old outgoing is migrated, `RebindOutgoing` emits
  `PersistHandshake(... retire old ...)` but returns `pumped` with the old `AppSession` still in
  `sessions`. The runtime executes the durable retirement (deleting the store row) and then sets
  that `pumped` state. A late old-session Data arrives, `InboundFrame` finds the stale app session,
  `Ratchet.decrypt` succeeds (ReadOnly is allowed to receive), `receiveSuccess` decrypts and calls
  `PersistInbound`; `store.loadSession(old)` is `NotFound`, the executor returns
  `Left(StoreFailed)`, and `applyEvent` sets fatal.
- Preconditions / exploit: the initiator or responder has an old outgoing at cutover; the last
  rebind migrates it; the peer's late old-session Data (the exact traffic the old session is kept
  for) arrives after the retirement. No attacker action is needed beyond the peer sending a frame
  it was already allowed to send.
- Impact: the runtime stops; subsequent local and remote traffic is not processed until restart.
  This is worse than the pre-fix divergence.
- Reproduced (probes deleted after the run):
  - `probe-rebind-init: oldInState=true retire=Vector((<old>,InitiatorAck))`
  - `probe-rebind-resp: oldInState=true newRetireOnFirstData=Some(true) retire=Vector((<old>,InitiatorAck))`
  - `probe-late-old: Left(StoreFailed:NotFound)`
- Fix: when the retirement effect is emitted, return a state with
  `sessions - message.sessionId` (and the old session's timers), so the runtime never holds a
  session whose row it has deleted. Alternatively, have the runtime remove the old session when the
  `PersistHandshake` retirement succeeds. Add a test: rebind the last old message, assert the old
  session is absent from `AppState`, then deliver a late old Data and assert it is dropped without
  a fatal.
- Closure evidence: the probe becomes a product test; a late old frame after retirement is dropped
  (or is impossible because the session is gone), not a `StoreFailed`.

## 3. R3-N2 — responder retires with InitiatorAck before the first new Data

- Severity: MAJOR (release-blocking; G12)
- Area: cutover / rekey / role evidence
- Source: `core/src/yochat/core/app/AppStep.scala:212-227` (`RebindOutgoing` hardcodes
  `SessionRetirement.InitiatorAck`), `:639-670` (`RekeyConfirmReceived` sets
  `retireOldOnFirstData = true` and emits `RebindPending` for the old session),
  `server/src/yochat/server/AppEffectExecutor.scala:318-323` (InitiatorAck →
  `CutoverRole.Initiator` with `confirmAckReceived = true`),
  `store/src/yochat/store/SessionRepository.scala:341-346` (`cutoverReady` trusts the caller's
  evidence).
- Design/spec: ADR-0007 §4/G12 and Plan 0007 §11.1: the responder may delete the old session only
  after the first authenticated Data on the new session; a local Active state or a ConfirmAck is not
  the responder's evidence.
- Problem: the responder's `RekeyConfirmReceived` emits `RebindPending` immediately. If the old
  session has outgoing, the rebind loop migrates it and, when it is the last one, emits
  `InitiatorAck` retirement. The responder has not received any new-session Data; the store deletes
  the old session anyway. The `retireOldOnFirstData` flag is ignored by this path.
- Preconditions / exploit: responder has a pending old message; the initiator's Confirm is
  processed; the responder's rebind completes before the initiator sends any new-session Data. If
  the initiator's ConfirmAck is lost and the B3 retry budget is exhausted, the initiator aborts to
  the old session while the responder has deleted it.
- Impact: desync; the responder drops the initiator's old-session frames and the initiator drops the
  responder's new-session frames until a new rekey/re-pair. The store's G12 evidence check is
  bypassed by a caller assertion.
- Reproduced: `probe-rebind-resp: oldInState=true newRetireOnFirstData=Some(true)
  retire=Vector((<old>,InitiatorAck))`.
- Fix: do not emit a retirement from `RebindOutgoing` when the target new session has
  `retireOldOnFirstData` (or when the old session's `rekey.role == Responder`); leave the old
  session in place until `receiveSuccess` reaches the first Data and uses
  `PersistInboundAndRetire` with `ResponderFirstData`. For the initiator, keep `InitiatorAck` and
  combine it with the R3-N1 state removal. Add a responder old-outgoing test that asserts the old
  session is still present and not retired before the first Data, then retired after it.
- Closure evidence: the responder's old row survives the rebind and is deleted only by the first
  new-session Data; a lost-Ack/abort scenario leaves both sides on the old session.

## 4. Residual minor / evidence

- **M7 skipped cleanup**: when a rebind is `Skipped`, `executeAll` stops and the old session is not
  retired even if no rebindable outgoing remains; it stays in `AppState` and the store. The fatal
  is fixed, but the old session/key retention is a cleanup gap (Plan 0007 §11.3/A6). Re-evaluate
  retirement after removing skipped ids.
- **B3 runtime wiring**: the `InternalReviewSpec` calls `RekeyConfirmRetry` directly. The production
  path goes through `AppRuntime`'s `TimerFired(Confirm) → RekeyConfirmRetry` mapping
  (`AppRuntime.scala:382-397`) and `recoverRekeys` (`:535-560`); neither is directly tested. E2E#14
  exercises auto-rebuild but not a lost Confirm. Add a runtime test that enqueues the timer and
  observes one fresh Confirm.
- **B1 safety HTTP**: the new HTTP test covers a locked invitation POST; the safety endpoint
  (`POST /api/contacts/:id/safety`) is covered only at the core level. `withUnlockedEither` is
  applied in `AppService.verifySafety`; add the HTTP assertion.
- **B1 read linearization**: `messagesJson` checks `unlockedState` and then reads messages from the
  store. The check is the authorization point; a lock after it can still return rows read in the
  same request. This is a narrow race, not the original two-state-read bug; document the
  linearization or read the store behind the same lock.
- **1k gate flake**: one E2E#8 run after E2E#13/#14/#17 took 8,010 ms and failed; two isolated runs
  took 4,234/4,262 ms and passed. This is the repo's "landing-point drift = load flake" class, not
  a fixed regression, but the margin is ~15%; record the run conditions and rerun on a quiet host
  before the external handoff.
- **M8/M9** remain open as recorded; M9 still needs the ADR/implementation decision.

## 5. Gate

The external cryptographic gate is **open**. Even after R3-N1/R3-N2, the external reviewer must be a
human or institution with no conflict of interest, verify the manifest, reproduce the evidence,
answer `open-questions.md`, and sign `reviewer-checklist.md`. No AI output, including this note,
can be that signature.
