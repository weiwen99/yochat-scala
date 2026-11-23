# Plan 0008 design: E2E continuity and 1k gate

## Scope

- Test-only: `server.e2e` harness and receipts.
- No wire, SQLite schema, API, or product configuration change.

## E2E#17 extension

After the existing pending-message rebind, send one ordinary message on the new session and wait for:

- one body on B;
- one `Delivered` entry on A.

This consumes a real sender sequence before cutover and proves normal continuation after cutover.

## E2E#8

- Real relay, two real server JVMs, real SQLite.
- Warm up 1,000 messages, wait for both sides.
- Measure a fresh 1,000 messages from first send to B's 1,000th body and A's 1,000th `Delivered`.
- HTTP sends are bounded at 32 in flight; bodies are unique.
- Gate: measured elapsed `<5000ms`.
- Report the measured elapsed, warmup count, and both counts in the receipt.

The warmup is separate from the measured window and is recorded explicitly; it is not a skipped or weakened assertion.


## PERF#10K

- Same two-process service path as E2E#8.
- Warm up 1,000 messages; measure 10,000 fresh messages.
- Send in bounded batches and use the real SQLite message rows for completion counts; no product API is added.
- Gate: measured elapsed `<60000ms`; report warmup, both counts, and elapsed.
