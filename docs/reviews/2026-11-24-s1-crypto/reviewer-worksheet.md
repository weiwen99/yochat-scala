# Reviewer worksheet — the 33 open questions

**Purpose:** give the human external reviewer a starting point, not an answer key. Every row must be
re-derived by the reviewer from the frozen source; an "internal" result is not evidence and does not
count as a finding until the reviewer independently confirms it.

Status values:

- **no finding** — static read found no defect; the reviewer must still check it.
- **finding** — linked to `internal-review-2026-11-24.md`; the reviewer must re-verify and classify.
- **gap** — the repository does not contain evidence that can answer the question; the reviewer must
  request or perform the missing work.
- **decision** — a product/threat-model acceptance is needed; the code cannot decide it.

Baseline: source `6884926`, packet `21991d3`. Manifest verified (178 entries at this revision, `OK`;
source files unchanged) before this worksheet was written. Line numbers are from the baseline.

## A. Primitives and key separation

| Q | Question | Internal | Evidence | Reviewer must independently verify |
| ---: | --- | --- | --- | --- |
| 1 | Domain separation for every `info`, salt, AAD domain, signature/hash prefix | no finding | `core/src/yochat/core/Crypto.scala:16-19,60-68`; `Rekey.scala:6-8,240-256`; `StoreKeys.scala:10-25`; `StoreCrypto.scala:57-95`; `Invitation.scala:17-18`; `Reply.scala:7-10` | Re-enumerate every label, confirm no two purposes share a key space; check `"yochat-s1-reply"` is used as both HKDF info and signature domain (different primitives). |
| 2 | `rootKey0`/`rekeyBase0` from the same X25519 secret safe | no finding | `Reply.scala:113-118` (different salts/infos); `Crypto.scala:49-58` | Recompute both derivations from a fixed `shared`; confirm the labels/salts cannot collide. |
| 3 | Identity/device/one-time/ratchet/storage keys disjoint | no finding | `SessionSetup.scala`; `KeySeparation.scala:8-9`; `Invitation.scala:31-32`; `Reply.scala:28-31`; `Jce.scala:13-17` (distinct DER OIDs) | Check type usage (`Ed25519*` vs `X25519*`) and that no path feeds an identity key into DH. |
| 4 | Safety number 39 digits: unambiguous, collision-resistant, leading-zero handling | no finding (INFO) | `SafetyNumber.scala:19-31`; ADR-0006 §3.3 | Recompute the 128-bit → 39-digit zero-pad; confirm `BigInt(1, bytes)` is unsigned; decide whether the always-leading zeros and 128-bit entropy meet the manual-comparison goal. |
| 5 | Fresh 96-bit nonce per message key; any reuse path | no finding (M7/M3 related) | `Ratchet.scala:38-55,111-128`; `AppStep.scala:99-175`; `Rekey.scala:30-40,84-94` | Trace every `nonce` source: retries reuse the stored wire; control frames get fresh values; rebind gets a fresh nonce. Check crash windows: a candidate is persisted before send; no path re-encrypts with the same key. |

## B. Invitation / reply

| Q | Question | Internal | Evidence | Reviewer must independently verify |
| ---: | --- | --- | --- | --- |
| 6 | Canonical encoding covers version/account/expiry/keys; non-canonical rejected | no finding | `InvitationFields.scala:22-67`; `ReplyFields.scala:22-67`; `CanonicalReader.scala:41-42`; `Base64Url.scala:14-21` | Parse every field, check `finish`, check re-encode equality; try duplicate/trailing/overlong encodings. |
| 7 | Reply AEAD (`replyKey`, AAD, fields+signature) transplantable | no finding | `Reply.scala:47-52,74-98`; `ReplyEnvelope.scala:14-37` | Try to transplant a reply to another invitation/account/version; check the AEAD salt/AAD and the signature field coverage. |
| 8 | Invitation hash/session id binds both sides and both ephemeral/ratchet keys | no finding | `Reply.scala:113-118`; ADR-0006 §3.3 | Recompute `invitationHash`, `replyHash`, `sessionId`, `rootKey0`, `rekeyBase0` from a fixed transcript. |
| 9 | One-time private-key deletion observable/complete; no stale recovery | finding (m3), gap | `AppStep.scala:286-332,344-372`; `AppEffectExecutor.scala:382-391`; `Rekey.clearTerminal` only in `RekeySpec.scala:135-136` | Verify the invitation row is deleted on both paths; check the rekey R1/R2 retention and the M3 indefinite window. |
| 10 | Safety gate prevents body before verification in all paths (queued/rebound/restart/display) | no finding (B1 separate) | `Ratchet.scala:10-19,143`; `AppStep.scala:877-950,762-777`; `AppStateRestore.scala:137-145` | Enumerate every send path (`UserSend`, `RebindOutgoing`, resend), every display path (`ApiJson.visible`), and the `bindingEstablished=false` restore state. |

