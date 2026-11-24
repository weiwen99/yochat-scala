# Threat model

## Assets

- plaintext message bodies and conversation metadata;
- user/device Ed25519 private keys;
- session root/chain keys, message keys, skipped keys, rekey private material;
- local database master key and identity-file key;
- contact safety state (verified/mismatch/confirmation).

## Adversaries considered

### A1. Relay / network observer

Can observe device public keys, session IDs, ratchet public keys, counters, frame sizes, timing,
delivery/ACK traffic and relay availability. Can drop, delay, reorder, duplicate, truncate,
flip bits, and inject frames. Can forge its own routing/registration metadata.
Cannot read message plaintext or obtain session/private keys from correctly implemented traffic.

### A2. Offline data thief

Has a copy of the local data directory: SQLite database, WAL/SHM, `identity.bin`, and
possibly `keystore.bin`. Does not know the passphrase and does not have the OS keychain item.
Must not recover plaintext bodies, contact fields, session state, invitation private keys or master key.

### A3. Partial local tamperer

Can modify/delete/duplicate individual SQLite rows without coordinated rollback of the DB and
its key source. The design uses encrypted values plus monotonic high-water metadata to reject
partial rollback/copy/reinsert. Coordinated rollback of DB and `meta` is not detected (see limits).

### A4. Malicious peer

Has a legitimate relationship/invitation, can send malformed Data/ACK/Gap/rekey frames,
replay old frames, use stale ratchet keys, and attempt to exhaust buffers/rekey budgets.
Must be rejected/silently handled without state corruption.

### A5. Local unprivileged process / accidental logs

Must not find plaintext, passphrase, master key or private keys in application logs, crash text,
HTTP error bodies, or ordinary UI state beyond the documented `/api/state` UI contract.

## Not protected

- an attacker with the unlocked process, debugger/root, or OS keychain access;
- coerced disclosure or compromised OS;
- coordinated DB + `meta` rollback, whole-database replacement with a consistent old snapshot;
- local metadata leakage (existence/counts/timestamps/public keys/session ids);
- deletion or state tampering of messages/conversations that causes availability/order damage;
- power-loss loss of the tail of committed transactions on filesystems where `fsync` is not
  hardware-flushed;
- physical media sanitization or WAL history cleanup;
- relay-level traffic analysis, availability, Sybil/registration abuse;
- secure erasure of all in-memory key copies.

## Security claims that must not be strengthened

- Relay sees participant device keys and routing metadata; S1 does not claim participant anonymity.
- No external cryptographic audit exists yet; internal tests and Quint models are not a proof.
- The local database is application-layer encrypted; its SQLite header and non-sensitive metadata are visible.
- S1 has no recovery phrase, no master-key rotation, and no multi-device key continuity.
