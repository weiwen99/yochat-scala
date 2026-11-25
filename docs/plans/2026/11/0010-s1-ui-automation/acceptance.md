# S1 acceptance receipt

Date: 2026-11-23 (baseline); status update 2026-11-25
Base: `fe9e126`; current source at the status update: `ca33ec3` (throughput + cross-host rate)

| Gate | Evidence | Verdict |
| --- | --- | --- |
| Behavior contracts | `server.e2e`: 18/18 at `ca33ec3` | PASS |
| 1k two-process | E2E#8: `4347ms`, warmup 1000 | PASS (<5s) |
| 10k two-process | PERF#10K: `32854ms`, warmup 1000 | PASS (<60s) |
| ACK throughput (observational) | PERF#RATE: `300.4 msg/s`, 10,000 x 128B, 64 in flight, warmup 1000 | MEASURED |
| Cold start | five samples, p50 `1296ms`, p95 `1351ms` | PASS (≤2s/≤4s) |
| Idle memory | 300s established idle, `756.8MB` | PASS (≤800MB) |
| Two-device UI | `shell/ui-smoke.js`: safety gate before sending, 100 delivered/100 received in each direction, exact rendered sequences | PASS |
| Electron window UI | `shell/electron-smoke.js`: two real Electron windows, 100+100 messages, both windows restarted, post-restart messages delivered | PASS |
| Cross-host UI | `shell/cross-host-smoke.js`: m1 + w7 servers, browser on each host, 100+100 messages, relay outage/reconnect, B/A server restart, post-restart delivery | PASS |
| Cross-host throughput (observational) | `CROSS_HOST_RATE`: A->B 1,000 messages `42.8 msg/s`; 5,000 messages `23.5 msg/s`; 128B, 64 in flight | MEASURED |
| Repository gate | `check.sh` executes unit/integration, E2E, desktop, UI, Quint, and spike checks | see command receipt |

## Status update 2026-11-25

Current source `ca33ec3`; full `./check.sh` PASS (655.77s): E2E 18/18, 1k 4,347ms, 10k 32,854ms,
local ACK throughput 300.4 msg/s (observational), cold start p50 1,296ms / p95 1,351ms, idle 756.8MB,
web UI 100/100, Electron UI 100/100 with B/A restart, cross-host m1/w7 100+100 with relay outage and
B/A restart plus CROSS_HOST_RATE 42.8 msg/s (1k) and 23.5 msg/s (5k). M8 (byte-bounded inbound queues) and M9 (key-source/ADR alignment) are
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
