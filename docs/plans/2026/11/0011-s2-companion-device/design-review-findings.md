# Plan 0011 design review findings

Object: `design.md` at worktree branch `docs/plan-0011-s2-multi-device`, round 1. Standards and Spec axes; source evidence in `docs/evaluations/2026-11-25-d-sync-throughput-requirements.md`. The review is internal; it is not an external audit. Round 1 found 8 P1 + 8 P2. All findings below were fixed in the revised design; the closure evidence is the section named in the last column.

| ID | Severity | Finding and impact | Rule / source | Closure evidence | Cost |
| --- | --- | --- | --- | --- | --- |
| R1-1 | P1 | Pre-enrollment message gets a new `sync_seq` on later delivery; A2 could receive its body. | ADR-0003 S2 post-join boundary; source message `133908` | §7.3/§8.2: signed `joinManifest` over `(conversation, author, device, seq)` filters creation; `S2#5` pre-join-delivered and relabeled-id cases | 0.25 d |
| R1-2 | P1 | A2 mirrored SQLite insertion order; A1 UI hides unresolved gaps and orders by `(seq,id)`, so A2 could show B's seq=2 before seq=1. | `ApiJson.visible/ordered`; ADR-0003 ordering | §4.3/§8.1/§8.2: A1 UI visible projection; A2 sorts `(seq, sourceId)`; `S2#1` compares actual `/api/conversations/<id>/messages` JSON | 0.5 d |
| R1-3 | P1 | Lost ACK plus A1 restart reconstructs a batch with a new nonce; hash/`prevBatchHash` cannot match A2's committed batch. | ADR-0004 evidence discipline; ADR-0008 ACK semantics | §8.4/§8.5: signed `SyncStatus` plus A1-initiated `SyncStatusRequest`; one checkpoint tuple; `S2#3` restarts A1 while A2 stays online | 0.5 d |
| R1-4 | P1 | A1 confirmed only request-supplied name/fingerprint; a same-name wrong device could be authorized. | ADR-0003 safety/invitation; pairing threat model | §7.2/§7.3: verify request proof; A2 displays its own confirmation code/fingerprint; user compares both screens; `S2#5` | 0.25 d |
| R1-5 | P1 | Device sync had no carrier: relay rejects unknown kinds and `translateInbound` drops non-S1 frames. | ADR-0002 relay-only; transport boundary | §8.3: `RelayEnvelope kind=device-sync`; body includes `DeviceAuthorization`/`SyncStatusRequest`/`ResyncRequired`; real-relay E2E | 0.5 d |
| R1-6 | P1 | Batch nonce was not transmitted; A2 could not decrypt. | ADR-0006 AEAD encoding | §8.4: nonce in frame and AAD; decrypt vector | 0.1 d |
| R1-7 | P1 | Empty `wire` in existing `messages` is invalid; restore assumes peer sessions; A2 cannot read/restart. | `MessageRepository.decode`; `AppStateRestore.contactsFrom` | §10.1: dedicated `companion_messages` projection; role-aware `ApiJson`/restore; contact-only restart gate | 0.5 d |
| R1-8 | P1 | Planned schema v2 collides with current v2; v2 database would be mistaken for S2 format. | `Schema.scala` current `Version=2` | §10.4: 2→3 migration, preserve 1→2, populated v2 fixture | 0.25 d |
| R2-9 | P2 | Enrollment could commit a grant without a sync root; `conversations` omitted from lock order. | ADR-0007 transaction/lock discipline | §7.3/§10.3: devices+device_sync one transaction; full S2 lock order | 0.25 d |
| R2-10 | P2 | Oversized first batch waits for an ACK that cannot exist; 256-record cap ignores relay max bytes. | ADR-0008 bounds; relay payload | §8.2/§8.5: byte-aware packing, split, single-record failure | 0.15 d |
| R2-11 | P2 | Signature failure produced `ResyncRequired`; optional removal frame lacked sender/epoch auth. | ADR-0003 inbound admission | §8.4/§8.5 and §9: unauthenticated input dropped silently; removal frame omitted | 0.2 d |
| R2-12 | P2 | Design did not state that GBNV and signed revocation remain open S2 commitments. | ADR-0003 S2; GLOSSARY device revocation | §2/§16: S2-A is partial; two commitments open; owner decision | 0.1 d |
| R2-13 | P2 | “No old multi-device tests” ignored T1-2341/T1-2400/T1-2600; round 2 added T1-2323/T1-2405. | ADR-0004 T1 | `test-parity.md` includes all five; pending owner sign-off | 0.5 d |
| R2-14 | P2 | Two-process one-batch spike could not decide 1k gate; worker preceded Q7 freeze. | AGENTS §4/§6; ADR-0004 | §11.1: Q7 before worker; 1k measured at storage/worker stage | 0.2 d |
| R2-15 | P2 | Acceptance named nonexistent Mill targets and ran UI smoke before assembly build. | `check.sh` / `build.mill` | §18: `server.e2e`, `store.test`, `server.test`; UI smoke via `check.sh` after assemblies | 0.1 d |
| R2-16 | P2 | D's history figure was written as an accepted S3 gate. | 2026-11-25 evaluation: pending scope | §5/§13: candidate requirement, not accepted S3 gate | 0.05 d |
| N1 | P1 | Export eligibility used `messages.id`, which is not in AAD; a relabeled pre-join row could pass. | ADR-0007 §3; ADR-0003 post-join | §7.3/§8.2 signed `joinManifest` over `(conversation, author, device, seq)`; A2 verifies; S2#5 relabel test | 0.5–1 d |
| N2 | P2 | S2#1 used `/api/session` and `seq=2/1` after 50 bidirectional messages; B's seq had advanced. | `HttpApi.scala` history path | §13/§18 use `/api/conversations/<id>/messages` and next valid `N+2/N+1` frames | 0.1 d |
| N3 | P1 | Resume cursor and `prevBatchHash` could come from different checkpoints. | ADR-0008 ACK chain | §8.4 selects one checkpoint tuple; rejects older/conflicting status; S2#3 replay test | 0.25 d |
| N4 | P2 | `DeviceAuthorization` was signed but not encrypted; relay could read `userPub`/name. | ADR-0006 relay privacy | §7.3/§8.3 encrypt with pairing key; S2#5 relay byte scan | 0.25 d |
| N5 | P2 | Grant retransmission and second enrollment were indistinguishable. | ADR-0003 enrollment | §7.2 stores `pairingNonce`/`requestHash`; same request resends original grant, different request rejected; S2#5 | 0.25 d |

## Status

- Round 1: fixed.
- Round 2: 6 open items and 5 new findings; all revised in the design.
- Round 3: pending. It should check only the revised sections and the closure evidence, not reopen scope.
- No claim of external review or implementation.
