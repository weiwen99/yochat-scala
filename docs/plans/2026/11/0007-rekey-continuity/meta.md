# Plan 0007: Rekey continuity and failure visibility

- Plan: 0007
- Date: 2026-11-23
- Status: design
- Domain: protocol recovery, persistence, diagnostics
- Summary: Close the remaining #14 gaps: 7A rebind unconfirmed outgoing messages from the old session to the newly active rekey session; 7B expose rebuild failure at a production-readable boundary and bound rebuilds across successful cutovers. Performance gates remain later.
- Live spec: `.scratch/yochat-rekey-continuity/spec.md`
- Predecessor: Plan 0006 stack (`647f785`), ADR-0006 §6, ADR-0008
- Review budget: 3 rounds; protocol/security review required
