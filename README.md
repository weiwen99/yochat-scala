# YoChat (有聊)

A no-account-server, end-to-end-encrypted, friend-to-friend messenger. S1 uses one minimal relay to forward ciphertext; without the relay, two devices on different networks cannot communicate. This repository is a full rewrite of the 2026-09..2026-11 implementation. The old implementation is kept read-only at `~/projects/yochat-reference`; its requirements are snapshotted under `docs/spec/`.

## Status

Plan 0001 is at the M4/M5 boundary, not merged. Plan 0002 closes the M3 structural review residuals (atomic handshakes, commit-before-publish, durable reply retries, rekey timeouts, bounded restore, Gap/attempt restore, bounded reply candidates, timer cleanup, idle/Lock generation); see `docs/plans/2026/11/0002-m3-structural-hardening/impl.md`. `core/` contains the protocol core and a pure application kernel (`yochat.core.app`: lock, invitations/safety, write-before-send, ordering, reconnect, rekey); `models/` contains the six finite Q1–Q6 Quint models with Apalache/TLC receipts; `store/` is doobie + SQLite + application-layer AEAD; `transport/` has the minimal relay, bounded framing, and a reconnecting client; `server/` has the single-writer runtime and a Pekko HTTP/WebSocket local service; `ui/` is the ScalaJS/Laminar surface; and `shell/` is the Electron desktop shell. Web and Electron share one interface; Electron adds hidden-inset window chrome, single-instance lifecycle, native notifications, and an OS-encrypted passphrase cache when the platform exposes `safeStorage`. Received invitations are persisted, and `AppState` is rebuilt from the store on startup. Still missing: the 15 E2E behavior contracts, two-process 1k/10k receipts, cold-start/memory receipts, and the external cryptography review.

## Read first

- `AGENTS.md` — the rules for working in this repo.
- `DESIGN-DISCIPLINE.md` — scale, reuse, review and verification discipline.
- `GLOSSARY.md` — the domain vocabulary.
- `docs/adr/` — decisions that are expensive to reverse.
- `docs/plans/2026/11/0001-yochat-rewrite/` — the rewrite plan.
- `docs/evaluations/` — the two 2026-11-20 postmortems that shaped the rules.
- `docs/references/2026-11-20-flw-yochat-diagrams/` — flw's complete notes package (six diagrams, glossary, offline reading page) and the prompts that produced them.
- `docs/references/2026-11-20-yochat-reference-study/` — our reading notes on the old implementation: status, requirement batching, contradictions, hard parts, glossary, and an offline reading page.

## Run the web surface

```sh
./mill server.assembly
java -jar out/transport/assembly.dest/out.jar 7654
java -jar out/server/assembly.dest/out.jar --port 8787 --data-dir "$HOME/.yochat" --relay 127.0.0.1:7654
open http://127.0.0.1:8787/
```

The first visit asks for a local passphrase and creates the device identity; later visits unlock the existing SQLite database. The server binds loopback only. `GET /api/session` reports the locked/unlocked bootstrap state, and `/api/events` is the pushed state channel used by the UI.

## Run the storage restore benchmark

```sh
./mill server.bench
```

Writes 10,000 inbound messages to a real SQLite store, then reports per-message append p50/p95 and full `AppStateRestore.load` p50/p95 after a separate warmup. This is a benchmark command, not part of `./check.sh`; the two-process 1k/10k E2E gates remain M5.

## Run the Electron shell

```sh
cd shell
npm install
npm start
```

The shell finds `../out/server/assembly.dest/out.jar`, starts it with the OS user-data directory, waits for the local service, and stops it on quit. Set `YOCHAT_SERVER_JAR` for another location and `YOCHAT_RELAY` for a different relay endpoint. If the platform exposes Electron `safeStorage`, the unlock panel offers to keep the local passphrase encrypted by the OS; otherwise it stays in the unlock form only.

## v1 scope (S1)

One user, one contact, one device each: add friend, single chat, write-before-send, restart without loss, reconnect after network loss. No multi-device, history sync, revocation propagation, group chat, or file transfer in v1.

## Stack

Scala 3 + Mill + JDK 25; Pekko HTTP for the local service; ScalaJS + Laminar for the UI; Electron for the desktop shell. No Rust in our product code. If the JVM-only path fails the S1 acceptance, the fallback is an iroh/p2panda sidecar per ADR-0002.

## The rewrite is the last one

After this rewrite, changes are module replacements behind behavior contracts and performance gates. No third full rewrite.
