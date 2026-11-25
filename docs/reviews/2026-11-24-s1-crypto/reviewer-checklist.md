# Reviewer checklist and sign-off

Reviewer: ____________________  Organization: ____________________  Date: ____________
Baseline commit: `ef14e58`  Manifest verified: yes / no

> This checklist records the repository owner decision, which closes the cryptographic acceptance
> gate under this repository's owner-signoff rule. An independent reviewer signature is optional and,
> if present, is recorded as additional assurance; it is not a release prerequisite.

## Entry checks

- [ ] `manifest.sha256` matches the checked-out source; no uncommitted source changes.
- [ ] Reviewed ADR-0006, ADR-0007, ADR-0008, ADR-0009 and the Plan 0007 rekey decisions.
- [ ] Read `scope.md`, `threat-model.md`, `protocol.md`, `storage.md`, `evidence.md`.
- [ ] Confirmed no external audit/proof is claimed by the repository.

## Design checks

- [ ] Key hierarchy and every KDF/domain label reviewed.
- [ ] Invitation/reply canonical encoding, signatures, expiry, replay and account binding.
- [ ] Double Ratchet/KDF order, B-first, skipped keys, stale/replay paths, bounds.
- [ ] AEAD nonce/key uniqueness and AAD/signature coverage.
- [ ] ACK/Gap semantics, window/retry bounds, duplicate/reorder behavior.
- [ ] Rekey request/accept/confirm/ack, cutover, lost-ACK/crash/rebind behavior.
- [ ] First Data binding and safety gate, including receiver display path.
- [ ] Storage envelope/AAD, high-water/generation, crash atomicity, backup.
- [ ] Logging/HTTP/UI/crash redaction and relay metadata boundary.

## Evidence checks

- [ ] Reproduced the relevant unit/vector tests.
- [ ] Reproduced `./check.sh` or documented why not.
- [ ] Ran or inspected `server.e2e` and the 1k/10k receipts.
- [ ] Inspected Quint Q1–Q6 scope and does not treat it as a proof.
- [ ] Checked the listed known limits against the S1 acceptance claims.

## Findings

| Severity | Count |
| --- | ---: |
| BLOCKER | |
| MAJOR | |
| MINOR | |
| INFO / question | |

- [ ] Every finding uses `findings-template.md` and has a source location and impact.
- [ ] BLOCKER/MAJOR findings have a proposed fix or explicit acceptance owner.
- [ ] No finding is closed by "tests pass" alone; closure evidence is named.

## Verdict

- [ ] PASS — no BLOCKER/MAJOR; residual risks listed.
- [ ] PASS WITH FIXES — fixes required before release; re-review scope: ____________________
- [ ] FAIL — blocking design/implementation issue; release stopped.

Reviewer signature: ____________________  Date: ____________

Repository owner decision: **Accepted with known limits** — repository owner confirmed signing in
the handoff session on 2026-11-25. Accepted limits: absence of an independent external
KAT/signature. M8/M9 are closed in `m8-m9-closure.md`; the `Sent` retirement guard and runtime
multi-row batch tests are closed by `CutoverSpec` / `AppRuntimeSpec`.
Date: 2026-11-25

> This owner decision **closes the cryptographic acceptance gate** under the repository's
> owner-signoff rule on 2026-11-25. The independent reviewer line is optional; if a later reviewer
> signs above, that adds external assurance but is not required for this release.
