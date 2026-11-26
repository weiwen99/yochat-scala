# S1 acceptance receipt

Date: 2026-11-23 (baseline); status update 2026-11-26
Base: `fe9e126`; current source at the status update: `f8ccb36` (UI acceptance fixes)

| Gate | Evidence | Verdict |
| --- | --- | --- |
| Behavior contracts | `server.e2e`: 18/18 at `f8ccb36` | PASS |
| 1k two-process | E2E#8: `3718ms`, warmup 1000 | PASS (<5s) |
| 10k two-process | PERF#10K: `41318ms`, warmup 1000 | PASS (<60s) |
| ACK throughput (observational) | PERF#RATE: `289.3 msg/s`, 10,000 x 128B, 64 in flight, warmup 1000 | MEASURED |
| Cold start | five samples, p50 `1263ms`, p95 `1272ms` | PASS (≤2s/≤4s) |
| Idle memory | 300s established idle, `757.5MB` | PASS (≤800MB) |
| Two-device UI | `shell/ui-smoke.js`: safety gate before sending, 100 delivered/100 received in each direction, exact rendered sequences; status banners (load error, 200 error envelope, pushed fatal), queue-full draft retention, modal keyboard, Electron exit reason, service-down banner | PASS |
| Send admission | `SendBackpressureSpec`: full queue rejection and two concurrent sends admit exactly one | PASS |
| Contrast gate | `shell/contrast-smoke.js`: 16 token ratios plus icon/back control boundaries | PASS |
| Electron window UI | `shell/electron-smoke.js`: two real Electron windows, 100+100 messages, both windows restarted, post-restart messages delivered | PASS |
| Cross-host UI (2026-11-25) | `shell/cross-host-smoke.js`: m1 + w7 servers, browser on each host, 100+100 messages, relay outage/reconnect, B/A server restart, post-restart delivery | PASS |
| Cross-host throughput (observational) | `CROSS_HOST_RATE`: A->B 1,000 messages `42.8 msg/s`; 5,000 messages `23.5 msg/s`; 128B, 64 in flight | MEASURED |
| Repository gate | `check.sh` executes unit/integration, E2E, desktop, UI, Quint, and spike checks | see command receipt |

## Status update 2026-11-26

Current source `f8ccb36` (UI acceptance fixes); full `./check.sh` PASS (693.16s): E2E 18/18,
E2E#8 3,718ms, 10k 41,318ms, local ACK throughput 289.3 msg/s (observational), cold start
p50 1,263ms / p95 1,272ms, idle 757.5MB, contrast gate PASS (16 ratios + 2 selectors), web UI
100/100 with the new failure/keyboard checks, Electron UI 100/100 with B/A restart. The earlier
1k failures at 5,141ms/5,234ms were load-induced (machine load average 28–34 on 16 cores); the
isolated and full-gate reruns passed at 3,857ms/3,718ms. Cross-host was not rerun in this batch.
M8/M9 remain closed; T1 总签 completed 2026-11-24 (`test-parity-final-signoff.md`). The owner
closed the cryptographic acceptance gate by explicit risk acceptance; this is not an independent
external audit.

## Remaining open gates

- Independent external cryptography review remains optional assurance; no external signature is
  claimed.
- Cross-host UI is a manual/opt-in SSH gate (`node shell/cross-host-smoke.js`), not part of the
  default `check.sh` because it requires the `w7` host. Store retirement guard and runtime multi-row
  batch tests are covered as above.
- Send admission has a 30-second watchdog. If the single-writer runtime stalls past it, the UI
  reports an unconfirmed send and keeps the draft; it does not instruct an automatic retry.
  Duplicate prevention under a writer stall longer than 30 seconds is not claimed.

The web UI, Electron-window UI, restart/reconnect, and service acceptance evidence are complete for
the tested surfaces. S1 owner risk acceptance is recorded; the remaining item above is optional
external assurance, not silently closed.
