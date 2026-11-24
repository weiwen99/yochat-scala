# S1 external cryptography review

Status: **ready for external review**; no external reviewer has signed this package.
Baseline: `main` at `6884926` (2026-11-24).
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
7. `reviewer-checklist.md` — sign-off checklist.
8. `findings-template.md` — required finding format.
9. `source-map.md` — where each implementation concern lives.
10. `manifest.sha256` — source files covered by this snapshot.

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
