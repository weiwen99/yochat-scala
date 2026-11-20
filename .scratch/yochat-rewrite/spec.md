# YoChat rewrite — live spec

Plan: 0001
Status: design

## v1 (S1)

One user, one contact, one device each:

- add friend through an invitation and reply;
- one-to-one chat;
- write to local storage before send;
- restart without losing messages;
- reconnect and continue after a network drop.

## Out of scope for v1

Multi-device sync, history transfer to a new device, revocation propagation, group chat, file transfer, release-grade WCAG audit, stranger admission budgets, a public privacy notice page.

## Open spikes

- JVM-only transport and crypto feasibility (A-first). Fallback trigger: if a time-boxed spike cannot deliver the S1 acceptance, or cannot meet the security/performance gates, switch to the iroh/p2panda sidecar (B) with an ADR.
- SQLCipher on the JVM (JDBC driver or application-level encryption).
- Electron ↔ JVM process boundary and keychain access.
- Laminar virtual list at the S1 message volume (100k is not a v1 target).

## Acceptance

Two devices (one per user) exchange 100 messages; both restart without loss; disconnect and reconnect; no command line.
