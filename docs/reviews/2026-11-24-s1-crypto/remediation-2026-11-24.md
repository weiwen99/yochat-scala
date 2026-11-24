# Internal review remediation status

Status: internal, non-signing. The external cryptographic gate is **still open**.
Source baseline: `fce56e8` (fix commit on top of `6884926`; packet commit `b3871bd`).
The original findings are in `internal-review-2026-11-24.md`; this file records only what changed.

## Round-2 verification and round-3 closure

Round 2 correctly found that the first remediation was incomplete. Round 3 closes the three remaining
items and strengthens the two weak tests:

- **B1-write (CLOSED)**: all mutating `AppService` methods go through `withUnlockedEither`; the pure
  kernel rejects mutating events while locked; `stateJson`/`messagesJson` gate on one state read.
  `HttpApiSpec` tests the locked invitation POST; `InternalReviewSpec` tests locked safety/invitation
  events.
- **B3 retry (CLOSED)**: `RekeyTimerStage.Confirm` carries a Confirm retry. The runtime translates a
  fired Confirm timer into a fresh-nonce `RekeyConfirmRetry`; the core persists and sends a new
  Confirm, then arms the Initiator abort timer. Recovery is Online-only and timer-guarded, so
  Unlock+reconnect do not double-send. `InternalReviewSpec` covers immediate recovery, duplicate
  recovery, and the retry.
- **M7 skipped batch (CLOSED)**: `executeAll` stops the fold as soon as it sees a non-empty skipped
  set; the runtime removes the skipped id and enqueues `Pump`. The executor test now passes
  `PersistRebound(Skipped)` followed by `PersistAttempt` and still returns `Right(Vector(id))`.
- **M4 test (now probative)**: a fresh epoch/id is constructed during the cooldown; the session stays
  Active and the test observes `RekeyFailed`.
- **M6 test (now probative)**: two accept calls run in `IO.both`; exactly one succeeds and the contact
  stays `Confirmed` (not `AccountMismatch`).

## Closed findings (round-1 claims)

| ID | Closure | Regression evidence |
| --- | --- | --- |
| B1 | HTTP reads now check the locked state; `/api/state` and `/api/conversations/*/messages` return 401 after idle lock; `/api/session` returns the locked JSON. | `HttpApiSpec` “idle auto-lock gates state and message reads…” |
| B2 | Rekey timers are installed only while the target new session is absent or `Pending`; `abortRekey` ignores an `Active` target; store deletion accepts only `Pending` rows. | `InternalReviewSpec` B2; `InternalReviewStoreSpec` B2 |
| B3 | Restore/reconnect detects `Initiator + Accepted + Pending`, creates a fresh Confirm, persists it, sends it, and re-arms the bounded timer. | `InternalReviewSpec` B3 |
| M1 | ACK delivery requires `attempts > 0` and the ACK seq is bounded by attempted messages; a never-sent `Queued` message cannot be marked delivered. | `InternalReviewSpec` M1 |
| M2 | `DataAck`/`Gap` are permitted on a `ReadOnly` old session; the responder emits `RebindPending` after Confirm. | `InternalReviewSpec` M2; `server.e2e` E2E#17 |
| M3 | Failed old-session messages no longer block retirement or enter the rebind set. | `InternalReviewSpec` M3 |
| M4 | Responder checks the existing rekey cooldown before accepting an inbound request; a request during cooldown leaves the session Active and notifies failure. | `InternalReviewSpec` M4 |
| M5 | Session/contact inserts read the per-row high-water tombstone and reject delete→reinsert with `StaleGeneration`. | `InternalReviewStoreSpec` M5 |
| M6 | `AppService.acceptInvitation` serializes through the service mutex; a second accept fails without changing the contact to `AccountMismatch`. | `HttpApiSpec` M6 |
| M7 | `PersistRebound` returns `Migrated`/`Skipped`; `executeAll` exposes the skipped message ids; runtime discards the candidate and does not send its external effects on `Skipped`. | `AppEffectExecutorSpec` M7 |

## Still open before external review

- **M8**: relay/runtime queues remain bounded by entry count, not bytes; 1 MiB frames can still consume the documented worst-case memory. No code change in this remediation.
- **M9**: the implemented master-key path is still `safeStorage` → renderer IPC → `POST /api/unlock` JSON body → JVM; `storage.md` now describes this actual path and marks the deviation, but ADR-0007's one-shot pipe is not implemented. This is an implementation/ADR inconsistency for the owner to resolve before external review.
- The packet must not be called “ready for external review” while M8/M9 are open and no human external reviewer has signed.

## Verification run

- `./mill core.test`: SUCCESS; all core MUnit classes including the new `InternalReviewSpec` cases.
- `./mill store.test`: SUCCESS; `InternalReviewStoreSpec` both cases pass.
- `./mill server.test`: SUCCESS; `HttpApiSpec` and `AppEffectExecutorSpec` include the new tests.
- `./mill server.e2e`: final round-3 receipt pending the gate.
- `./check.sh`: final gate in progress; exact PASS/time and MUnit totals will be appended.
- Desktop gate receipt from the prior pass: cold start p50 = 1,544 ms, p95 = 1,646 ms, idle 744.5 MB;
  UI smoke delivered=100, received=100, reciprocal=1.