## C. Double Ratchet

| Q | Question | Internal | Evidence | Reviewer must independently verify |
| ---: | --- | --- | --- | --- |
| 11 | Compare to Signal rev 4: root order, DH steps, `PN/Ns/Nr`, skipped keys, B-first | no finding (m6) | `SessionSetup.scala`; `Ratchet.scala:164-242`; `RatchetSpec.scala:76-199` | Walk the state transitions against the spec; note the KAT provenance gap (m6). |
| 12 | Skipped keys bounded under gap and total; memory/CPU amplification | no finding (m7) | `Ratchet.scala:217-234`; `S1Limits.scala:6-8`; `StateCodec.scala:149-183` | Check `MaxSkipGap` and `MaxSkipTotal` together; note `MaxSkipTotal` has no test. |
| 13 | Stale/replay/duplicate/gap/AEAD failure distinguished without partial commit | no finding | `Ratchet.scala:130-162,164-185`; `ReceiveError.scala`; `AppStep.scala:975-1000` | Try each failure class; confirm the failure counter is the only persisted change and only on the current chain/new seq. |
| 14 | Signature ordered before AEAD; covers all header control fields and ciphertext | no finding | `Ratchet.scala:137-146`; `MessageHeader.scala:18-29`; `WireFrame.scala:19-31` | Check every kind; confirm the signature input is the parsed canonical header, and that parse rejects trailing bytes. |
| 15 | `seq` separated from `n`, per author, across rekey/restart; duplicate/gap abuse | no finding (M1/M2) | `MessageHeader.scala:18-29,66-71`; `AppStep.scala:212-240,778-809`; `AppStateRestore.scala:131-145` | Check the `seq` reconstruction on restart and the ACK bound; see M1. |
| 16 | First Data binding prevents an old body in a new relationship/session | no finding (M6) | `DataPayload.scala:25-83`; `AppStep.scala:762-870`; `AppStateRestore.scala:137-145` | Try mismatched accounts/safety/seq/session; check the restore `bindingEstablished` rule and the second-pairing path (M6). |

## D. Rekey and cutover

| Q | Question | Internal | Evidence | Reviewer must independently verify |
| ---: | --- | --- | --- | --- |
| 17 | Request/Accept/Confirm/Ack authenticated and bound; replay into another epoch/session | no finding (m12) | `RekeyRequest.scala:19-24,91-112`; `RekeyAccept.scala:20-25,96-118`; `Rekey.scala:59-151` | Check transcripts, keys, session/epoch/id binding; note the version byte is not in the transcript. |
| 18 | Cutover safe against lost ACK, crash, reconnect, duplicate first Data | **finding B2/B3** | `AppStep.scala:548-620,1009-1063`; `SessionRepository.scala:60-73` | Re-run the B2/B3 probes; check the responder path. |
| 19 | Rebind preserves `seq`/wire semantics; old/new mixing, duplicates, order | **finding M2/M3/M7** | `AppStep.scala:99-175,622-631`; `AppEffectExecutor.scala:318-350`; `MessageRepository.scala:304-366` | Check the `Migrated/Skipped` outcome, terminal blocking, and the ReadOnly ACK gate. |
| 20 | Rekey attempts/cooldowns bounded without a permanent remote block | **finding M4** | `Rekey.scala:50-57,59-122`; `AppStep.scala:439-507`; Plan 0007 §10.6 | Decide whether the accepted local-only budget satisfies ADR-0006 §6. |
| 21 | Failed rekey never leaks plaintext/key/unauthenticated success | no finding | `CoreError.scala`; `Notification`; `AppStep.scala:1034-1063` | Check every failure notification and every crash path; B3 is a visibility/timeout issue, not a leak. |

