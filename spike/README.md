# A-first spike

A JDK-only feasibility harness for the S1 crypto and transport path. It is
**not production code** and not the final protocol.

See `docs/adr/0005-a-first-crypto-and-transport.md` and
`docs/plans/2026/11/0001-yochat-rewrite/spike-a-first.md` for the decision and
the recorded measurements.

## What it does

- Ed25519 invitation signing and reply signing.
- X25519 one-time invitation key; ChaCha20-Poly1305 reply encryption.
- Two-party hash-chain messages with a 64-slot out-of-order window and replay
  rejection.
- Length-prefixed TCP relay; device public keys register, ciphertext frames
  are routed.
- The demo starts a relay in the harness JVM and two **separate client JVMs**
  (Alice and Bob), exchanges messages, optionally reconnects Alice, and
  verifies the ACKs.
- `storage` proves SQLite + application-layer AEAD (WAL/SHM marker scan,
  row-binding, high-water rollback, query plans, 1,000 insert/read).
- `doobie` proves the doobie 1.0.0-RC14 / Hikari(max 1) / SQLite access-layer
  smoke: parameterized write/read, forced rollback, PRAGMAs, indexed lookup.

## Commands

```bash
./mill spike.run crypto
./mill spike.run storage
./mill spike.run doobie
./mill spike.run demo --messages 100 --reconnect
./mill spike.run demo --messages 1000
./mill spike.run demo --messages 1000 --reconnect
```

## Style gates

`./check.sh` is the gate: scalafmt check, scalafix check, compile with
`-Werror`/`-Wunused:all`, the crypto harness, storage spike, doobie access
smoke, and a 100-message reconnect smoke. `./fix.sh` applies scalafix and
scalafmt fixes. The spike follows the
same Scala rules as the final project: no `var`, `while`, `null`, bare clock,
unsafe `.get`, or `sealed trait` in main sources; protocol state transitions
are immutable and errors use `Either`.

`crypto` prints `CRYPTO_CHECKS PASS`. `demo` prints one `SPIKE ...` line and
exits non-zero on failure. The relay/peer subcommands are used by `demo`;
they are not a supported interface.

## What it does not prove

No DH ratchet, no group/multi-device, no production store/process restart, no
keychain/passphrase unlock, no app lock, no Pekko, no UI/Electron, no cold
start/memory, no real network/NAT, no relay authentication or quotas, no final
wire format, and no external crypto review. SQLCipher is rejected by ADR-0007,
not a missing spike. The storage/doobie commands are feasibility evidence, not
the production store.

## Why JDK-only

The A/B question is whether the JVM can provide the S1 crypto and transport
without a Rust sidecar. Using JDK 25 JCE and JDK sockets isolates that
question from Pekko, ScalaJS and Electron. The final service still uses Pekko;
the final core is a pure state machine; the spike only shows that immutable chain helpers and JDK sockets are feasible.
