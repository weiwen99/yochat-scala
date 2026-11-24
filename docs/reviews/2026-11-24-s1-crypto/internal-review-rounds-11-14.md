# Internal reviewer rounds 11–14

Status: internal review record. The cryptographic acceptance gate was **closed by owner decision** on
2026-11-25; no independent external signature is claimed. Source baseline: `e2001ad` on top of
`6884926`; this note records the added rounds 11–14 and their closures. The earlier rounds are in
`internal-review-rounds-4-10.md`.

## R11 findings and closure

| ID | Finding | Closure at `e2001ad` |
| --- | --- | --- |
| R11-1 | Ancestor outgoing could be rebound A→C after B retired. | `pointsToRetired` walks the direct-successor chain and durably fails every stranded ancestor message. Successful rebind, skipped rebind, rejected rebind, and recovery all use it. |
| R11-2 | Handshake deadline was timer-map-only and drifted across retries/reconnect. | `RekeyState.deadlineMs` is persisted in `sessions.state` (codec v3); start/respond set it once; retry/recovery use the minimum of retry cadence and the original deadline; inbound Accept/Confirm/Ack and duplicate Request check the deadline at consumption. |
| R11-3 | Timer callbacks had no installation identity at dispatch. | `StartTimer` carries an installation id; `TimerFiredAt` carries it back; `AppState.timerInstallations` is checked in the reducer and in the Confirm runtime translation. Rebinding cancels the old Delivery/Retry installations. |
| R11-4 | Last `Skipped` did not physically cancel timers or reliably retire. | Runtime removes skipped rows and timer installations, calls `transport.cancelTimer`, runs `RecoverRetirements` inline, then `Pump`; startup/reconnect also runs `RecoverRetirements`. |
| R11-5 | Restore-time `RecoverRebinds` used `tryOffer`. | `recoverRebinds` now applies inline on the writer. |
| R11-6 | Abort pumped before predecessor rebind. | `RebindOutgoing` no longer pumps; `RebindPending` batches by `(seq, id)` and the runtime pumps once after the batch. |

## R12–R14 corrections

- **Persisted evidence:** `RekeyState.ackReceived` records a locally consumed initiator Ack; initiator
  retirement requires it. Responder retirement requires authenticated peer Data on the successor,
  checked by author/device on restore.
- **Legacy upgrade:** v1 rows decode with an already-expired deadline (`1`); `adoptLockGeneration` and
  `AppStateRestore` normalize retained rows so a same-generation reopen and later safety read can
  write v3 without `InvalidCiphertext`. v2/v3 zero deadlines are rejected.
- **Offline recovery:** rekey timers remain installed while disconnected. An accepted initiator with
  a pending successor gets a Confirm deadline after an offline reopen; a retry missed while offline
  is marked and resumed once on reconnect without extending the deadline. Expiry aborts locally.
- **Late/inbound controls:** a late first Ack on an already-active successor is ignored rather than
  manufacturing Ack evidence; inbound Confirm/Ack use the consumption-time clock.
- **Retirement ordering:** retirement is durable before ancestor failures. A rejected last rebind
  recomputes retirement; surviving acknowledged predecessors, including unbound ones, are persisted.

## Verification

- `./check.sh` at `e2001ad`: PASS (582.48 s); E2E 17/17, 1k 4,356 ms, 10k 29,958 ms; cold start
  p50 1,202 ms / p95 1,218 ms; idle 745.3 MB; UI smoke 100/100.
- Targeted tests added: `InternalReviewSpec` stale Confirm/Delivery tokens, offline restart/reconnect,
  missed retry, late Ack, legacy zero deadline, responder first-Data, rejected rebind retirement;
  `StateCodecSpec` v3 Ack/deadline and legacy migration; `AppRuntimeSpec` startup retirement with
  asserted store writes; `TimerServiceSpec` installation echo.

## Remaining limits

- M8 (queue byte budget) and M9 (key-source/ADR mismatch) remain open.
- The store retirement guard still counts only `Pending`, not `Sent`; a hidden unrepresented `Sent`
  row is protected only by the core binding/outgoing checks. This predates R13.
- No external KAT, independent proof, or human/institution signature exists.
- Runtime-level multi-row `Skipped`/`Migrated` and mid-batch store-failure tests remain desirable;
  the core and executor tests cover the reducer and durable short-circuit, and E2E#17 covers one
  migrated row, but not the full writer batch.

The packet carries an owner risk-acceptance signature dated 2026-11-25; that closes the
cryptographic acceptance gate under the repository rule. This is not an independent external audit.
An independent reviewer may later verify the manifest, reproduce the evidence, answer
`open-questions.md`, and append a signature to `reviewer-checklist.md` as optional additional
assurance.
