# Plan 0002 live spec

- Plan: 0002
- Design record: `docs/plans/2026/11/0002-m3-structural-hardening/`
- Status: draft, not implemented
- Baseline: `0ce9937` (M4 merged); M3 review round 1 in Plan 0001 `impl.md`
- Review budget: 7 rounds maximum; round 3 must show a falling finding count or stop.

## Open findings

| ID | Finding | Required evidence |
| --- | --- | --- |
| R1 | Atomic reply/rekey handshake transactions | crash-injection test; no partial contact/session/conversation/invitation |
| R2 | Commit-before-publish runtime | store failure leaves old state and publishes nothing |
| R3 | Durable accepted-invitation reply | offline accept, restart/reconnect, retry until sent; no fatal on first transport failure |
| R4 | Rekey retry/abort | each control frame dropped; active or bounded abort |
| R5 | Bounded atomic restore | 10k restore single read transaction, indexed bounded projection, no 100k prefix |
| R6 | Restore Gap and attempts | buffered gap requested after restart; attempts survive restart and stay bounded |
| R7 | Reply invitation id | reply locates one invitation without scanning all |
| R8 | Timer registry | fired/cancelled timer removed; repeat start/cancel idempotent |
| R9 | Idle timeout and Lock generation | idle locks; generation persisted; old-generation results rejected after restart |

## Sequence

1. Freeze design and run review rounds 1–2.
2. Implement R1–R3, then R4–R6, then R7–R9; each gate gets a failing test before the fix.
3. Freeze the implementation diff and run rounds 3–7. Do not edit during a review round.
4. Merge only when all nine are closed or explicitly withdrawn; `./check.sh` green and 10k replay <50s.
