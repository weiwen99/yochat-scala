# Internal review round 2 — fixes for B1–B3 / M1–M9

**Status: internal, non-signing. The external cryptographic gate is still open.** This is a re-review
of the remediation, not an external audit and not a verdict on S1.

| Item | Value |
| --- | --- |
| Source under review | `fce56e8` (fix commit; packet doc `43c6830`; base `6884926`) |
| Packet | `43c6830` (remediation record), plus this round-2 note |
| Manifest | 181 entries at the start of this round (182 after adding this report), verified `OK` |
| Method | read the fix diff; ran core/store/server suites; ran E2E#8, #14, #15, #17, PERF#10K; four scratch probes (deleted) |
| Suites | `core.test` SUCCESS, `store.test` SUCCESS, `server.test` SUCCESS; the new `InternalReviewSpec` 6/6, `InternalReviewStoreSpec` 2/2, `HttpApiSpec` 3/3, `AppEffectExecutorSpec` 4/4 |
| E2E | E2E#8 3,813 ms (1k, gate <5 s); E2E#14 PASS; E2E#15 PASS; E2E#17 PASS; PERF#10K 30,501 ms (gate <60 s) |
| Not run | full `./check.sh`, the desktop/Electron gate, the Quint models (unchanged by the fix diff) |

## 0. Verdict

**Not ready for external review.** The remediation is real and most of the blockers are closed, but
three items are **not** closed:

- **B1-write (BLOCKER)**: idle lock still does not gate the mutating HTTP endpoints. A locked app
  accepts `POST /api/contacts/:id/safety` and flips `safetyVerified`, and accepts invitation/reply/
  alias mutations. The read paths (B1-read) are fixed.
- **B3 (BLOCKER)**: the crash window is handled (immediate Confirm after restore), but there is
  **no Confirm retry**. The same 5 s timer still aborts and deletes the Pending new session; the
  recovery path sends while Offline and can double-send on Unlock + reconnect.
- **M7 (MAJOR, release-blocking until the combined case passes)**: `executeAll` returns `Skipped`
  but **continues executing the remaining durable effects**. A `PersistAttempt` derived from the
  skipped candidate fails with `WriteConflict`, and the runtime marks fatal. The committed M7 test
  passes only the single `PersistRebound` effect.

The rest: **B2, M1, M2, M3, M5 are closed** with tests; **B1-read** is closed; **M4 and M6 are fixed
by code but their regression tests are not probative**; **M8 and M9 remain open** as recorded.

## 1. Finding-by-finding

| ID | Round-2 status | Evidence / gap |
| --- | --- | --- |
| B1-read | **CLOSED** | `AppService.stateJson/sessionJson/messagesJson` gate on `state.lock.isUnlocked` (`AppService.scala:123-170`); `HttpApiSpec` idle test passes (`server/test/.../HttpApiSpec.scala`, "idle auto-lock gates…"). |
| B1-write | **OPEN — BLOCKER** | `createInvitation` (`AppService.scala:191`), `receiveInvitation` (`:205`), `acceptInvitation` (`:213`), `rejectInvitation` (`:228`), `setAlias` (`:236`), `verifySafety` (`:248`) use `withRunningEither` (sessionRef only). Core handlers `SafetyVerified` (`AppStep.scala:275`), `SetContactAlias` (`:287`), `InvitationCreated` (`:294`), `InvitationReceived` (`:307`), `ReplyCreated` (`:320`), `RejectInvitation` (`:468`) do not check the lock. Probe: locked state + `SafetyVerified` → `safety=true persistSafety=true`; + `InvitationCreated` → `persistInvite=true`. |
| B2 | **CLOSED** | `rekeyTimers` filters via `rekeyTimerAllowed` (`AppStep.scala:1080-1090`); `abortRekey` ignores an Active target (`:1110-1120`); `deletePendingProgram` requires `Pending` (`SessionRepository.scala:60-75`). `InternalReviewSpec` B2 and `InternalReviewStoreSpec` B2 pass. |
| B3 | **OPEN — BLOCKER** | `RecoverRekey` sends one fresh Confirm (`AppStep.scala:108-140`) and arms `TimerId.Rekey`; on fire, `abortRekey` deletes the Pending new session. No Confirm retry exists. Probe: after `RecoverRekey`, fire the timer → `newStillPresent=false oldPhase=Active retried=false`. `RecoverRekey` is accepted while `ConnectionState.Offline` (probe `sent=true`); `recoverRekeys` runs on both Unlock and Connection(true) with no once-only marker (`AppRuntime.scala:535-560`). The committed B3 test (`InternalReviewSpec.scala:88-104`) only asserts the immediate send. |
| M1 | **CLOSED** | `acknowledge` bounds the ACK to attempted messages and the highest attempted seq (`AppStep.scala:820-850`); `InternalReviewSpec` M1 passes. |
| M2 | **CLOSED** | `Ratchet.scala:102` permits DataAck/Gap on `ReadOnly`; `RekeyConfirmReceived` emits `RebindPending` for the old session (`AppStep.scala:590-610`); `InternalReviewSpec` M2 and E2E#17 pass. |
| M3 | **CLOSED** | `hasRebindableOutgoing` excludes `Failed` (`AppStep.scala:660-665`); `retirementCandidates` uses it; `InternalReviewSpec` M3 passes. |
| M4 | **fixed, test not probative** | Cooldown check at `AppStep.scala:515-520`; but the test reuses the same `request` epoch, which `Rekey.respond` would reject via `lastRekeyEpoch` even without the cooldown. A fresh epoch during cooldown is untested. After 60 s a peer can re-block for another 5 s (bounded duty cycle, not permanent). |
| M5 | **CLOSED** | Tombstone reads in `SessionRepository.insert`/`writeProgram` (`SessionRepository.scala:28-60,294-320`) and `ContactRepository.insert`/`upsertProgram` (`ContactRepository.scala:20-40,180-190`); `InternalReviewStoreSpec` M5 passes. |
| M6 | **fixed, test not probative** | `acceptInvitation` holds the service mutex (`AppService.scala:213-225`); the committed test calls accept **sequentially**, which the pre-fix runtime already rejected via `invitation-not-pending`. A concurrent two-fiber accept is untested. |
| M7 | **OPEN — MAJOR** | `executeAll` collects `Skipped` but continues the fold (`AppEffectExecutor.scala:88-110`); `PersistAttempt` after a skipped rebind hits no row → `WriteConflict` → `Left` → `applyEvent` fatal (`AppRuntime.scala:415-430`). Probe: `single=Right(Vector(1)) combined=Left(StoreFailed:WriteConflict)`. The committed test passes only the single effect. The `retirement` `PersistHandshake` after `Skipped` is also executed, contrary to "Skipped writes nothing". |
| M8 | **OPEN** | unchanged; acknowledged. |
| M9 | **OPEN** | `storage.md` now describes the real `safeStorage` → renderer → `/api/unlock` body path; ADR-0007 still specifies the one-shot pipe. Owner decision required. |

