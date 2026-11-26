# Plan 0012 test parity

Status: implementation evidence present and revalidated after the Plan 0013 capability gate. Owner instructed autonomous closure on 2026-11-27; this is recorded as owner authorization, not an independent row-by-row audit or external cryptographic review. The S2-A T1 sign-off (2026-11-26) is not broadened automatically. This table maps the S2-A omitted rows and the old S2-B-relevant tests; it does not re-open S1.

| Old test / requirement | Old behavior | S2-B test | Disposition | Reason / gap |
| --- | --- | --- | --- | --- |
| `T1-2341` own-enrollment approval | Stale approvals rejected across restart/isolation | `S2B#1` device op merge + `S2#5` pairing | REWRITE | New op set, not the old approval graph; device ops are the authority |
| `T1-2400` self-sync ordered catch-up | Disconnected device catches up in order | `S2B#2` direct B1→A2, `S2B#2a` dedupe, `S2B#3` order | REWRITE | Implemented; A1 device-sync remains one path |
| `T1-2405` revocation | C cannot decrypt after revocation while B receives | `S2B#4` signed revocation | REWRITE | Signed user revocation reaches contact; local history retained |
| `T1-0133` invalid device list | Whole invalid list dropped | `S2B#1` negative signatures | REWRITE | Signed operations are per-op; invalid op is dropped, a whole invalid batch is rejected |
| `order_*` old GBNV models | Cross-author total order | `S2B#3` two-author Lamport/GBNV | REWRITE | Only A1/B1 write; no group/third-writer claim |
| `multidevice.qnt` | Multi-device delivery/revocation | `S2B#2`/`S2B#2b`/`S2B#2c`/`S2B#4` | REWRITE | One read-only companion per user; direct delivery, retry, revocation implemented |
| Full history sync 10k/10s | New device gets history | — | OMIT (S2-B) | Not in scope; S3 candidate only |
| User private key sync / recovery phrase | Second device becomes writable | — | OMIT (S2-B) | Read-only companions only; later plan |
| B directly addresses A2 with no A1 | Old multi-device fanout | `S2B#2` | RETAIN BEHAVIOR | Implemented with device-list authorization; E2E A1 offline |
| Contact-visible signed revocation | B rejects revoked device | `S2B#4` | RETAIN BEHAVIOR | Implemented as signed original |
| Cross-device GBNV | Same final order everywhere | `S2B#3` | RETAIN BEHAVIOR | Implemented for two authors; E2E concurrent A/B order |

Explicit omissions: history sync, user-private sync, recovery phrase, group/file, third writable device, mobile. Owner authorization for S2-B mapping: 2026-11-27 instruction; independent audit not claimed.
