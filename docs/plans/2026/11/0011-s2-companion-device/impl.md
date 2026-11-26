# Plan 0011 S2-A implementation

Status: implementation receipt; owner acceptance and T1 sign-off are still required. This is S2-A only; GBNV and contact-visible signed revocation remain S2-B/后续.

## Delivered

- A1/A2 pairing with two-screen confirmation code/fingerprint, encrypted authorization, v2 identity (A2 has no user private key), and local removal.
- A1 `messages.sync_seq` visible-change stream; signed, sealed incremental batches filtered by the signed join manifest; byte/message caps; signed A2 ACK checkpoint.
- A2 read-only `companion_messages` projection and role-aware API/restore; no peer wire/session or send path.
- Bidirectional signed `SyncStatus`: A2 status on reconnect/restart, A1 `SyncStatusRequest` on startup/reconnect, one complete checkpoint tuple.
- Real-relay E2E and a separate 1k sync gate.

## Receipts

| Gate | Command | Result |
| --- | --- | --- |
| S2 functional contract | `./mill server.s2e2e` | PASS 20.7s; `old=0 postJoin=200 offline=220 restart=221 revoked=221`; A2 post-join history compared with A1; no duplicate author seq. Receipt `tmp/checks/s2e2e.log` |
| S2#2 1k sync | `./mill server.s2perf` | PASS; `sync_ms=2552 count=1000 firstSeq=1 lastSeq=1000` (threshold 5,000ms). Receipt `tmp/checks/s2perf.log` |
| Unit/store | `./mill server.test`, `./mill store.test` | PASS in final `./check.sh` |
| S1 regression / UI / smoke | `./check.sh` | PASS 668.4s; S1 E2E 18/18, E2E#8 3737ms, `PERF#10K` 34,522ms, `PERF#RATE` 308.2 msg/s, cold start p50/p95 1283/1341ms, idle 750.4MB. Receipt `tmp/checks/` |

## Known limits

- S2-A is one primary plus one read-only companion. A1 offline stops A2; A2 cannot take over.
- Contact-visible signed revocation and GBNV are out of scope and remain explicit S2 commitments.
- `test-parity.md` T1 rows require owner sign-off before S2 delivery can be claimed.
- The dedicated ACK-loss/old-status fault injection is covered by the bidirectional status path and A1-restart E2E, not by a separate packet-drop proxy scenario.
