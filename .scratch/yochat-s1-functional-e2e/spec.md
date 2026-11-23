# Plan 0003 live spec

- Plan: 0003
- Design record: `docs/plans/2026/11/0003-s1-functional-e2e/`
- Status: implemented (service flow; ADR-0006 §3.3 binding open)
- Baseline: `8977ebd`
- Review budget: 3 rounds maximum; Round 2 closed with no BLOCKER.

## Closed findings

| ID | Finding | Evidence |
| --- | --- | --- |
| E1 | No two-process functional E2E | `server.e2e` starts relay + two real server processes; 7/7 pass |
| E2 | Invite/reply/contact only same-JVM | `E2E#1/#2` through HTTP and real relay |
| E3 | Safety gate not E2E-proven | `E2E#1/#2/#3` negative cases use a FIFO barrier; no body before safety |
| E4 | Restart paths not process-level | `E2E#4/#5` lock, stop, restart, identity check, `seq=2` continuation |
| E5 | Reconnect not relay-level | `E2E#6` close/restart relay; offline row is `pending`, then delivered |
| E6 | 100-message order/dedup not E2E | `E2E#7` complete 100-row histories, exact order |
| E7 | Failures not reproducible | redacted receipt under `tmp/checks/<timestamp>/`, HEAD + counts + timings |
| L1 | Lock generation adoption | `adoptLockGeneration` + store regression; stale generation/version rejected after adoption |

## Open

- ADR-0006 §3.3 first-message account/safety binding; `E2E#1/#2/#3` security closure.
- Wire-level absence of pre-safety DATA; no frame counter in this harness.
- `E2E#8`, 10k, cold start, idle memory.
- `E2E#9–#15` and UI automation.
- Concurrent direct opens of the same data directory are unsupported by S1 lifecycle.
