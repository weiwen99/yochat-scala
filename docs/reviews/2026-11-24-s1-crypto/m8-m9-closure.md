# M8/M9 closure

Status: closed at source `6a81640`; the manifest records the exact hash. The owner risk-acceptance packet remains non-external.
This note records the two findings that previously blocked external handoff.

## M8 — byte-bounded inbound queues

- `transport/src/yochat/transport/ByteBudget.scala` adds non-blocking byte admission.
- `RelayClient` admits each parsed payload only if its byte size fits the default 8 MiB inbound
  budget; a frame over budget is shed before entering the queue. The budget is released when the
  consumer takes the item.
- `AppRuntime` reserves a second 8 MiB budget before enqueueing `RuntimeCommand.Inbound`; the event
  loop releases it on dequeue. A queue-full admission is rolled back.
- Inbound frames are therefore bounded by bytes, not only by the 1,024-entry relay queue and
  4,096-entry runtime queue. Worst-case admitted inbound memory is ~16 MiB plus framing/object
  overhead, not ~5 GiB.
- Tests: `transport/test/src/yochat/transport/ByteBudgetSpec.scala` covers exact reserve/release and
  a real relay client that drops a second 1 MiB frame under a one-frame budget.

## M9 — key-source/ADR consistency

- ADR-0007 is revision 6 and accepts the implemented path: Electron `safeStorage` encrypts the
  passphrase at rest; the preload IPC exposes it to the renderer; the renderer sends it in the
  loopback `POST /api/unlock` JSON body; the JVM derives the master through `keystore.bin` and
  Argon2id.
- There is no one-shot inherited pipe in this repository. The former ADR text and M9 finding
  described a mechanism that did not exist; the implementation and ADR are now aligned.
- Renderer/HTTP-body exposure is explicitly accepted inside the unlocked-process threat boundary;
  the passphrase must not enter argv, environment variables, logs, or crash reports.
- `G10` now records the Electron `safeStorage`/loopback path and the owner decision; a source-level
  check is `shell/main.js` / `shell/preload.js`, with the existing `keychain-smoke.js` and HTTP/E2E
  unlock tests.

## Verification

- `./mill transport.test` — ByteBudgetSpec 2/2, RelaySpec 7/7.
- `./mill server.test` — 8/8 test classes; AppRuntimeSpec 2/2.
- Full `./check.sh` at `6a81640`: PASS (563.99 s); E2E 17/17, 1k 3,795 ms, 10k 28,744 ms;
  cold start p50 1,194 ms / p95 1,208 ms; idle 742.5 MB; UI smoke 100/100.

## Residual

- M5/G13 two-process 1,000-message E2E is covered by E2E#8 (3,795 ms, warmup 1,000 at `6a81640`);
  the store-only throughput receipt does not replace it.
- Store retirement guard still counts only `Pending`; an unrepresented `Sent` row remains protected
  by core binding/outgoing checks, not by SQL alone.
- No independent external KAT/signature is claimed; the owner-signoff packet records risk acceptance.
