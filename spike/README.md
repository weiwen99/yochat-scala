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

## Commands

```bash
./mill spike.run crypto
./mill spike.run demo --messages 100 --reconnect
./mill spike.run demo --messages 1000
./mill spike.run demo --messages 1000 --reconnect
```

## Style gates

`./check.sh` is the gate: scalafmt check, scalafix check, compile with
`-Werror`/`-Wunused:all`, the crypto harness, and a 100-message reconnect
smoke. `./fix.sh` applies scalafix and scalafmt fixes. The spike follows the
same Scala rules as the final project: no `var`, `while`, `null`, bare clock,
unsafe `.get`, or `sealed trait` in main sources; protocol state transitions
are immutable and errors use `Either`.

`crypto` prints `CRYPTO_CHECKS PASS`. `demo` prints one `SPIKE ...` line and
exits non-zero on failure. The relay/peer subcommands are used by `demo`;
they are not a supported interface.

## What it does not prove

No DH ratchet, no group/multi-device, no persistence or process restart, no
SQLCipher/app lock, no Pekko, no UI/Electron, no cold start/memory, no real
network/NAT, no relay authentication or quotas, no final wire format, and no
external crypto review. These are separate spikes or design work.

## Why JDK-only

The A/B question is whether the JVM can provide the S1 crypto and transport
without a Rust sidecar. Using JDK 25 JCE and JDK sockets isolates that
question from Pekko, ScalaJS and Electron. The final service still uses Pekko;
the final core is a pure state machine; the spike only shows that immutable chain helpers and JDK sockets are feasible.
