# Plan 0008/0009 review

Fixed point: `main` at `5317022`; diff `git diff main...HEAD`.
Axes: Standards and Spec, reviewed independently before merge.

## Standards findings

| ID | Finding | Closure |
| --- | --- | --- |
| S1 | `check.sh` did not execute the new E2E or desktop gates. | Added `server.e2e`, `server.assembly`, Electron presence check, and `shell/perf-start.js` to `check.sh`. |
| S2 | E2E#17 could pass on the old session after a queued rekey. | Added both-peer active-session change assertions and sequence `1,2` assertions. |
| S3 | `--user-data-dir` was passed as two Chromium tokens. | Changed to `--user-data-dir=<path>`. |
| S4 | Desktop measurement could reuse an existing server and exclude its JVM. | Each launch uses a distinct port; idle requires the owned JVM in the process tree. |
| S5 | Cold start excluded native startup and `did-finish-load` preceded UI interactivity. | Parent records spawn epoch; shell waits for `.loading-veil` removal. |
| S6 | Idle sample was 8s, not five minutes; early exit could produce no receipt. | Pinned 300s idle and subscribed to child exit before the idle window. |
| S7 | Gate limits/samples were overridable. | Limits and five samples are fixed constants; no env override. |
| S8 | Async sends could hang without a deadline. | Added per-batch 30s timeout. |
| S9 | Direct SQLite count is not public history; 10k lacks body/read verification. | Accepted for the performance gate only: public history caps at 1,000; behavior contracts cover body correctness. No product API added. |
| S10 | 10k receipt has one elapsed value, not p50/p95. | Accepted as a throughput gate receipt; 1k has repeated samples. A repeated 10k distribution is future work. |
| S11 | Plan 0007's historical 15/15 receipt was rewritten. | Restored the Plan 0007 receipt and appended Plan 0008 evidence. |

Baseline judgement calls: shared E2E send batching remains duplicated between E2E#8 and PERF#10K; message-state literals remain test-only. Not blocking.

## Spec findings

| ID | Finding | Closure |
| --- | --- | --- |
| P1 | Cutover continuity was not proven. | E2E#17 now awaits new active sessions on both peers and asserts seq `1,2` on both sides. |
| P2 | Gates were not executed by the repository check. | Added to `check.sh`. |
| P3 | Launch metric excluded native startup and UI readiness. | Spawn epoch + `.loading-veil` wait. |
| P4 | Cold-start/idle fixture was not isolated or established. | Unique ports and `--user-data-dir=`; warmup creates the profile; idle waits 300s. |
| P5 | Throughput/desktop receipts omit some metadata. | Desktop receipt includes samples, limits, idle seconds, node version, verdict; throughput receipt evidence is in `server.e2e`. |
| P6 | Plan 0006 closure claim about real timeout was broader than tested. | Plan 0006 closure now references Plan 0007/0008 evidence and keeps UI/external review open. |

Residual, explicitly not claimed: UI automation and external cryptography review.
