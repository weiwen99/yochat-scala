# S1 acceptance receipt

Date: 2026-11-23 (baseline); status update 2026-11-25
Base: `fe9e126`; current source at the status update: `6a81640` (M8/M9)

| Gate | Evidence | Verdict |
| --- | --- | --- |
| Behavior contracts | `server.e2e`: 17/17 at `6a81640` | PASS |
| 1k two-process | E2E#8: `3795ms`, warmup 1000 | PASS (<5s) |
| 10k two-process | PERF#10K: `28744ms`, warmup 1000 | PASS (<60s) |
| Cold start | five samples, p50 `1194ms`, p95 `1208ms` | PASS (≤2s/≤4s) |
| Idle memory | 300s established idle, `742.5MB` | PASS (≤800MB) |
| Two-device UI | `shell/ui-smoke.js`: safety gate before sending, 100 delivered/100 received in each direction, exact rendered sequences | PASS |
| Repository gate | `check.sh` executes unit/integration, E2E, desktop, UI, Quint, and spike checks | see command receipt |

## Status update 2026-11-25

Current source `6a81640`; full `./check.sh` PASS (563.99s): E2E 17/17, 1k 3,795ms, 10k 28,744ms,
cold start p50 1,194ms / p95 1,208ms, idle 742.5MB, web UI smoke 100/100. M8 (byte-bounded inbound
queues) and M9 (key-source/ADR alignment) are closed in
`docs/reviews/2026-11-24-s1-crypto/m8-m9-closure.md`. T1 总签 completed 2026-11-24
(`test-parity-final-signoff.md`). The owner closed the cryptographic acceptance gate by explicit
risk acceptance; this is not an independent external audit.

## Remaining open gates

- Electron-window UI interaction and full UI restart/reconnect are not automated; the UI smoke drives
  the same web surface in Chrome. Desktop cold-start/idle and shell lifecycle are measured separately.
- Store retirement guard still counts only `Pending`; runtime multi-row `Migrated`/`Skipped` plus
  mid-batch store-failure coverage remains a planned residual test.
- Independent external cryptography review remains optional assurance; no external signature is
  claimed.

The web UI and service acceptance evidence is complete for the tested surfaces. S1 owner risk
acceptance is recorded; the remaining items above are explicitly accepted or pending test work, not
silently closed.
