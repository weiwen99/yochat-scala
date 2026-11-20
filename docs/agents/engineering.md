# Engineering

Project-specific Scala and build rules. Read the `scala-best-practices` skill first; this file only records the project's deviations and additions.

## Toolchain

- Scala 3.10.x, Mill 1.3.x, JDK 25. Exact pins live in `build.mill` and the supported lock/config files; do not upgrade by memory.
- Pekko 1.7.x and Pekko HTTP 1.4.x for the local service; ScalaJS + Laminar for the UI; Electron for the shell.
- scalafmt and scalafix follow CFA: `maxColumn = 144`, `align.preset = most`, sorted imports; `ExplicitResultTypes`, `RemoveUnused`, `DisableSyntax` (`noVars`, `noNulls`, `noReturns`, `noWhileLoops`, `noAsInstanceOf`, `noIsInstanceOf`, `no-sealed-trait`) in main sources. Test sources may relax framework conventions only.
- `-Werror`; warnings are build failures. Unused imports/locals/params are failures.
- Gate entry: `./check.sh` (unicode safety, format, scalafix, compile, tests, performance). Auto-fix: `./fix.sh`. `just` recipes, if added, must call the same gates; no second gate list.

## Module layout

```
core/       pure state machines, protocol ADTs, domain newtypes
store/      SQLite, transactions, query plans
transport/  relay client, framing, reconnect, bounded queues
server/     Pekko HTTP local service, IPC, health
ui/         ScalaJS + Laminar client
shell/      Electron main process, native notifications/keychain/single instance
models/     Quint models and checks.json
tools/      check scripts, fixtures, spikes
```

`core/` does not depend on Pekko, JDBC, or the UI. Effects are executed by `server/`, `store/`, `transport/`, or `shell/`.

## Pure core

```scala
def step(state: State, event: Event): (State, Vector[Effect])
```

- `State` is serializable pure data; no handles, clocks, or connections.
- `Event` covers network arrival, user action, timer expiry, peer reachability change, and effect completion.
- `Effect` describes send/persist/start-timer/random; the executor performs it and feeds the result back as an `Event`.
- No `Instant.now()`, `Random`, IO, logging, or exception control flow in the core. Inject a `Clock`/`Random` or pass values in events.

## Data and ownership

- Pass domain types, structs, and borrows internally. Do not serialize/deserialize to copy, read fields, or adapt modules. Representation conversion belongs at network, SQLite, IPC, and import/export boundaries.
- Ciphertext, digests, signatures, and signed originals are byte data; never re-encode them as if that preserved the original.
- Move or borrow large state, bodies, buffers, and frames. Do not deep-copy for interface convenience. When multiple owners are required, share immutable data only and state the memory budget and release point.
- Do not hide nested allocations behind small top-level types, and do not use serialization to estimate cache size.
- Small fixed identifiers and a bounded number of crypto candidates may be copied; cloning an entire dynamic state is not allowed.

## State runtime

- Active state is bound to the unlock identity, a generation, and the encryption context.
- A single writer coordinates every state write entry. A cache hit never replaces the current authorization, version, or deletion check.
- Deletion operates on the pending struct. After final validation, each changed state is encoded once; cache the final object only after the transaction commits, then perform the durable effect.
- A failed attempt must not publish a candidate. Lock, identity replacement, and late results must not leave or resurrect old state. Do not patch a second cache after commit.

## Readability

- One statement, declaration, or control-flow step per line; do not pack several into one line.
- Blank lines separate semantic steps and responsibilities.
- Comment complex business rules, state transitions, boundary conditions, and non-obvious reasons; do not restate the code.
- Follow the existing style and formatter; do not introduce a second convention.

## Errors and effects

`Either[DomainError, A]` is the default; `Try` only at Java/library boundaries, converted immediately; exceptions only for unrecoverable bugs. Do not translate storage failures into "empty"/"not found" semantic answers; return existence and readability as separate answers.

All waits, queues, retries, broadcasts, and background steps are bounded. Every wait has a deadline; every retry has a cap; every queue has a capacity. No deadlock, no infinite wait.

## Storage

- SQLite through JDBC. The encryption choice (SQLCipher native vs application-level AEAD) is a spike in plan 0001; do not assume one.
- Every query that can grow is checked with `EXPLAIN QUERY PLAN`; no full-table blob scans. Add the index in the same change as the query.
- Transactions state the global lock order in the design. A read that can fail is not a read that returns empty.
- One authoritative writer per row/entity; do not let two fire-and-forget writers overwrite each other's fields.

## Size and deletion

- Production source file ≤2,000 lines; module ≤8,000 lines; v1 production ≤30,000 lines, tests ≤15,000 lines.
- A change that pushes a file or module over the limit must split or cut in the same change.
- Every feature commit deletes an equivalent amount of dead code. Dead code means unused public symbols, old paths, duplicated helpers, and comments that describe removed behavior.

## Performance

- 1,000 messages end-to-end <5s on the S1 setup; cold start p50 ≤2s / p95 ≤4s (calibrate after the first spike); idle memory ≤800MB.
- Measure with a fixed seed, fixed data, isolated processes, and no heavy concurrent load. Report median and p95, not a single best run.
- Performance results go into check receipts; a number in a document is not a gate.

## A-first and B fallback

A-first means no Rust in our product code. The JVM-only path must prove:

1. S1 acceptance end to end;
2. the security gates (key separation, E2E, signature verification, replay rejection);
3. the performance gates above.

If a time-boxed spike cannot prove these, write an ADR with the evidence and switch to B: iroh/p2panda as an external sidecar with a narrow IPC contract. Do not start writing Rust in this repo as an unplanned escape hatch.
