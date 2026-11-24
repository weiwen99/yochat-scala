# S1 external cryptography review

Status: **not ready for external review**. Round 1 found 3 BLOCKER and 9 MAJOR defects. Round 2
verified B2, M1, M2, M3, M5, and the B1 read paths, but found B1 writes, B3 retry, and M7 batch
still open. Round 3 closes those three items and strengthens the M4/M6 tests. A round-3 re-review of
`bb152f8` (`internal-review-round3-2026-11-24.md`) verified those closures, but found two further
cutover defects: **R3-N1 (BLOCKER)** — after the last rebind, the old session row is deleted while
the old `AppState` session remains, so a late old-session Data fails `PersistInbound` and sets the
runtime fatal; **R3-N2 (MAJOR, G12)** — the responder retires its old session with `InitiatorAck`
before the first authenticated new-session Data. M8 (byte-unbounded queues) and M9 (key-source/ADR
mismatch) remain open. No external reviewer has been commissioned and none has signed this package.
The external cryptographic gate is **open**.

Baseline: source `bb152f8` on top of `6884926`; packet `43c6830` plus the round-2 note and
round-3 remediation. `manifest.sha256` covers the source snapshot
and this packet's docs; it is regenerated at each packet revision and verified before handoff. The
final `./check.sh` receipt is in `remediation-2026-11-24.md`.

Scope: the S1 two-party, one-device-each, relay-only YoChat implementation.

This package is for an independent cryptography reviewer. It describes the design as implemented,
identifies what has and has not been tested, and records known limits. Findings must be written into
`findings-template.md`; passing internal tests is not a cryptographic proof.

## Read in this order

1. `scope.md` — what is and is not being reviewed.
2. `threat-model.md` — adversary capabilities and explicit non-goals.
3. `protocol.md` — invitation/reply, ratchet, headers, ACK/Gap, rekey.
4. `storage.md` — master-key hierarchy, envelopes, AAD, rollback boundaries.
5. `evidence.md` — tests, vectors, models, performance receipts and commands.
6. `open-questions.md` — questions we want the reviewer to answer.
7. `internal-review-2026-11-24.md` — internal adversarial review; non-signing input, 3 BLOCKER /
   9 MAJOR. Read this before the checklist.
8. `internal-review-round2-2026-11-24.md` — re-review of the first fixes; historical input.
9. `remediation-2026-11-24.md` — round-2 corrections and round-3 closure/evidence.
10. `internal-review-round3-2026-11-24.md` — re-review of `bb152f8`: the named closures verified;
    R3-N1 (old session store/app split) and R3-N2 (responder retirement evidence) open.
11. `reviewer-worksheet.md` — the 33 questions mapped to code and tests, with the independent
    verification each one still needs.
12. `reviewer-checklist.md` — sign-off checklist.
13. `findings-template.md` — required finding format.
14. `source-map.md` — where each implementation concern lives.
15. `manifest.sha256` — source files covered by this snapshot.

## Boundary

S1 is not a production-audited messenger. The following are deliberately outside this review:

- group chat, multi-device, history sync, revocation propagation, file transfer;
- deniability, traffic-analysis resistance, coercion resistance, secure erase;
- malicious unlocked-process memory, root/admin compromise, physical attacks;
- the relay's authentication of devices (it is a dumb forwarding service).

## Rules for the reviewer

- Review the committed source and the cited tests; do not treat generated binaries as evidence.
- Report at least: file/line, spec/design sentence, problem, impact, exploit/precondition, recommended fix.
- Distinguish a specification problem from an implementation bug.
- Mark omitted assurance (e.g., no independent proof) explicitly; do not infer it from tests.
- A BLOCKER/MAJOR finding freezes release until fixed and re-reviewed.
- The internal review's findings are inputs, not a verdict. Re-verify each one; do not accept its
  severity or its "no finding" rows on trust.

## Gate closure

The external cryptographic gate is open. This package becomes ready for an external reviewer only
after M8/M9 are resolved and the fixed source gets a new commit and manifest.
A human or institution with no conflict of interest then verifies the manifest, reproduces the
evidence, answers `open-questions.md`, and signs `reviewer-checklist.md`. No internal review and no
AI-generated document can close this gate.
