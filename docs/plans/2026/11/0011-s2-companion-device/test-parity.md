# Plan 0011 test parity

Status: draft; no owner sign-off yet. This file maps the old multi-device tests that Plan 0001 marked `OMIT_CANDIDATE` because S1 had no multi-device surface. It does not modify S1's T1 sign-off.

| Old test | Old behavior | S2 test / location | Disposition | Reason / gap |
| --- | --- | --- | --- | --- |
| `T1-2341` (e2e own-enrollment) | Rejects stale approvals across transport isolation, request replacement and restart, then exchanges own messages | `S2#5` + `S2#3` (pairing proof/confirmation code, expiry/replay, restart) | REWRITE | S2-A has one read-only companion, not the old multi-device enrollment graph; old approval replacement is not implemented. |
| `T1-2400` (e2e self-sync) | A disconnected device keeps messages; when reachable, they arrive in original order | `S2#3` (offline, restart, `SyncStatus`, ordered catch-up) | REWRITE | S2-A is one-way A1→A2; no peer fan-out/self-sync acknowledgment in both directions. |
| `T1-2600` (ui enrollment) | Ready and keyless devices cannot use the wrong signing actions | `S2#5` + UI read-only gate | RETAIN BEHAVIOR | A2 is keyless; the test must be rewritten for the companion role and server API, not just UI. |
| `T1-2323` (e2e multi-device) | Two instances with different data directories do not read/write each other's identity | `S2#5` (separate A1/A2 stores; A2 cannot read A1 keys) | REWRITE | S2-A has one companion, not two writable devices; old data-directory isolation is retained in the companion test. |
| `T1-2405` (e2e self-sync) | C cannot decrypt messages after revocation while B keeps receiving | `S2#4` (A2 stops receiving after removal; A1/B continue) | REWRITE | S2-A has no direct C/A2 peer session; the local-removal behavior is tested, but contact-visible revocation is S2-B. |

## Explicit omissions

| Old behavior | Disposition | Reason | Sign-off |
| --- | --- | --- | --- |
| B directly addresses A2; multi-device fan-out | OMIT (S2-A) | S2-A keeps B on the S1 path; A2 is reachable only through A1. Deferred to S2-B. | pending |
| Full history sync (10k/10s, 1000/s) | OMIT (S2-A) | ADR-0003 S2 says post-join only; D's figure is a candidate, not an accepted S3 gate. | pending |
| Contact-visible signed revocation | OMIT (S2-A) | A2 does not speak to B; local removal is the S2-A boundary. Deferred to S2-B. | pending |
| Cross-device GBNV | OMIT (S2-A) | A2 mirrors A1's order; ADR-0003's GBNV commitment remains open. | pending |

No old test is silently dropped: each row states whether S2-A rewrites, retains the behavior, or explicitly omits it. Owner sign-off is required before this plan can claim S2 delivery.
