# Plan 0004 live spec

- Plan: 0004
- Design record: `docs/plans/2026/11/0004-first-message-binding/`
- Status: implemented (full check green; round 2 review pending)
- Baseline: `4445297`
- Review budget: 3 rounds.

## Gate

| ID | Finding | Required evidence |
| --- | --- | --- |
| B1 | First Data has no account/safety binding | `DataPayload` v1 encodes both accounts + 39-digit safety number on `seq==1`; wire vector; code path in `UserSend` |
| B2 | Receiver accepts an unbound first Data | `Application.step` rejects missing/wrong/misplaced binding; no `PersistInbound`, no ACK/Gap, session/conversation unchanged |
| B3 | Binding leaks or changes visible data | Binding stays inside AEAD plaintext; header/relay unchanged; `messages.body`/API body remain user body |
| B4 | Existing flows regress | E2E#1-#7, B-first, rekey, restart, 100-message flow, `./check.sh` green |

## Sequence

1. Freeze design and ADR-0009; run review round 1.
2. Add `DataPayload` + tests.
3. Wire `AppStep.UserSend`/`InboundFrame`; add negative state tests.
4. Adapt test helpers and manual wire tests; no assertion weakening.
5. Run core/server/E2E/full check; freeze diff for rounds 2–3.
6. Record receipts and open items.

## Known non-goals

- No Quint model change in this round.
- No legacy first-message migration.
- No 1k/10k, E2E#8, E2E#9–#15, UI automation, new table, header field, or config.
