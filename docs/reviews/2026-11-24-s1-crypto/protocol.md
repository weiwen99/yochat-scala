# Protocol summary for review

This is a review map, not a replacement for ADR-0006/0008/0009. Line-level source references are
in `manifest.sha256`; read the ADRs for rationale.

## 1. Primitives

| Purpose | Construction |
| --- | --- |
| Identity/device signatures | Ed25519 (JDK 25 JCE) |
| Ephemeral/ratchet DH | X25519; all-zero shared secret rejected |
| Root KDF | HKDF-SHA256 (RFC 5869), local implementation |
| Chain KDF | HMAC-SHA256; next chain = HMAC(ck, 0x02), message key = HMAC(ck, 0x01) |
| AEAD | ChaCha20-Poly1305, 256-bit key, 96-bit random nonce |
| RNG | `SecureRandom` |
| Safety number | HKDF-SHA256 over sorted user public keys; 128-bit value as 39 zero-padded decimal digits |

Domain strings and key separation are listed in `storage.md` and the source. No custom block cipher,
hash, signature, or DH primitive is implemented.

## 2. Invitation/reply

### Invitation

Canonical binary fields (version 1): `userPub(32)`, `devicePub(32)`, channel label/account UTF-8
length-prefixed, `expiresAt(i64)`, `ephPub(32)`, `ratchetPub(32)`, note. Signature:
`Ed25519(user_priv, "yochat-s1-invite" || canonicalFields)`. Invitation text is base64url(canonicalFields || sig).
The invitation hash is SHA-256 over a separate domain and the canonical fields.

### Reply

Responder creates `B_eph`/`B_ratchet0`; canonical reply fields include `invitationHash`,
responder user/device keys, channel fields, `B_eph`/`B_ratchet0` and note.
`shared = X25519(B_eph_priv, A_eph_pub)`.
`replyKey = HKDF-SHA256(shared, salt=invitationHash, info="yochat-s1-reply", L=32)`.
Reply AEAD plaintext is canonical reply fields || Ed25519 signature; AAD binds
`"yochat-s1-reply-aad" || version || invitationHash || B_eph_pub`.
`sessionId = SHA256("yochat-s1-session" || invitationHash || replyHash)`.
`rootKey0` and `rekeyBase0` are derived from `shared` with separate labels/salts.
The reply envelope is signed by the responder user key; the inviter verifies signature, invitation hash,
version, `B_eph_pub`, account/expiry, and rejects replayed/duplicate replies. One-time private keys are
deleted after the reply handshake.

### Safety gate

After relationship establishment, `safety_verified=false`; user bodies cannot be sent or displayed
until the out-of-band safety number is checked. The first Data (`seq==1`) carries a canonical
`DataPayload` with both channel accounts and the 39-digit safety number; the receiver verifies it
before applying the body. `seq>1` must not carry the binding. A mismatch is rejected without body,
ACK, ratchet advance, or a second body.

## 3. Double Ratchet

Implemented as the two-party data path from Signal Double Ratchet spec revision 4 (2025-11-04),
without group sender keys or header encryption. It is not claimed to be wire-compatible with Signal.

- State: root key, send/receive chain keys, DH ratchet keypair/peer key, `Ns/Nr/PN`, bounded skipped
  keys, retired DH keys, phase/lock/safety/rekey metadata.
- `KDF_RK(rk, dh) = HKDF-SHA256(ikm=dh, salt=rk, info="yochat-s1-rk", L=64)`.
- Each DH ratchet performs receive-chain then send-chain root steps as in the spec.
- Send: advance send chain, derive one message key, generate fresh 96-bit nonce, sign header||ct with
  device key; persist candidate before publishing/sending.
- Receive: verify device/session/signature, check skipped keys, check retired DH, perform DH ratchet if
  the header DH is new, enforce `MAX_SKIP_GAP=64`/`MAX_SKIP_TOTAL=256`, then AEAD-open. Any failure
  discards the candidate; failed decode never commits skipped keys or ratchet advancement.
- Replay/duplicate is rejected before AEAD; a stale ratchet is distinct from a bad ciphertext.
- `B-first` (responder sends before initiator) is explicitly supported and tested.

### Wire header

Base header: `version u8=1`, `kind u8`, `sessionId 32B`, sender `devicePub 32B`, `dhPub 32B`,
`pn u64`, `n u64`, application `seq u64`, `nonce 12B`. Control frames
`RekeyConfirm=2`/`RekeyConfirmAck=3` append `rekeyEpoch u64 || rekeyId 16B`; they are signed and
AEAD-protected, do not consume application `seq`, and are idempotent by their control key.
`DataAck=4`/`Gap=5` are signed/AEAD-protected control frames; ACK is cumulative acknowledged data seq,
Gap is the next expected data seq; neither enters `messages`, ACK does not ACK controls.
Signature covers `"yochat-s1-msg" || header || ciphertext`.

## 4. ACK/Gap and bounds

- Data is applied in per-author application sequence order; a future sequence is buffered within
  `MaxBuffered=256`; missing data triggers one Gap for the current expected seq.
- ACK advances all sent/in-flight messages up to the cumulative seq to `Delivered`, removes them from
  the core outbox, and cancels delivery/retry timers. `Sent` is only transport acceptance; it is not
  delivery.
- Gap requeues only already-persisted wires; resend reuses the exact wire bytes.
- Window/limits: `MaxInFlight=32`, `MaxOutgoing=1024`, `MaxFailedRetained=64`, `MaxBuffered=256`,
  `MaxSendAttempts=3`, delivery timeout 5s, retry backoff 2s. Queue/retry paths are bounded.
- No priority queue and no infinite retry.

## 5. Rekey and cutover

- Request/Accept are authenticated relay payloads; Confirm/Ack are protected control frames in the
  new session. The initial `rekeyBase` is handshake-derived; each successful rekey derives a new
  `rekeyBase` for the new session while the old value authenticates only the old epoch. New root
  material is derived from fresh X25519 DH and root-key KDF; confirm/ack do not reuse a message key.
- Cutover keeps old sessions ReadOnly while old outgoing messages exist. Unconfirmed old messages are
  rebound to the new session in order, preserving application `seq`; persistence updates the session
  and reseals the body under the new session key/AAD in one transaction. Old sessions retire only after
  the role-specific evidence: initiator ConfirmAck, responder first authenticated new-session Data.
- A duplicate application `seq` on the first new-session Data is authenticated before it is treated as
  a duplicate; a successful duplicate persists the new ratchet and retires the old session without
  appending a second body. Failed authentication never retires.
- Local rekey attempts are capped at 3 per 60s window for explicit starts; automatic rebuilds have
  bounded attempt/cooldown behaviour. Notifications expose `rekey-required`/`rekey-failed`; failure
  is visible only after a real timeout, not as a successful body.
- Known limits: no multi-device key continuity; old pending/retry semantics across process crash need
  the tests listed in `evidence.md`; relay can observe rekey frame metadata.

## 6. Non-goals

- No deniability claim, no header encryption, no anonymous credentials, no traffic-analysis defense.
- No group ratchet; no one-time prekey server; no post-compromise security beyond the implemented
  Double Ratchet/rekey path.
- No security proof in this repository. The external reviewer must decide whether the design and
  implementation provide the claimed properties.
