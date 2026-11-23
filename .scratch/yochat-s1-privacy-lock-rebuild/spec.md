# Plan 0006 live spec

- Plan: 0006
- Design record: `docs/plans/2026/11/0006-s1-privacy-lock-rebuild/`
- Status: implemented (check green; Round 1 review closed)
- Baseline: `c21210b`
- Review budget: 3 rounds.

## Gate

| ID | Finding | Required evidence |
| --- | --- | --- |
| P1 | Logs or diagnostic error payloads expose content, contact names, public keys, or key material | After a real message with a unique body and alias, both service logs and HTTP error payloads are scanned; no marker/alias/public key/key material occurs. `/api/state` is treated as UI data, not a diagnostic log. |
| P2 | App lock leaves readable state or accepts sends | Lock returns 200; session reports locked; state/messages/send return 401; wrong passphrase fails; correct unlock restores the existing history and allows a new message. |
| P3 | Undecryptable messages silently disappear or never trigger rebuild | After 3 qualified AEAD failures, the receiver emits automatic rekey; a new session becomes active; a new message succeeds. The bad body is never visible. Rebuild attempts stay bounded/failed only after the attempt cap; no silent switch. |

## Sequence

1. Freeze design; run review round 1.
2. Add `AppEffect.StartRekey` + runtime execution; update core threshold test.
3. Add fault count support for three consecutive AEAD failures; add E2E#14.
4. Add E2E#12 log/error scan and E2E#13 lock/unlock.
5. Run focused/full E2E and `./check.sh`; record receipts.
6. Freeze diff; close review findings.

## Known non-goals

- No new table, schema, header, wire format, UI screen, or Quint model.
- No #8/10k, cold start, idle-memory, or Electron DOM automation.
- E2E#13 covers manual lock/unlock; idle timeout remains covered by core `AppLockSpec` and is not re-proven in E2E.
- E2E#12 does not treat the UI data API (`/api/state`) as a log; it scans logs and error payloads.
- Automatic rekey uses existing `MaxRekeyAttempts`/cooldown; no new policy surface.
