# S1 acceptance receipt

Date: 2026-11-23 (baseline); status update 2026-11-25
Base: `fe9e126`; current source at the status update: `b56bb68` (throughput measurement)

| Gate | Evidence | Verdict |
| --- | --- | --- |
| Behavior contracts | `server.e2e`: 18/18 at `b56bb68` | PASS |
| 1k two-process | E2E#8: `3854ms`, warmup 1000 | PASS (<5s) |
| 10k two-process | PERF#10K: `28958ms`, warmup 1000 | PASS (<60s) |
| ACK throughput (observational) | PERF#RATE: `335.4 msg/s`, 10,000 x 128B, 64 in flight, warmup 1000 | MEASURED |
| Cold start | five samples, p50 `1200ms`, p95 `1221ms` | PASS (≤2s/≤4s) |
| Idle memory | 300s established idle, `750.6MB` | PASS (≤800MB) |
| Two-device UI | `shell/ui-smoke.js`: safety gate before sending, 100 delivered/100 received in each direction, exact rendered sequences | PASS |
| Electron window UI | `shell/electron-smoke.js`: two real Electron windows, 100+100 messages, both windows restarted, post-restart messages delivered | PASS |
| Cross-host UI | `shell/cross-host-smoke.js`: m1 + w7 servers, browser on each host, 100+100 messages, relay outage/reconnect, B/A server restart, post-restart delivery | PASS |
| Cross-host throughput (observational) | `CROSS_HOST_RATE`: A->B 1,000 messages `42.4 msg/s`; 5,000 messages `21.8 msg/s`; 128B, 64 in flight | MEASURED |
| Repository gate | `check.sh` executes unit/integration, E2E, desktop, UI, Quint, and spike checks | see command receipt |

## Status update 2026-11-25

Current source `b56bb68`; full `./check.sh` PASS (634.73s): E2E 18/18, 1k 3,854ms, 10k 28,958ms,
ACK throughput 335.4 msg/s (observational), cold start p50 1,200ms / p95 1,221ms, idle 750.6MB,
web UI 100/100, Electron UI 100/100 with B/A restart, cross-host m1/w7 100+100 with relay outage and
B/A restart (54.5s receipt). M8 (byte-bounded inbound queues) and M9 (key-source/ADR alignment) are
closed in `docs/reviews/2026-11-24-s1-crypto/m8-m9-closure.md`. T1 总签 completed 2026-11-24
(`test-parity-final-signoff.md`). The owner closed the cryptographic acceptance gate by explicit
risk acceptance; this is not an independent external audit.

## Remaining open gates

- Independent external cryptography review remains optional assurance; no external signature is
  claimed.
- Cross-host UI is a manual/opt-in SSH gate (`node shell/cross-host-smoke.js`), not part of the
  default `check.sh` because it requires the `w7` host. Store retirement guard and runtime multi-row
  batch tests are covered as above.

The web UI, Electron-window UI, restart/reconnect, and service acceptance evidence are complete for
the tested surfaces. S1 owner risk acceptance is recorded; the remaining item above is optional
external assurance, not silently closed.