## 2. Residual findings in detail

### B1-write — the idle lock still accepts security-state mutations

- Severity: BLOCKER
- Source: `server/src/yochat/server/AppService.scala:191-260` (all mutating methods), `:308-310`
  (`requireUnlocked` exists but is used only by the read paths);
  `core/src/yochat/core/app/AppStep.scala:275-330,468-475` (handlers lack a lock guard);
  `server/src/yochat/server/HttpApi.scala` routes for invitations/contacts.
- Design/spec: Plan 0006 §4 #13 and the S1 invariant "应用锁、空闲锁定"; A5.
- Problem: after the idle timer fires, `sessionRef` remains `Some`, so every endpoint that uses
  `withRunningEither` is still callable. The read paths were fixed; the mutating paths were not.
  `AppStep` does not check the lock for `SafetyVerified`, `SetContactAlias`, `InvitationCreated`,
  `InvitationReceived`, `RejectInvitation`, or `ReplyCreated`.
- Preconditions / exploit: after the idle deadline, any local process sends
  `POST /api/contacts/{userPub}/safety`; `AppService.verifySafety` submits the event and the core
  flips `safetyVerified` (probe). `POST /api/invitations` persists a new invitation; `acceptInvitation`
  can create/send a reply.
- Impact: the safety gate can be marked verified without the out-of-band comparison; invitations and
  replies can be created while the UI reports locked. This is the same boundary as B1, on the write
  side.
- Reproduced: `probe-b1-write: locked=true safety=true persistSafety=true persistInvite=true invitations=1`.
- Fix: call `requireUnlocked` in every mutating `AppService` method (or one route-level guard applied
  to all non-unlock/lock routes), **and** add the lock guard in the core handlers so another caller
  cannot bypass the HTTP check. Add an API test for the safety endpoint and invitations after idle.
- Closure evidence: the probe becomes a product test; `POST /api/contacts/*/safety` is 401 and the
  contact stays unverified.

### B3 — no Confirm retry; recovery is offline/double-send capable

- Severity: BLOCKER
- Source: `core/src/yochat/core/app/AppStep.scala:108-140` (`RecoverRekey`), `:180-181`
  (`TimerFired` → `abortRekey`), `:1110-1140` (`abortRekey`); `server/src/yochat/server/AppRuntime.scala:535-560`
  (`recoverRekeys` checks lock only).
- Design/spec: Plan 0007 §10.4/§11.2/§12.2/§13.2: a Confirm retry timer, fresh nonce/`n`, persist
  before send, only when Online+Unlocked, once at readiness, then bounded failure.
- Problem: the only timer is the abort timer. After the immediate recovery send, a lost Confirm or
  Ack is not retried; at 5 s the old session is reactivated and the Pending new session deleted. The
  event also sends while Offline, and `recoverRekeys` can enqueue it on both Unlock and
  `Connection(true)` with no "already sent" marker.
- Preconditions / exploit: one lost `RekeyConfirm`/`RekeyConfirmAck` on a healthy link after an
  Accept; or Unlock while Offline followed by a reconnect. The responder may already be Active.
