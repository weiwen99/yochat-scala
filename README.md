# YoChat (有聊)

A no-account-server, end-to-end-encrypted, friend-to-friend messenger. S1 uses one minimal relay to forward ciphertext; without the relay, two devices on different networks cannot communicate. This repository is a full rewrite of the 2026-09..2026-11 implementation. The old implementation is kept read-only at `~/projects/yochat-reference`; its requirements are snapshotted under `docs/spec/`.

## Status

M5 is in progress on `main`. Plans 0002–0007 close M3 structural hardening, first-message binding, functional E2E, privacy/lock/automatic rebuild, and rekey continuity. `core/` contains the protocol core and pure application kernel; `models/` contains Q1–Q6 finite models with Apalache/TLC receipts; `store/` is doobie + SQLite + application-layer AEAD; `transport/` has the relay and reconnecting client; `server/` has the single-writer runtime and HTTP service; `ui/` and `shell/` provide the Laminar and Electron surfaces.

Functional E2E now covers 16 behavior contracts plus `PERF#10K`: 1,000 measured messages under the 5s gate and 10,000 measured messages under the 60s gate, after explicit warmup. Cold-start/idle-memory gates are measured by `shell/perf-start.js`. The two-device web UI smoke (`shell/ui-smoke.js`) drives a directed invite/reply, blocks pre-safety sending, and sends 100 messages in each direction through the rendered UI. Remaining release work is Electron-window automation, final T1 parity sign-off, and external cryptography review.

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

Writes 10,000 inbound messages to a real SQLite store, then reports per-message append p50/p95 and full `AppStateRestore.load` p50/p95 after a separate warmup. This is a benchmark command, not part of `./check.sh`; the two-process 1k/10k gates live in `server.e2e`.

## Run the functional E2E contracts

```sh
./mill server.e2e
```

Run `npm ci` in `shell` first; `check.sh` uses Electron and Playwright. The service E2E starts one real relay and two real server JVMs with temporary data directories, then runs the S1 behavior contracts, including invite/reply, contacts, safety gate, restart, reconnect, 100 ordered messages, tamper/rejection paths, lock, automatic rebuild, Gap recovery, pending rebind after cutover, and the 1,000-message throughput gate. It also runs `PERF#10K`, the 10,000-message two-process gate. It writes a redacted receipt under `tmp/checks/`.

Run the two-device rendered-UI smoke:

```sh
node shell/ui-smoke.js
```

It starts a relay and two server JVMs, opens two Chrome contexts, creates both identities, exchanges an invitation, verifies both safety numbers, and sends/receives 100 messages.

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
