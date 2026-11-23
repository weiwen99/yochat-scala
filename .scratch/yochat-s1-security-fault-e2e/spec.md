# Plan 0005 live spec

- Plan: 0005
- Design record: `docs/plans/2026/11/0005-s1-security-fault-e2e/`
- Status: implemented (check green; Round 2 review closed)
- Baseline: `4b55085`
- Review budget: 3 rounds.

## Gate

| ID | Finding | Required evidence |
| --- | --- | --- |
| S1 | Wrong/expired invitation is usable | Tampered invitation text and a valid-but-expired invitation both end with no usable contact; accept/receive returns an error appropriate to the observed path; no body is sent. |
| S2 | Tampered ciphertext is applied or silently lost | One real Data frame is corrupted in the ciphertext region; receiver does not apply the body; sender retries the original wire; receiver shows it once and sender ends `delivered`. |
| S3 | Tampered signature is applied or silently lost | Same as S2 with a signature-byte corruption; receiver does not apply the body; retry still delivers. |
| S4 | One dropped Data frame is not recovered by Gap/resend | Drop `seq=1` from A to B; B receives `seq=2`, sends a `Gap`, retries make both messages appear exactly once in order, and A ends `delivered` for both. |

## Sequence

1. Freeze this design and the test-only fault-relay contract; run review round 1.
2. Add `FaultRelay` and `withFaultPair`; keep existing `withPair` behavior unchanged.
3. Add E2E#15 first; observe whether the existing core/relay path emits `Gap` and recovers.
4. Add E2E#10/#11 using one-shot tamper at the wire boundary.
5. Add E2E#9 using API rejection plus a test-generated expired invitation.
6. Run focused E2E, then full `server.e2e`, `server.test`, and `./check.sh`; record receipts.
7. Freeze diff for rounds 2–3; close findings or withdraw them.

## Known non-goals

- No production protocol, schema, table, header, config, or UI change unless a contract fails for a product reason.
- No 1k/10k performance, cold start, idle memory, UI automation, or E2E#8/#12/#13/#14.
- Fault proxy tests one injected frame at a time; it is not a network fuzzer or a substitute for the real relay.
- No claim that arbitrary tampering/drop positions are covered.
