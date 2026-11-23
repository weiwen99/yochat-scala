# Plan 0006: S1 privacy, lock, and automatic rebuild

- Plan: 0006
- Date: 2026-11-23
- Status: partially implemented (#12/#13 pass; #14 auto-rebuild pass, sequence-continuity #14 P1 open)
- Domain: privacy/diagnostics, app lock, protocol recovery
- Summary: Add E2E contracts #12 (logs/diagnostics contain no content, contact names, public keys, or key material), #13 (manual lock/unlock), and #14 (undecryptable message triggers bounded automatic rekey, not silent loss). Only #14 is expected to change production code: emit a non-durable `StartRekey` effect at the AEAD-failure threshold and execute it in the runtime.
- Live spec: `.scratch/yochat-s1-privacy-lock-rebuild/spec.md`
- Predecessor: Plan 0005 (merged `c21210b`), Plan 0004 first-message binding
- ADR: no new protocol format; #14 follows ADR-0006 §6 and existing Q5
- Review budget: 3 rounds; security/protocol review required