- Impact: the cutover is discarded and the peer is on a session the local side removed; the
  relationship can desync until a new rekey/re-pair. This is the original B3 failure, narrowed to
  packet loss rather than the crash window.
- Reproduced: `probe-b3: newStillPresent=false oldPhase=Active retried=false`;
  `probe-b3-offline: sent=true`.
- Fix: introduce a Confirm-retry action (new timer stage or a `ConfirmRetry` timer) that re-runs
  `Rekey.confirm` with a fresh nonce/`n`, persists, sends, and counts against the existing budget;
  abort only after the retry budget. Gate `RecoverRekey` on `connection == Online`; add a once-only
  recovery marker or check the timer/pending state so Unlock+Connection does not double-send.
- Closure evidence: a virtual-clock test: send Confirm, drop it, fire the retry timer, observe a
  fresh Confirm, ACK, original row delivered once; permanent loss ends in a bounded, visible
  `rekey-failed`; no send while Offline.

### M7 — a skipped rebind can still produce a fatal

- Severity: MAJOR (release-blocking until the combined test passes; the design treats the skipped
  case as reachable)
- Source: `server/src/yochat/server/AppEffectExecutor.scala:88-110` (`executeAll` continues after
  `Skipped`); `core/src/yochat/core/app/AppStep.scala:150-175` (`RebindOutgoing` returns
  `PersistRebound`, retirement, and pump effects in one vector); `server/src/yochat/server/AppRuntime.scala:415-430`
  (fatal on `Left`).
- Design/spec: Plan 0007 §10.1/§11.3: `Skipped` writes nothing, does not advance `ns`, does not
  increase attempts, does not send.
- Problem: `executeAll` records the skipped id but keeps folding. The next durable effect is
  `PersistAttempt` for the same message under the new session/seq; the row is still under the old
  session, so `setAttemptsByKey` affects 0 rows and returns `WriteConflict`. The runtime sets fatal.
  The `retirement` `PersistHandshake` after the skip is also executed (a write the design forbids).
- Preconditions / exploit: an in-memory non-terminal item whose row is already `Delivered`/`Failed`
  (the exact case the M7 test constructs). `RebindPending` is triggered on cutover/reconnect; when
  Online, `pump` adds `PersistAttempt`.
- Impact: the runtime stops; the other queued messages are not sent; a restart may recover, but the
  session is stuck until then. This is worse than the pre-fix silent divergence.
- Reproduced: `probe-m7: single=Right(Vector(1)) combined=Left(StoreFailed:WriteConflict)`.
- Fix: in `executeAll`, return immediately on `Skipped` and do not execute the remaining effects;
  `applyEvent` already removes the skipped id and enqueues `Pump`. Ensure the retirement is
  re-derived on the next safe event, not executed from the skipped candidate. Add the combined test
  (`PersistRebound(Skipped)` + `PersistAttempt`) and an `applyEvent` test that the runtime stays
  alive and the other queued message sends.
- Closure evidence: the combined probe returns `Right(Vector(id))`; no `WriteConflict`; no send of
  the skipped wire; a following `Pump` delivers the other queued message.

### Minor / evidence

- **B1 TOCTOU**: `stateJson`/`messagesJson` call `requireUnlocked` and then read `runtime.state`
  again (`AppService.scala:123-170`). A lock between the two reads can still return data. Read once,
  gate on that value, and build from it.
- **M4 test**: `InternalReviewSpec.scala:168-191` reuses the same `request`; `lastRekeyEpoch` alone
  rejects it. Add a fresh epoch/id during cooldown.
- **M6 test**: `HttpApiSpec` M6 calls `acceptInvitation` sequentially; the pre-fix code already
  rejected the second call because the invitation was no longer `AwaitingReply`. Add two concurrent
  fibers and assert exactly one success, one 401/400, contact `Confirmed`.
- **Test counts**: `evidence.md:31-34` and `remediation-2026-11-24.md:31-33` report
  `145/198/399` as test counts. Those are Mill task ids: `./mill core.test.testOnly
  yochat.core.app.InternalReviewSpec` prints `145/145, SUCCESS` for a single class. Report per-suite
  MUnit totals or the real number of tests; do not use the task index as a receipt.
- **Independent KAT**: still no cross-implementation/hand-derived ratchet/rekey vector (round-1
  minor). The external reviewer must re-derive.
- **Performance**: E2E#8 3,813 ms (gate 5 s) and PERF#10K 30,501 ms (gate 60 s) pass; the 1k margin
  is thinner than the original packet receipt. Record the machine/load and run the gate on a quiet
  host before sending.
- **M8/M9**: unchanged; M9 now has an accurate `storage.md` description but ADR-0007 still specifies
  the pipe.

## 3. What the external reviewer must still do

The external gate is open. After B1-write, B3, and M7 are fixed and the M4/M6 tests are strengthened:
freeze a new source commit and manifest, rerun `./check.sh`, `./mill server.e2e`, the 1k/10k gates,
and the new regressions; then commission a human/institution with no conflict of interest to verify
the manifest, reproduce the evidence, answer `open-questions.md`, and sign `reviewer-checklist.md`.

No AI output, including this round-2 note and the original internal review, can be that signature.
