# S1 external cryptography review

Status: **not ready for external review**. An internal adversarial review (`internal-review-2026-11-24.md`)
found 3 BLOCKER and 9 MAJOR defects, including two rekey/cutover failures that Plan 0007 required to be
fixed and one idle-lock HTTP leak. No external reviewer has been commissioned and none has signed this
package. The external cryptographic gate is **open**.

Baseline: source `main` at `6884926` (2026-11-24); packet at `21991d3` plus the uncommitted internal
review and worksheet. `manifest.sha256` covers the source snapshot and this packet's docs (178 entries
at this revision; source files unchanged from `6884926`; verified).
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
   9 MAJOR. Read this before the checklist: several rows are already known failures.
8. `reviewer-worksheet.md` — the 33 questions mapped to code and tests, with the independent
   verification each one still needs.
9. `reviewer-checklist.md` — sign-off checklist.
10. `findings-template.md` — required finding format.
11. `source-map.md` — where each implementation concern lives.
12. `manifest.sha256` — source files covered by this snapshot.

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
after B1–B3 and M1–M7 are fixed and re-tested; the fixed source gets a new commit and a new manifest.
A human or institution with no conflict of interest then verifies the manifest, reproduces the
evidence, answers `open-questions.md`, and signs `reviewer-checklist.md`. No internal review and no
AI-generated document can close this gate.