## E. Storage and local state

| Q | Question | Internal | Evidence | Reviewer must independently verify |
| ---: | --- | --- | --- | --- |
| 22 | Master→identity/database→per-value hierarchy; salts/infos distinct; identity separated | no finding | `StoreKeys.scala:10-25`; `Keys.scala`; `IdentityFile.scala:33-51` | Recompute the hierarchy; confirm no derived key is persisted. |
| 23 | Argon2id header authenticated; bounds; uniform `UnlockFailed`; downgrade/DoS | no finding (M9) | `KeyFile.scala:89-115,117-131` | Check the header AAD, bounds, and error uniformity; check the actual passphrase path (M9). |
| 24 | Envelope AAD complete and fixed-length; row/conversation/peer/generation/version copy | no finding (m7) | `StoreCrypto.scala:50-95`; `SessionRepository.scala:184-215`; `MessageRepository.scala:431-471`; `ContactRepository.scala:189-204` | Try session-row swaps (untested), message body/wire swaps, contact/invitation swaps. |
| 25 | `messages.body` bound to the exact wire; no re-encoded pair | no finding | `StoreCrypto.scala:73-89`; `MessageRepository.scala:431-471`; ADR-0007 §3 | Recompute the `wireHash` AAD on load; try a valid body with another wire. |
| 26 | High-water/generation reject partial rollback/stale writes; crash between row and meta | **finding M5** | `Sql.scala:73-85`; `SessionRepository.scala:265-317`; `ContactRepository.scala:20-39` | Re-run the M5 probe; check crash tests. |
| 27 | Coordinated DB+meta rollback acceptable; keychain anchor | decision | ADR-0007 §6; threat model A3 | Decide whether the documented limitation is acceptable for S1. |
| 28 | Backup/restore preserves properties; no non-atomic WAL copy | no finding (partial) | `Backup.scala:11-44`; `BackupSpec.scala` | Check `VACUUM INTO` outside a transaction, key-source copy, and restore with the keychain path. |

## F. Protocol composition and operational security

| Q | Question | Internal | Evidence | Reviewer must independently verify |
| ---: | --- | --- | --- | --- |
| 29 | Relay-visible metadata linkage beyond the documented threat model | no finding | `MessageHeader.scala`; `protocol.md` §3; threat model A1 | Confirm the header is the only relay-visible metadata and that no user/contact field leaks. |
| 30 | Logs/HTTP/crash/UI free of bodies, keys, contacts, safety numbers | **finding B1/M9** | `AppService.scala:123-169`; `ApiJson.scala:24-170`; `HttpApi.scala:44-58`; `Main.scala` | Re-check the idle-lock API leak and the passphrase path; check crash text. |
| 31 | All waits/retries/queues/skipped keys/rekey attempts/buffers bounded | **finding M4/M8, m13** | `S1Limits.scala`; `AppTypes.scala:230-244`; `RelayClient.scala:69-70,164`; `AppRuntime.scala:301-313`; `RelayServer.scala:103-128` | Check byte bounds, blocking writes, and the responder budget. |
| 32 | Platform/JCE behavior unspecified or provider-dependent | decision/gap | `Jce.scala`; `KeyFile.scala` (BouncyCastle); `Store.scala:430-446` (sqlite-jdbc native) | Decide the JDK/provider support matrix and native-access requirement. |
| 33 | Any test claim more than it proves (finite model, same-JVM, mock, source scan) | **finding m6-m9** | `evidence.md`; `VectorsSpec.scala`; `S1E2E.scala`; `RatchetSpec.scala` | Re-run the suites; check the receipts and the independent-vector gap. |

## Sign-off mapping

The reviewer's answers should be written into `findings-template.md` with a severity and a source
reference, then summarized in `reviewer-checklist.md`. "No finding" is valid only after the reviewer
has checked the row. The internal results above are not a substitute and carry no weight in the
external report.

The gate is closed only by a signed report from a human or institution with no conflict of interest.
No AI output, including this worksheet and the internal review, can be that signature.
