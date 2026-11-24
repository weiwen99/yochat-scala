# Internal reviewer rounds 4–10

Status: internal, non-signing. The 10-round review budget is exhausted. The external cryptographic
gate remains **open**; this is not an external audit or signature.

Source baseline: `69050e4` (cumulative R3–R9 fixes).
Reviewers: read-only `reviewer` agents R4–R10; they did not edit files.

## Closed during rounds 4–10

- B1 idle-lock read/write admission, including the live safety POST.
- B2 deletion of an active successor; stale initiator timer.
- R3-N1 late-old-frame fatal after successful retirement.
- R3-N2 premature responder retirement before first new-session Data, including the direct-predecessor
  restriction.
- M1–M6 regression tests; M7 `Skipped`-then-`PersistAttempt` fatal and rebound-key test.
- Buffered first new Data carries `PersistInboundAndRetire`; overflow keeps the predecessor and its
  retirement flag.
- R7/R8: direct-predecessor recovery; pending-successor wait; durable failure for genuinely stranded
  ancestors; abort pumps resumed old queued work.

## Remaining release blockers reported by R10 (not closed)

1. A rejected rebind (e.g. `AccountMismatch`) returns unchanged state/effects; the runtime responder
   loop can select the same row indefinitely. Needs one visible rejection and batch termination.
2. A deferred ancestor outgoing is not revisited when its direct successor reactivates/retires;
   needs a phase-change re-evaluation.
3. Abort `pump` does not enforce `relationshipUsable`; a mismatched contact may replay old queued
   wire. Needs authorization gate before send.
4. B3 has no single handshake deadline across retries/reconnects; retry/abort deadlines can drift past
   responder expiry. Needs deadline preservation and remaining-budget retry.
5. Timer callbacks have no instance token at consumption; stale Confirm/Initiator/delivery callbacks
   can affect a replacement timer or a rebound message.
6. M7 skipped cleanup: skipped ID timers are not canceled, retirement/drain is dropped, and
   `Pump`/`RecoverRebinds` are best-effort `tryOffer` calls.
7. Responder rebind does not reset attempts/durable state; a third-attempt `Sent` row can become
   `Failed` immediately under the new session.

## Verification and limits

- `./check.sh` at `69050e4`: PASS (572.73 s); E2E 17/17, 1k 4,222 ms, 10k 30,642 ms; cold start
  p50 1,213 ms / p95 1,259 ms; idle 760.5 MB; UI smoke 100/100.
- MUnit reports inspected: core/store/server/transport all 0 failed.
- No external KAT or independent cryptographic proof.
- M8 (queue byte budget) and M9 (key-source/ADR mismatch) remain open.
- The ten-round cap is reached; further changes need owner escalation and a fresh review budget.

The packet must not be handed to an external reviewer as a finished baseline until the seven P1
items above are closed or explicitly accepted by the owner, and a new source/manifest is frozen.
