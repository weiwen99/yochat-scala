# S1 external cryptography review

Status: **candidate for external review; owner sign-off recorded, not externally signed**. The
repository owner confirmed acceptance with known limits on 2026-11-25
(`reviewer-checklist.md`). This is not an independent external reviewer signature. R3-N1 and R3-N2 are **closed** in rounds 4–10
(`internal-review-rounds-4-10.md`); the round-3 report is historical input. R11–R14 closed the
remaining deadline, timer-installation, Skipped, legacy-upgrade, Ack/retirement, ancestor, and
offline-recovery items (`internal-review-rounds-11-14.md`). Known limits remain: M8 (byte-unbounded
queues), M9 (key-source/ADR mismatch), the store retirement guard counts only `Pending` and not an
unrepresented `Sent` row, runtime multi-row `Migrated`/`Skipped` plus mid-batch store-failure coverage
is missing, and no external KAT/signature exists. No external reviewer has signed this package.
The external cryptographic gate is **open**.

Baseline: source `e2001ad` on top of `6884926`; packet `6acace9` plus this README correction. `manifest.sha256` covers the source snapshot
and this packet's docs; it is regenerated at each packet revision and verified before handoff. The
final `./check.sh` receipt (PASS 582.48 s; E2E 17/17; 10k 29,958 ms) is in
`internal-review-rounds-11-14.md`.

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
10. `internal-review-round3-2026-11-24.md` — historical re-review of `bb152f8`; R3-N1/R3-N2 were
    later closed in rounds 4–10.
11. `reviewer-worksheet.md` — the 33 questions mapped to code and tests, with the independent
    verification each one still needs.
12. `internal-review-rounds-4-10.md` — closure of R3-N1/R3-N2 and the original blockers.
13. `internal-review-rounds-11-14.md` — rounds 11–14: deadline/timer/Skipped/legacy/offline closure,
    final gate receipt, and remaining limits.
14. `reviewer-checklist.md` — sign-off checklist.
15. `findings-template.md` — required finding format.
16. `source-map.md` — where each implementation concern lives.
17. `manifest.sha256` — source files covered by this snapshot.

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

The external cryptographic gate is open. This package is ready to **hand** to an independent
reviewer with the known limits above; it is not ready to be treated as signed or approved.
A human or institution with no conflict of interest verifies the manifest, reproduces the evidence,
answers `open-questions.md`, and signs `reviewer-checklist.md`. M8/M9 and any reviewer findings must
be closed or explicitly accepted before signing. No internal review and no AI-generated document can
close this gate.
