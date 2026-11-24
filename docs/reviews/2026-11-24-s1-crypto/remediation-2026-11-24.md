# Internal review remediation status

Status: internal, non-signing. The external cryptographic gate is **still open**.
Source baseline: `fce56e8` (fix commit on top of `6884926`; packet commit `b3871bd`).
The original findings are in `internal-review-2026-11-24.md`; this file records only what changed.

## Closed findings

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

- `./check.sh`: PASS (588.63 s) on the `fce56e8` source tree.
- `./mill core.test`: 145/145.
- `./mill store.test`: 198/198.
- `./mill server.test`: 399/399.
- `./mill server.e2e`: 17/17; E2E#8 1k = 4,622 ms; PERF#10K = 34,006 ms.
- desktop gate: cold start p50 = 1,544 ms, p95 = 1,646 ms, idle 744.5 MB; UI smoke delivered=100, received=100, reciprocal=1.
