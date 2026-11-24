# Questions for the external reviewer

Answer each with a severity and a source reference. "No finding" is a valid answer only after checking.

## A. Primitives and key separation

1. Are Ed25519, X25519, HKDF-SHA256, HMAC-SHA256 and ChaCha20-Poly1305 composed with sufficient
   domain separation? Check every `info`, salt, AAD domain, signature prefix and hash prefix.
2. Is `rootKey0`/`rekeyBase0` derivation from the same X25519 secret safe given the different salts/labels?
3. Are user identity, device, one-time X25519, ratchet, and storage keys kept in disjoint roles?
4. Is the 39-digit safety number construction (HKDF 16B, zero-padded decimal, split 20/19 by public-key
   byte order) unambiguous and collision-resistant enough for manual comparison? Any truncation/leading-zero
   ambiguity?
5. Is a fresh 96-bit random nonce per message key sufficient with ChaCha20-Poly1305 under the stated bounds?
   Is there any path that reuses a message key or nonce (retry, rebind, duplicate, restart)?

## B. Invitation/reply

6. Does canonical encoding cover every field that matters, including version, account/channel, expiry,
   ephemeral and ratchet public keys? Are non-canonical/duplicate fields rejected before verification?
7. Is the reply AEAD construction (`replyKey`, AAD, plaintext fields + signature) sound? Can an attacker
   transplant a reply to another invitation, account, or version?
8. Is the invitation hash/session-id derivation binding both sides and both ephemeral/ratchet keys?
9. Is one-time private-key deletion observable/complete enough? Is there a recovery path that leaves stale
   state usable?
10. Does the safety gate really prevent user body before verification in all paths, including queued/rebound
    messages, restart, and receiver-side display?

## C. Double Ratchet

11. Compare the implementation to Signal Double Ratchet spec rev 4: root KDF order, DH ratchet steps,
    `PN/Ns/Nr`, skipped-key handling, B-first initialization, and old-chain handling.
12. Are skipped keys bounded correctly under both `n-Nr` and total state size? Can a malicious peer force
    memory/CPU amplification or permanent state growth?
13. Are stale-ratchet, replay, duplicate, gap-too-large and AEAD failure distinguished without committing
    partial state or enabling a probing oracle?
14. Is signature verification ordered before AEAD in every path? Does the signature cover all header
    control fields and ciphertext?
15. Is `seq` correctly separated from ratchet `n`, per author, across rekey and restart? Can duplicate or
    gap handling be abused to deliver one body twice or reorder visible messages?
16. Does the first-message `DataPayload` binding prevent a valid old body from being applied to a new
    relationship/session? Check mismatched accounts, safety number, seq and session id.

## D. Rekey and cutover

17. Are Request/Accept/Confirm/Ack messages authenticated and bound to the intended peers/session? Can a
    rekey confirmation be replayed into another epoch/session?
18. Is the cutover rule (old session ReadOnly while old outgoing exists; initiator waits ConfirmAck;
    responder waits first authenticated new-session Data) safe against lost ACK, crash, reconnect and
    duplicate first Data?
19. Does rebinding old pending bodies preserve application `seq` and exact wire semantics? Can old and new
    session messages be mixed, duplicated, or displayed out of order?
20. Are rekey attempts/cooldowns/failure counts bounded without allowing a remote peer to permanently
    block messaging? Is the automatic-rebuild path distinguishable from an explicit user retry?
21. Does a failed rekey ever leak plaintext, a message key, or an unauthenticated "success" notification?

## E. Storage and local state

22. Is the master→identity/database→per-session/contact/invitation hierarchy sound? Are HKDF salts/infos
    distinct, and is `identity.bin` separated from database keys?
23. Is Argon2id parameter handling safe (authenticated header, bounded memory/iterations/parallelism,
    uniform `UnlockFailed`)? Any downgrade or DoS path?
24. Are envelope AAD fields complete and fixed-length? Can a session/message/contact row be copied to
    another row, conversation, peer, generation or version?
25. Is `messages.body` bound to the exact `wire` via SHA-256 without allowing a re-encoded body/wire pair?
26. Do high-water and lock-generation checks actually reject partial rollback, stale writes and replay
    after restart? What happens on crash between row update and `meta` update?
27. Is the documented coordinated DB+meta rollback limitation acceptable for S1? Is it detectable by the
    proposed keychain high-water on the target platform?
28. Does backup/restore preserve the security properties, and does it avoid copying a non-atomic WAL set?

## F. Protocol composition and operational security

29. Can relay-visible metadata (device keys, session id, counters, frame sizes/timing, rekey frames) be
    linked beyond the documented S1 threat model?
30. Are logs, HTTP errors, crash reports, UI state and performance output free of bodies, keys, contacts and
    safety numbers? Check both success and failure paths.
31. Are all waits, retries, queues, skipped keys, rekey attempts and buffers bounded? Any deadlock/livelock
    or unbounded allocation reachable from an unauthenticated frame?
32. Does the implementation rely on any platform/JCE behavior that is unspecified or provider-dependent?
33. Does any test claim more than it proves (finite model, same-JVM runtime, mocked transport, source scan)?
