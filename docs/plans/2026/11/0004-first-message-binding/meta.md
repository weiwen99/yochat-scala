# Plan 0004: S1 first-message account/safety binding

- Plan: 0004
- Date: 2026-11-23
- Status: implemented (check green; Round 2 review closed)
- Domain: protocol/security
- Summary: Implement ADR-0006 §3.3 by adding a canonical AEAD-plaintext `DataPayload` envelope; the first Data per author (seq=1) carries both channel accounts and the 39-digit safety number; receivers reject missing/mismatched/misplaced binding without applying the frame.
- Live spec: `.scratch/yochat-first-message-binding/spec.md`
- Predecessor: Plan 0003 (merged `4445297`), ADR-0006 §3.3, ADR-0008
- ADR: `docs/adr/0009-first-message-binding-payload.md`
- Review budget: 3 rounds; protocol/security review required
