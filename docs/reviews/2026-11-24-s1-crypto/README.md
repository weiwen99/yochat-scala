# S1 external cryptography review

Status: **not ready for external review**. The internal adversarial review found 3 BLOCKER and
9 MAJOR defects. B1–B3 and M1–M7 are fixed in source `fce56e8`; M8 (byte-unbounded queues) and M9
(key-source/ADR mismatch) remain open. No external reviewer has been commissioned and none has signed
this package. The external cryptographic gate is **open**.

Baseline: source `fce56e8` on top of `6884926`; packet `b3871bd` plus this remediation record.
`manifest.sha256` covers the source snapshot and this packet's docs; it is regenerated at each packet
revision and verified before handoff. The full `./check.sh` gate passed at source `fce56e8`
(17/17 E2E, 1k 4,622 ms, 10k 34,006 ms, UI smoke 100/100).
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
8. `remediation-2026-11-24.md` — closure status for B1–B3/M1–M7 and the two remaining MAJOR items.
9. `reviewer-worksheet.md` — the 33 questions mapped to code and tests, with the independent
   verification each one still needs.
10. `reviewer-checklist.md` — sign-off checklist.
11. `findings-template.md` — required finding format.
12. `source-map.md` — where each implementation concern lives.
13. `manifest.sha256` — source files covered by this snapshot.

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
