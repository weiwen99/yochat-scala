# Verification

Three layers, each with a different job. A pass at one layer never substitutes for another.

## Layer table

| Layer | Tool | What it proves | What it does not prove |
| --- | --- | --- | --- |
| Protocol | Quint + Apalache/TLC + replay | interleavings, timeouts, ordering, membership, retry bounds | Scala implementation correctness, real network |
| Implementation | Scala property/state-machine tests + real SQLite | state transitions, crash/restart, transactions | multi-process timing, GUI, real transport |
| Pure functions | unit/property tests; bounded proofs where available | parse/sequence/token edges | the rest of the system |
| Behavior | E2E on the real app | user-visible paths, restart, reconnect, UI | all interleavings |

## Protocol layer

- Model the kept protocol surface only. v1 has one path: relay-only (no direct attempts, no NAT traversal), single chat, write-before-send, offline retry.
- Time is explicit: every wait is a timeout action that can fire at any time, any number of times. Do not assume a wait eventually ends. Do not assume every device comes online.
- Liveness properties state fairness premises; each has a safety dual that does not depend on them. Remove the fairness premise as a negative control and confirm the property is falsified.
- `REQ_20260918_BXC7RNH3YCQZ9BQY` is read as "the wait ends under the stated fairness premise", with a safety dual. Do not claim unconditional termination.
- Retry, rebuild, and broadcast counts are bounded in every connected epoch or sliding window.
- After the design freezes, the model freezes. The implementation may follow the model; model edits are a separate, human-signed round with re-verification.
- A model check needs a clear `[ok]` verdict. Exit code 0 alone, a type check, a trace sample, or a finite-state exploration is not a proof.
- Replay: export a simulator trace and feed it step by step into the real Scala `step`; compare observable state. A mismatch fails the test.

Quint registry: `models/checks.json` with the main module, backend, invariants, temporal properties, max steps, and source hash. Public positive entry points end with `Checks`. A config that only observes traces must record the obligation it covers, the reason, and the source fingerprint.

## Implementation layer

- Property/state-machine tests use the same generators, properties, and reference model as the old Rust tests for kept features. Port the specification, not the file layout.
- Real SQLite, not a mock, for transaction and crash tests. Crash injection is a test feature, not a production path.
- A storage failure is never translated into "empty", "not found", or "no data". Existence and readability are separate answers.

## Behavior contracts

14 E2E scenarios for S1, fixed across refactors:

1. first invitation and reply;
2. add contact and show it on both sides;
3. send one message, both sides show it;
4. restart the sender, message remains;
5. restart the receiver, message remains;
6. disconnect and reconnect, pending message arrives;
7. 100 messages in order, no duplicates;
8. 1,000-message throughput;
9. wrong/expired invitation rejected;
10. tampered ciphertext rejected;
11. tampered signature rejected;
12. log and diagnostics contain no message content, contact names, public keys, or key material;
13. app lock and unlock;
14. a message that cannot be decrypted triggers channel rebuild, not silent loss.

Contact removal and blocking are S3, not S1; they are not a behavior contract for v1.

Safety gate (S1 security enhancement, not a new scenario count): after the handshake, `contacts.safety_verified` is false; `#1/#2/#3` must assert that no user body is sent or displayed until the out-of-band safety number is verified. Only control/rekey frames may flow before that.

Unit tests may be rewritten. Every bug fix starts with a failing case. Do not chase coverage percentages.

## Test parity (T1)

`docs/plans/.../test-parity.md` maps every old test for a kept feature:

| Old test | Feature | Layer | New test | Status | Reason if omitted | Human sign-off |
| --- | --- | --- | --- | --- | --- | --- |

- Port the same scenario/assertion, not the same framework.
- Kani proofs have no direct Scala equivalent; record the gap and replace with property tests where possible.
- An omitted or weakened test needs a written reason ("the test asserts an implementation detail" or "it conflicts with the real requirement") and a human decision. Silent deletion is a failure.
- Dependency upgrades must not turn the behavior contracts red. If they do, the test was coupled to an implementation detail; fix the test, not the contract.

## Performance gates

- 1,000 messages end-to-end <5s on the S1 setup.
- Cold start p50 ≤2s / p95 ≤4s (calibrate after the first spike).
- Idle memory ≤800MB.
- Same seed, same data, isolated processes; report median and p95; no heavy concurrent load.
- The gate stores numbers in a receipt. A number in a document is not a gate.

## Check scope

During development, run the checks the change actually affects and reuse results that are still valid. The full gate is for an integrable batch or before delivery. Only new changes, failures, or unresolved risks justify widening or repeating a check. Keep the required checks; do not replace them with a targeted subset.

## Test integrity

- Do not fabricate results. Do not claim a test passed when it was skipped, timed out, or produced no verdict.
- An incomplete receipt is not a pass. A batch that ran out of time is not a counterexample and not a pass.
- Gate red: isolate and rerun. A fixed failing location is this change's bug; a drifting location under load is a flake. Do not global-serialize with `-j 1` to hide it.
- Local unit tests are not production acceptance. Say what was actually run.

## Receipts

Checks write under `tmp/checks/<timestamp>/` (ignored). Each receipt records the command, tool versions, input hash, duration, verdict, and the log path. Only a complete receipt with an explicit verdict counts.
