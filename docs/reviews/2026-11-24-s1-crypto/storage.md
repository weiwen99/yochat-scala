# Storage encryption and key hierarchy

Source of truth: ADR-0007 (accepted), `store/src/yochat/store/Keys.scala`,
`StoreCrypto.scala`, and the repository implementations. S1 uses application-layer AEAD plus
ordinary SQLite; SQLCipher is not used.

## 1. Key hierarchy

```text
masterSecret (32B, keychain or passphrase-wrapped)
├─ identityKey  = HKDF-SHA256(masterSecret, salt="", info="yochat-store/identity/v1",  L=32)
└─ databaseKey  = HKDF-SHA256(masterSecret, salt="", info="yochat-store/database/v1", L=32)
   ├─ stateKey(sessionId)       = HKDF(databaseKey, salt=sessionId, info="yochat-store/session-state/v1")
   ├─ messageBodyKey(sessionId) = HKDF(databaseKey, salt=sessionId, info="yochat-store/message-body/v1")
   ├─ contactKey                = HKDF(databaseKey, salt="", info="yochat-store/contact/v1")
   └─ invitationKey             = HKDF(databaseKey, salt="", info="yochat-store/invitation/v1")
```

`masterSecret` is never written in clear to SQLite, `meta`, logs or env. Identity and database keys are
domain-separated. Per-session subkeys use `sessionId` as salt, reducing accidental cross-session reuse;
this does not protect if `databaseKey` itself leaks.

## 2. Key sources

- Preferred: OS keychain item `org.yochat.desktop` / `store-master-key-v1`, 32-byte master base64,
  passed to the JVM through a one-shot inherited pipe. Not argv, environment, logs, crash text or UI.
- Fallback: `keystore.bin` (0600), Argon2id v=19, memory 64 MiB, iterations 3, parallelism 1,
  salt 16B, output 32B; accepted bounds memory 16–256 MiB, iterations 1–10, parallelism 1–4.
  Parameters and salt are authenticated; out-of-range values return `UnlockFailed`.
- `keystore.bin` v1: `magic "YCKS" || version || kdf || memoryKiB || iterations || parallelism ||
  salt || nonce || wrappedMaster || tag`; AAD covers the header and a fixed domain.
- `identity.bin` v1: `magic "YCID" || version || nonce || ciphertext || tag`; plaintext is
  `user_priv || user_pub || device_priv || device_pub` (Ed25519). Key `identityKey`; AAD is a distinct
  identity-file domain. File 0600, directory 0700. S1 identity is immutable after creation.
- First run fails closed unless all four artifacts are absent (DB, identity, key file, keychain item).
  Partial combinations return `InstancePartiallyPresent`/`UnlockFailed`; no silent identity rebuild.
- Changing passphrase rewraps the same master; old `keystore.bin` copies still open it. S1 has no master
  rotation or recovery phrase. Leak response is delete instance and re-pair.

## 3. Envelope and AAD

Envelope: `magic "YCE1" || version || nonce(12) || ciphertext || tag(16)`; AEAD is
ChaCha20-Poly1305. AAD is `UTF-8 domain || 0x00 || fixed-length fields || big-endian i64`.
All AAD fields are fixed length; adding a variable field requires a new domain/version and ADR.
Writers do not re-serialize plaintext after decryption to change the signed/encrypted representation, except the explicit rekey rebind described below.

| Value | Key | AAD fields |
| --- | --- | --- |
| `sessions.state` | `stateKey(sessionId)` | domain + sessionId(32) + peerUserPub(32) + peerDevicePub(32) + generation(i64) + stateVersion(i64) |
| `messages.body` | `messageBodyKey(sessionId)` | domain + conversation(32) + session(32) + author(32) + device(32) + seq(i64) + SHA-256(wire)(32) |
| `contacts` sealed value | `contactKey` | domain + userPub(32) + version(i64) |
| `invitations` sealed value | `invitationKey` | domain + invitationId(32) + version(i64) |

`messages.wire` is already the protocol `header || ct || sig`; ordinary resend/Gap must reuse the exact
stored bytes. The one exception is rekey rebinding: a pending old-session message is re-encrypted under
the new session while preserving the application `seq`, then the new wire and body are committed before
sending. `messages.body` is local display plaintext sealed under the message-body key and bound to
`SHA-256(wire)`, so a body cannot be paired with a different wire. On load, header `session_id`/`device_pub`/`seq`
must match the row; mismatch is rejected, never re-signed/re-encrypted.

## 4. High-water marks, generation, transactions

- `meta` stores integer schema version, `lock-generation`, and per-row high-water marks; `meta` contains
  no secrets.
- `sessions.state` has `state_version`; updates use `UPDATE ... WHERE id=? AND state_version=?` (plus
  generation), require exactly one row, then write the matching exact high-water. No `excluded.value > value`
  silent no-op that could launder a rolled-back row.
- All write transactions compare `meta["lock-generation"]` inside the transaction; stale generations
  return `StaleGeneration` and write nothing.
- One executor writes; session state, message rows and required `meta` counters share one SQLite
  transaction. Lock order: `sessions` → `messages` → `meta`; no nested transactions.
- Candidate session state is persisted before publish/send; crash/cancel leaves the old committed
  state, never reuses a message key. Restart tests cover durable reply retry and session state.
- `state_version`/`version` are `i64 >= 1`, overflow checked; `generation >= 0`.
- SQLite uses WAL and explicit foreign keys; running DB files are not a consistent backup. Backup uses
  Online Backup API or `VACUUM INTO` in a raw transaction, plus `identity.bin` and the applicable key source.

## 5. Explicit local limits

- SQLite header and structural metadata are visible; only values are sealed.
- Contact/message counts, timestamps, public keys, session ids and message lengths may be inferable.
- Coordinated rollback of DB + `meta` to an older consistent snapshot is not detected. Partial
  rollback/copy/reinsert is rejected by high-water/AAD.
- Deletion/availability attacks are not prevented.
- `identity.bin` has no rollback versioning in S1; identity replacement requires a new instance/master.
- No master rotation, no recovery phrase, no secure erase of every in-memory/Java copy, no WAL history
  scrubbing beyond normal SQLite behavior.
- A compromised unlocked process or keychain can read plaintext; this is out of scope.
