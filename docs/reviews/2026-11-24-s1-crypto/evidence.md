# Evidence and reproducible checks

No external reviewer has validated this evidence. Internal tests, vectors and finite models are
supporting material, not a cryptographic proof.

## Commands

```sh
./check.sh
./mill server.e2e
./mill core.test
./mill store.test
./mill transport.test
./mill server.test
./mill spike.run crypto
./mill spike.run storage
./mill spike.run doobie
./mill spike.run demo --messages 100 --reconnect
tools/t1_signoff_check.py
```

`./check.sh` includes format, compile, unit/integration tests, Quint checks, DICE checks, and the
100-message E2E smoke. `server.e2e` contains the 17 S1 acceptance rows, including 1k/10k receipts.
Performance gates are hard but are not evidence of cryptographic security.

## Receipts recorded at the signed baseline

| Check | Result |
| --- | --- |
| `./check.sh` | full run completed ~400s; format, compile, core/store/transport/server tests and DICE passed |
| `server.test` | `./mill server.test` SUCCESS. The `399/399` line is a Mill task index, not a test count; per-suite MUnit totals are the receipt. |
| `core.test` | `./mill core.test` SUCCESS; protocol property/replay/vector tests included. The `145/145` line is a Mill task index, not a test count. |
| `store.test` | `./mill store.test` SUCCESS; AEAD, high-water, generation, restart/crash tests. The `198/198` line is a Mill task index, not a test count. |
| `transport.test` | 7 tests, 0 failed; relay/reconnect |
| `server.e2e` | 17/17 PASS; two separate server JVM processes, real relay, separate SQLite data dirs |
| UI smoke | 2 windows / 100 messages each direction, 0 API failures; restart/reconnect/rekey smoke |
| 1k gate | 1,000 data messages: 4,069 ms end-to-end, under 5 s |
| 10k gate | 10,000 data messages: 32,719 ms, under 60 s; ACK window 32 |
| cold start | p50 1,279 ms, p95 1,315 ms (Electron+JVM); idle memory 749.9 MB |
| `server.bench` | 10k appends + 20 full restores; not an E2E substitute |

Exact machine/toolchain: macOS arm64, JDK 25, Mill, repo commit `6884926`; UI counts from Playwright
smoke, not a screenshot-only claim. Numbers are historical receipts, not reproducible guarantees on
another host.

Mill prints `N/N` (a task counter) even for `testOnly` of a single class, so it is not a test count.
Report per-suite MUnit totals, or the actual number of tests, in any final receipt.

## What the tests cover

- JCE primitive vectors: Ed25519, X25519, HKDF-SHA256, HMAC-SHA256, ChaCha20-Poly1305.
- Invitation/reply canonical parsing, signature failures, replayed replies, invitation hash/account binding.
- Ratchet: initial root, B-first, DH ratchet, out-of-order/skipped keys, replay, stale ratchet,
  GapTooLarge, skip limits, AEAD/signature tamper, duplicate application seq.
- First Data binding: missing/wrong accounts or safety number, seq==1 rule, body withheld on mismatch.
- Rekey: request/accept/confirm/ack, timeout, cooldown, failure/abort, old pending rebind, cutover to new
  session, duplicate first new Data, old session retire, rebind of pending old-session messages; ordinary resend/Gap reuses stored wire
- Storage: passphrase/identity creation, partial-instance failure, wrong master, AAD row binding,
  state high-water rollback, stale generation, crash/restart, backup/restore.
- Transport: relay forwarding, offline queueing, reconnect, dropped frame, duplicate frame, ordering.
- E2E: two real services with separate data dirs, real relay, invite/reply/safety, bidirectional messages,
  restart, reconnect, rekey continuity, drop/gap/resend, 1k/10k throughput.
- Quint: Q1–Q6 finite model checks; a pass is not a proof of unbounded behavior.

## What is not covered

- No independent cryptanalysis, no formal computational proof, no side-channel or memory-remnant audit.
- No proof that random nonces never collide; relies on 96-bit `SecureRandom` and unique message keys.
- No proof that SQLite/WAL leaves no historical plaintext or metadata.
- No coordinated DB+meta rollback detection, no master rotation/recovery, no multi-device continuity.
- No relay anonymity or traffic-analysis resistance.
- No guarantee that OS keychain/Electron bridge is uncompromised; the shell is not in this repository's test boundary.
