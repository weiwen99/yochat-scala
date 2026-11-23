# Plan 0007 live spec

- Plan: 0007
- Design record: `docs/plans/2026/11/0007-rekey-continuity/`
- Status: design
- Baseline: `647f785` (Plan 0006 stack)
- Review budget: 3 rounds.

## Gate

| ID | Finding | Required evidence |
| --- | --- | --- |
| 7A | Old pending messages become unusable after cutover | A real `sendMessage` before automatic rekey either is delivered by the old session while it remains read-only or is re-encrypted and updated in place under the new session; it appears once and ends `delivered`. A subsequent new message is delivered with the next sequence. |
| 7B | Rebuild failure is invisible | A forced rekey timeout yields a production-readable `rekey-failed` value at the service/state boundary; no rekey starts silently. |
| 7C | Rebuilds are unbounded across successful cutovers | Within one 60s window, successful and failed attempts count toward a cap of 3; the fourth attempt fails visibly; after 60s a new window is allowed. |
| 7D | No new protocol surface | No new table, header field, wire kind, or config. The rebind uses the existing session/message rows and key derivation. |

## Sequence

1. Freeze the 7A storage/ID contract in `design.md`; review round 1.
2. Implement 7A only; run `RatchetSpec`, `RekeySpec`, `store.test`, E2E#14 real-sender.
3. Implement 7B/7C; run API/state and rekey-window tests.
4. Full E2E and `./check.sh`; record receipts.
5. Freeze diff; review rounds 2–3.

## Known non-goals

- No #8/10k, cold start, idle memory, UI DOM, or Electron automation.
- No multi-device/history sync/revocation.
- No new timeout, retry, or rekey quota beyond the existing 60s cooldown / 3 attempts.
