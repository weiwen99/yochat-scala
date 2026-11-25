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
- `S1E2E.scala`: `PERF#RATE` warms up 1,000 messages, then measures 10,000 fresh 128-byte messages with 64 sends in flight and direct SQLite received/delivered counts.

## Verification

- E2E#17 PASS: `rebound=1 afterCutover=1`.
- E2E#8 PASS, measured runs: `3783ms`, `3785ms`, `3708ms` with `warmup=1000` (1,000 total messages, not 1,000 msg/s).
- PERF#10K PASS: `elapsed_ms=28986 warmup=1000 bCount=10000 aDelivered=10000`.
- `PERF#RATE` PASS, three isolated runs:
  - `rate_msg_s=337.7 b_rate_msg_s=338.1 ack_ms=29609`
  - `rate_msg_s=334.2 b_rate_msg_s=334.6 ack_ms=29920`
  - `rate_msg_s=325.0 b_rate_msg_s=325.4 ack_ms=30768`
  - Median isolated ACK rate: 334.2 msg/s for 10,000 x 128-byte messages.
- Full `server.e2e` after adding `PERF#RATE`: 18/18 contracts; rates `334.0`, `334.7`, and `335.4` msg/s across full runs. The latest full gate receipt is `tmp/checks/1795579743834/s1-e2e.log`; the `./check.sh` run recorded `rate_msg_s=335.4 ack_ms=29815`.
- `PERF#RATE` is observational; it has no msg/s threshold and does not measure UI rendering. The receipt includes exact `bCount=11000 aDelivered=11000` (1,000 warmup + 10,000 measured).
