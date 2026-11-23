# Plan 0005: S1 rejection and fault E2E

- Plan: 0005
- Date: 2026-11-23
- Status: implemented (check green; Round 2 review closed)
- Domain: protocol/security, test harness
- Summary: Add E2E contracts #9 (wrong/expired invitation), #10 (tampered ciphertext), #11 (tampered signature), and #15 (one dropped data frame -> Gap/resend). Use a test-only fault-injecting relay proxy in front of the real relay; product code changes only if a contract exposes a defect.
- Live spec: `.scratch/yochat-s1-security-fault-e2e/spec.md`
- Predecessor: Plan 0004 (merged `4b55085`), Plan 0003 E2E harness
- ADR: none expected; no protocol/schema change
- Review budget: 3 rounds; protocol/security review required
