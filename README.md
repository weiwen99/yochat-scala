# YoChat (有聊)

A serverless, end-to-end-encrypted, friend-to-friend messenger. This repository is a full rewrite of the 2026-09..2026-11 implementation. The old implementation is kept read-only at `~/projects/yochat-reference`; its requirements are snapshotted under `docs/spec/`.

## Status

Plan 0001 is at the M2/M3 boundary. `core/` contains the protocol core and a pure application kernel (`yochat.core.app`: lock, invitations/safety, write-before-send, ordering, reconnect, rekey); `models/` contains the five finite Q1–Q5 Quint models with Apalache/TLC receipts; and the production `store/` (doobie + SQLite + application-layer AEAD) exists. Q1–Q5 have fixed ITF traces replayed against the real Scala `Application.step` (Q5 as a safety-invariant projection); the `AppEffect` executor in `server/` persists through the store and calls an explicit transport sink. A real transport, runtime, UI/shell, the 14 E2E contracts and the two-process 1,000-message receipt are not implemented. The storage gates G8–G12/G15/G16 pass in `store/`; G10 keychain shell, G14 launcher, G13 E2E, and external cryptographic review remain open.

## Read first

- `AGENTS.md` — the rules for working in this repo.
- `DESIGN-DISCIPLINE.md` — scale, reuse, review and verification discipline.
- `GLOSSARY.md` — the domain vocabulary.
- `docs/adr/` — decisions that are expensive to reverse.
- `docs/plans/2026/11/0001-yochat-rewrite/` — the rewrite plan.
- `docs/evaluations/` — the two 2026-11-20 postmortems that shaped the rules.
- `docs/references/2026-11-20-flw-yochat-diagrams/` — flw's complete notes package (six diagrams, glossary, offline reading page) and the prompts that produced them.
- `docs/references/2026-11-20-yochat-reference-study/` — our reading notes on the old implementation: status, requirement batching, contradictions, hard parts, glossary, and an offline reading page.

## v1 scope (S1)

One user, one contact, one device each: add friend, single chat, write-before-send, restart without loss, reconnect after network loss. No multi-device, history sync, revocation propagation, group chat, or file transfer in v1.

## Stack

Scala 3 + Mill + JDK 25; Pekko HTTP for the local service; ScalaJS + Laminar for the UI; Electron for the desktop shell. No Rust in our product code. If the JVM-only path fails the S1 acceptance, the fallback is an iroh/p2panda sidecar per ADR-0002.

## The rewrite is the last one

After this rewrite, changes are module replacements behind behavior contracts and performance gates. No third full rewrite.
