# Plan 0008 implementation

- Status: implemented
- Date: 2026-11-23
- Branch: `feat/plan-0008-s1-perf-e2e`
- Base: `5317022`

## Changes

- `S1E2E.scala`: E2E#17 now sends `after-cutover` and asserts one B body and one A `Delivered`.
- `S1E2E.scala`: E2E#8 sends 1,000 warmup and 1,000 measured messages on the real service path.
- `E2ESupport.scala`: bounded async HTTP send and a read-only SQLite count probe for completion.
- `S1E2E.scala`: `PERF#10K` sends 1,000 warmup and 10,000 measured messages in bounded batches.

## Verification

- E2E#17 PASS: `rebound=1 afterCutover=1`.
- E2E#8 PASS, measured runs: `3783ms`, `3785ms`, `3708ms` with `warmup=1000`.
- PERF#10K PASS: `elapsed_ms=28986 warmup=1000 bCount=10000 aDelivered=10000`.
- Full `server.e2e`: 17/17 contracts, including `PERF#10K`; receipt under `tmp/checks/`.
