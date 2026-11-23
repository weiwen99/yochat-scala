# S1 acceptance receipt

Date: 2026-11-23
Base: `fe9e126`

| Gate | Evidence | Verdict |
| --- | --- | --- |
| Behavior contracts | `server.e2e`: E2E#1–#15 plus E2E#17; 16 behavior contracts | PASS |
| 1k two-process | E2E#8: `3738ms`, warmup 1000 | PASS (<5s) |
| 10k two-process | PERF#10K: `28804ms`, warmup 1000 | PASS (<60s) |
| Cold start | five samples, p50 `1198ms`, p95 `1208ms` | PASS (≤2s/≤4s) |
| Idle memory | 300s established idle, `750.6MB` | PASS (≤800MB) |
| Two-device UI | `shell/ui-smoke.js`: safety gate before sending, 100 delivered/100 received in each direction, exact rendered sequences | PASS |
| Repository gate | `check.sh` executes unit/integration, E2E, desktop, UI, Quint, and spike checks | see command receipt |

## Open human gates

- Final T1 parity reconciliation in `docs/plans/2026/11/0001-yochat-rewrite/test-parity.md` still needs the required human sign-off; the current file is an initial inventory.
- External cryptography review remains a release gate.
- Electron-window UI interaction is not automated; the UI smoke drives the same web surface in Chrome. Desktop cold-start/idle and shell lifecycle are measured separately.

The web UI and service acceptance evidence is complete for the tested surfaces; S1 release sign-off is not claimed until the human gates close. Electron-window interaction and full UI restart/reconnect are not automated.
