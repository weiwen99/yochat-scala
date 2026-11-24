# Internal adversarial review — S1 crypto and cutover

**Status: internal, non-signing. This is NOT the independent external cryptographic review and cannot
close that gate.** The external gate stays open regardless of what follows.

| Item | Value |
| --- | --- |
| Source baseline | `6884926` (`main`, 2026-11-24) |
| Packet baseline | `21991d3` |
| Manifest | `manifest.sha256`, 178 entries at this revision (176 at the source baseline), verified `OK`; source files unchanged from `6884926` |
| Reviewer | omp (AI assistant, `deepseek-flash`) |
| Conflict of interest | The reviewer participated in implementation and in writing this packet. It is not independent. |
| Reproduced suites | `core.test` 145/145, `store.test` 25/25, `server.test` 399/399 (`./mill core.test`, `./mill store.test`, `./mill server.test`) |
| Scratch probes | Five, listed in §4; all deleted after the run. No product source was changed. |

This document is an **input to** a human external review, not a substitute. Its verdict is a release
recommendation for the owner, not a cryptographic assurance.

## 0. Verdict

**Do not send this packet to an external reviewer as a finished baseline.** Internal adversarial
review found **3 BLOCKER and 9 MAJOR** defects. Two blockers are in the rekey/cutover lifecycle that
Plan 0007 explicitly required to be fixed; one is in the idle-lock HTTP boundary. Each has a named
closure test.

No key/nonce reuse, signature-coverage, AAD-coverage, or KDF domain-separation defect was found in the
reviewed crypto core (§5). The blockers are lifecycle and API-boundary defects, not primitive
composition. That distinction does not make them less blocking: they break the session the crypto
protects.

## 1. Findings summary

| ID | Sev. | Area | One-line |
| --- | --- | --- | --- |
| B1 | BLOCKER | lock / HTTP | Idle auto-lock does not gate `/api/state`, `/api/session`, or `/api/conversations/*/messages`; bodies and accounts remain readable. |
| B2 | BLOCKER | rekey | A stale rekey timeout aborts a completed cutover and deletes the Active new session (Plan 0007 §10.3). |
| B3 | BLOCKER | rekey | A lost `SendRekeyConfirm` is never retried after restore; the pending new session is aborted after 5 s (Plan 0007 §10.4/§12.2). |
| M1 | MAJOR | ACK | A peer-signed cumulative ACK beyond the highest sent seq marks unsent messages `Delivered` and purges them. |
| M2 | MAJOR | cutover | A `ReadOnly` old session applies late Data but cannot emit ACK/Gap; responder-side pending messages are not rebound at cutover. |
| M3 | MAJOR | cutover | A `Failed` old-session message blocks retirement forever and is never rebound (Plan 0007 §11.3). |
| M4 | MAJOR | rekey | The responder has no attempt/cooldown budget; a peer can keep the session `ReadOnly` and block sends indefinitely (Q20). |
| M5 | MAJOR | storage | `insertSession`/`insertContact` reset the per-row high-water tombstone to 1; delete→reinsert succeeds through the store API (ADR-0007 G5). |
| M6 | MAJOR | invitation | Two concurrent accepts of one invitation mark the contact `AccountMismatch`, requiring re-pairing. |
| M7 | MAJOR | cutover | `PersistRebound` discards `Migrated`/`Skipped`; a `Skipped` rebind still publishes a new wire and ratchet state (Plan 0007 §10.1). |
| M8 | MAJOR | resource | Relay-client and runtime queues are bounded by entry count only: ~5 GiB of attacker-chosen frames can be admitted. |
| M9 | MAJOR | key source | The packet claims a one-shot key pipe; the implementation sends the passphrase from the Electron renderer to `POST /api/unlock`. |

Minor/INFO items are in §6.

## 2. Blockers

### B1 — Idle auto-lock does not gate the HTTP read paths

- Severity: BLOCKER
- Area: lock / HTTP / A5
- Source: `core/src/yochat/core/app/AppStep.scala:35-37` (`IdleTimeout` → `lock`),
  `core/src/yochat/core/app/AppStep.scala:633-651` (`lock` mutates `AppState` only),
  `server/src/yochat/server/AppService.scala:106-116` (explicit lock clears `sessionRef`),
  `server/src/yochat/server/AppService.scala:123-169` (`stateJson`/`sessionJson`/`messagesJson`),
  `server/src/yochat/server/AppService.scala:290-296` (`withRunning` gates on `sessionRef` only),
  `server/src/yochat/server/ApiJson.scala:24-51,104-170` (emits contacts, safety code, last body).
- Design/spec: Plan 0006 §4 #13, steps 2–3: after lock, `GET /api/session` must return
  `unlocked=false` with **no contacts/conversations/identity**, and `GET /api/state` /
  `GET /api/conversations/{id}/messages` must return **401**.
- Problem: an idle timeout changes only the pure state to `Locked`. `sessionRef` is cleared only by
  the explicit `POST /api/lock` path. `ApiJson.state` does not consult `state.lock.isUnlocked`; it
  emits contacts, `channelAccount`, `safetyCode`, and the last body. `messagesJson` reads the store
  and returns bodies regardless of the lock.
- Preconditions / exploit: after the configured idle timeout fires (default 24 h), any local
  process sends `GET /api/session`, `/api/state`, or `/api/conversations/{id}/messages`. The API
  reports `"unlocked": false` while returning the data.
- Impact: plaintext message bodies (up to 1,000 rows per request), contact accounts, safety numbers,
  and invitation text remain readable after the UI is supposed to be locked. This is the S1 privacy
  lock invariant, not an optional feature.
- Existing coverage: E2E#13 (`server/test/src/yochat/server/e2e/S1E2E.scala:490-506`) tests only the
  explicit lock. The idle path is tested only in the pure kernel
  (`core/test/src/yochat/core/app/AppLockSpec.scala:116-131`).
- Recommended fix: gate `stateJson`, `sessionJson`, and `messagesJson` on
  `state.lock.isUnlocked` (return `ServiceError.Locked`/401 and the locked JSON), or make the
  runtime notify `AppService` on any lock transition so `sessionRef` is cleared. Add an API-level
  test with an injected short idle timeout: after the timer fires, `/api/state` and
  `/api/conversations/*/messages` are 401 and `/api/session` has no data keys.
- Closure evidence: the API-level idle test above; E2E#13 extended to include idle, not only manual
  lock.
- Residual risk: a local process can still read the SQLite file; that is explicitly out of scope.
  This finding is about the documented 401 boundary, not about at-rest confidentiality.

### B2 — Stale rekey timeout deletes the Active new session

- Severity: BLOCKER
- Area: rekey / cutover / store
- Source: `core/src/yochat/core/app/AppStep.scala:1009-1023` (`rekeyTimers` installs a timer for
  every session with `rekey`, without checking the target's phase),
  `core/src/yochat/core/app/AppStep.scala:180-181` (timer → `abortRekey`),
  `core/src/yochat/core/app/AppStep.scala:1034-1063` (`abortRekey` removes `newSessionId` from state
  and emits `deletePendingSessions`), `store/src/yochat/store/SessionRepository.scala:60-73`
  (`deletePendingProgram` checks generation and pending messages, **not phase**).
- Design/spec: Plan 0007 §10.3 item 3 / §11.1: when the target new session is `Active`, never
  install an aborting old timer; an old timer may clear itself but must not delete the new session
  or reactivate the old one.
- Problem: after the initiator receives `ConfirmAck`, the old session is kept `ReadOnly` with
  `rekey = Accepted` while any old outgoing exists. On the next `Unlock` or `ConnectionChanged(true)`,
  `rekeyTimers` installs `Rekey(oldSession, Initiator)`. When it fires, `abortRekey` sets the old
  session `Active` and schedules deletion of the **Active** new session. The store then deletes it:
  `pendingCount` counts only `Pending` rows, and there is no phase check.
- Preconditions / exploit: initiator completes the cutover but still has a pending or failed old
  message (the normal rebind case); process restarts or reconnects before the rebind finishes, or a
  rebind is blocked; 5 s later the timer fires. A `Failed` old message makes this permanent (M3).
- Impact: the peer is on the new session while the local side reverts to the old session. If any
  message row references the deleted new session, the next `AppStateRestore` fails with
  `outgoing-session` and the app cannot start. Without such a row, the relationship is desynced until
  a new rekey or re-pair.
- Reproduced: probe §4.1; output
  `installed=Some(Rekey(<old>,Initiator)) newActive=Active` then
  `oldPhase=Some(Active) newStillPresent=false deletePending=Vector(Vector(<new>))`.
- Recommended fix: implement the three-state restore of §10.3: install a timeout only when the
  target is absent or `Pending`; for an `Active` target, cancel/ignore old timers and never delete
  it. Add a phase check to `deletePendingProgram` (only `Pending` may be deleted). Add the
  regression test with an old pending/Failed message and a restart.
- Closure evidence: the probe above turns into a product test; plus a store test that
  `deletePendingProgram` rejects an `Active` row.
- Residual risk: none for the crypto; this is a lifecycle bug.

### B3 — Lost Confirm is never retried; the rekey aborts after 5 s

- Severity: BLOCKER
- Area: rekey / recovery
- Source: `core/src/yochat/core/app/AppStep.scala:543` (the only `SendRekeyConfirm`),
  `core/src/yochat/core/app/AppStep.scala:1009-1023` (restore installs only a timeout),
  `core/src/yochat/core/app/AppStep.scala:1034-1063` (`abortRekey` deletes the pending new session).
- Design/spec: Plan 0007 §10.4 / §11.2 / §12.2: after `PersistHandshake(old, new Pending)` and
  before `SendRekeyConfirm`, a crash must be recovered by immediately resending a fresh Confirm
  (new nonce/`n`, persist first), then installing the retry timer; abort only after the budget.
- Problem: `RekeyAcceptReceived` is the only producer of `SendRekeyConfirm`. On restore, the old
  session has `rekey = Accepted`, the new session is `Pending`; `rekeyTimers` installs a timer whose
  only action is `abortRekey`. Nothing resends the Confirm. A single lost `SendRekeyConfirm` (or a
  crash in the persist→send window) guarantees failure after 5 s.
- Preconditions / exploit: normal crash/kill between the durable handshake and the external send, or
  one dropped Confirm frame. The responder may already be `Active` and waiting.
- Impact: a completed Accept is discarded, the pending new session is deleted, and the peer is left
  waiting; the user sees `rekey-failed`. The relationship may need re-pairing, which ADR-0006 says
  must not be required for a recoverable handshake.
- Recommended fix: on `Unlock`/first `Connection(true)`, detect `Initiator + Accepted + new Pending`,
  generate a fresh Confirm (persist then send), then arm a retry timer with the existing budget;
  a second send must not call `Rekey.start` or consume the 7C local-start budget. Only abort after
  the budget. Mirror the responder's "duplicate Confirm → resend ACK" path.
- Closure evidence: the §12.2 virtual-clock test: B's pending deadline is 5 s, A restarts at 1 s
  with no user message, A's first Confirm is sent before 5 s, B ACKs, the original row is delivered
  once; a permanent loss ends in a bounded, visible `rekey-failed`.
- Residual risk: none for the crypto.

## 3. Major findings

### M1 — Peer-controlled cumulative ACK can mark unsent messages Delivered

- Source: `core/src/yochat/core/app/AppStep.scala:778-809` (`acknowledge` filters
  `value.seq <= ackedSeq` over all outgoing states, with no upper bound);
  `core/src/yochat/core/app/AppStep.scala:935-948` (DataAck handling).
- Design/spec: ADR-0008 §1: a DataAck is "I applied up to N"; N cannot exceed what the sender
  actually sent. ADR-0008 §3: `Sent` is transport acceptance, `Delivered` requires an authenticated
  ACK. Threat model A4: malformed ACKs must not corrupt state.
- Problem: `ackedSeq` is taken from the authenticated frame and used directly. The compare only
  removes messages with `seq <= ackedSeq`; it does not require `ackedSeq <= highestSeqSent` (or even
  `<= highestSeqAllocated`). Queued (never sent) and Failed messages are removed and reported
  `Delivered`. The ACK is not required to be monotone.
- Preconditions / exploit: the peer device (or a compromised peer) sends one validly signed
  `DataAck` with `seq = 1` while the local message is still `Queued` (offline), or with a huge seq to
  purge the whole outbox.
- Impact: unsent bodies are marked delivered and removed from the outbox; they are never retried.
  This is message loss plus a false delivery claim caused by the only other party.
- Reproduced: probe §4.2; output `remaining=None delivered=Vector(1)` for a `Queued` message while
  the connection was `Offline`.
- Recommended fix: bound `ackedSeq` by the highest seq in `InFlight`/`Sent` (not `Queued`), ignore
  larger values, and store the last accepted ACK per session to enforce monotonicity. Add a test
  with an ACK for a never-sent seq.
- Closure evidence: the probe as a product test; a fuzz/malformed-ACK test in the A4 suite.

### M2 — ReadOnly old session applies Data but cannot ACK; responder rebind is delayed

- Source: `core/src/yochat/core/Ratchet.scala:100-105` (`DataAck`/`Gap` require
  `phase == Active`), `core/src/yochat/core/app/AppStep.scala:951-973` (`controlResponse` silently
  drops the ACK when encryption fails), `core/src/yochat/core/app/AppStep.scala:548-575`
  (`RekeyConfirmReceived` emits no `RebindPending`), `core/src/yochat/core/app/AppStep.scala:99-175`
  (initiator rebind only).
- Design/spec: ADR-0008 §2: after applying a data frame, send the cumulative ACK. ADR-0006 §6:
  the old session stays ReadOnly but can decrypt in-flight frames. Plan 0007 §10.3/§11.1: both
  roles rebind pending old messages at cutover.
- Problem: (a) a late old-session Data is applied and persisted, then `encryptDataAck` is refused
  because the session is ReadOnly; no ACK is sent. (b) The responder does not emit `RebindPending`
  at `RekeyConfirmReceived`; only the initiator's `RekeyAckReceived` does. On a steady connection,
  responder pending old messages are not rebound until the next Unlock/reconnect.
- Preconditions / exploit: normal cutover with in-flight old traffic; a peer's retry of an old Data
  frame after the local old session became ReadOnly; a responder with a pending old message.
- Impact: the receiver displays the body, but the sender never gets an ACK and eventually marks it
  `Failed`; the responder's old messages stall on the old session. This contradicts the delivery
  contract and makes cutover lossy on a healthy link.
- Reproduced: probe §4.3; output `appliedNext=...->2 acks=0`.
- Recommended fix: allow DataAck/Gap on a ReadOnly session (data-plane control only; keep
  `RekeyConfirm`/`Ack` Active/Pending as now), or keep a narrow ACK capability for the old session
  until the in-flight window drains. Emit `RebindPending` from `RekeyConfirmReceived` for the
  responder's old session. Add a test: late old Data is applied and ACKed; responder rebind happens
  without a reconnect.
- Closure evidence: the two tests above; an E2E row that completes a cutover with an in-flight old
  Data and asserts `Delivered` once.

### M3 — Failed old-session messages block retirement and are never rebound

- Source: `core/src/yochat/core/app/AppStep.scala:622-631` (`retirementCandidates` excludes any old
  session with an outgoing entry, regardless of state), `core/src/yochat/core/app/AppStep.scala:99-175`
  (rebind only for Persisting/Sent/Queued/InFlight), `server/src/yochat/server/AppRuntime.scala:483-508`
  (`RebindPending` loop has the same state filter).
- Design/spec: Plan 0007 §11.3 / R3-N1: a `Failed` old item must not block the send gate; the batch
  must drain after the last skipped item.
- Problem: a `Failed` message stays in `outgoing` (up to `MaxFailedRetained=64`), so the old session
  is never retired; it is not rebindable either. The old session and its `rekey` state persist, so
  B2's timer eventually deletes the new session.
- Preconditions / exploit: one message exhausts its three attempts before the cutover; the rekey then
  completes. No user action is needed; the next reconnect arms the B2 timer.
- Impact: permanent old-session retention, blocked retirement, and the B2 desync. The user-visible
  symptom is a session that stops working after a reconnect following a failed send.
- Recommended fix: define one "needs rebind" predicate (non-terminal only) for the send gate,
  `retirementCandidates`, and `RebindPending`. On a `Failed` old item, remove it from the blocking
  set, cancel its timers, and do not send it; retire when the non-terminal set is empty. Then drain
  `pump` once at batch end (§12.3).
- Closure evidence: the fixed test sequence `Migrated(k) → Skipped(Failed,k+1)` with no extra user
  action: `k` is sent and ACKed, `k+1` is not sent and does not consume `ns`, the old row is retired.

### M4 — Responder has no rekey budget; a peer can keep the session ReadOnly

- Source: `core/src/yochat/core/Rekey.scala:59-122` (`respond` accepts any `epoch > lastRekeyEpoch`
  and sets `phase = ReadOnly`), `core/src/yochat/core/Rekey.scala:50-57` (`abort` sets a cooldown
  that `respond` never checks), `core/src/yochat/core/app/AppStep.scala:476-507`
  (`RekeyRequestReceived` has no attempts/cooldown guard; the 3/60 s cap is only in `StartRekey`).
- Design/spec: ADR-0006 §6: a peer must not be able to permanently block messaging; Plan 0007 §10.6
  explicitly narrows the budget to **local starts** and leaves inbound uncapped. Q20 asks whether
  the result is acceptable.
- Problem: a valid signed `RekeyRequest` with `epoch = lastRekeyEpoch + 1` every ≤5 s keeps the old
  session ReadOnly and the new session Pending. `activeSession` requires `Active`, so `canSend` is
  false; every send returns 400. The cooldown set by a previous abort is ignored on the next
  request.
- Preconditions / exploit: an authenticated peer (or a buggy peer that restarts rekeys) sends one
  request per window. No signature forgery is needed.
- Impact: indefinite send denial on a previously working relationship. The peer can also refuse to
  ACK, but this is a protocol-level block that the design claimed to bound. Per Plan 0007 §10.6
  this is an **accepted boundary**; the external reviewer must decide whether it is acceptable for
  S1. Record it explicitly either way.
- Recommended fix: if accepted, add the boundary to the threat model and Q20 answer. If not, add a
  responder-side per-session attempt/cooldown cap (the existing `rekeyCooldownUntilMs` can be
  checked in `respond`) and a test with sustained requests.
- Closure evidence: either a signed acceptance note or a test showing a bounded failure mode.

### M5 — Store tombstone can be reset by `insertSession`/`insertContact`

- Source: `store/src/yochat/store/SessionRepository.scala:294-317` (`writeNew` writes
  `meta[session-version]=1` unconditionally), `store/src/yochat/store/ContactRepository.scala:20-39`
  (same for contacts), contrast `store/src/yochat/store/InvitationRepository.scala:24-27,136-139`
  (insert checks the tombstone and rejects).
- Design/spec: ADR-0007 §4/G5: delete then reinsert of an old row must return `StaleGeneration`;
  the high-water is monotone.
- Problem: after a retirement writes high-water `v+1` and deletes the row, a later
  `insertSession`/`insertContact` for the same key inserts version 1 and overwrites the high-water
  with 1. The old state is then readable again. The existing G5 test reinserts with raw SQL (which
  the read path rejects); it never exercises the store API.
- Preconditions / exploit: a stale or buggy caller inserts a retired session/contact, or a future
  repair path does. Not reachable from a remote frame in the current wiring; it is a violated
  invariant in the security boundary.
- Impact: the rollback/tombstone protection is not enforced on the write path for two of the three
  protected row types; a high-water can go backwards.
- Reproduced: probe §4.4; output `highWaterAfterRetire=Right(3) reinserted=Right(...version=1)
  loaded=Right(1)`.
- Recommended fix: before `writeNew`/contact insert, read the per-row high-water; if present,
  abort `StaleGeneration` (or require an explicit new-instance path). Add API-level delete→insert
  tests for sessions and contacts.
- Closure evidence: the probe as a product test; `meta` high-water monotonicity assertion.

### M6 — Double accept of one invitation marks the contact AccountMismatch

- Source: `core/src/yochat/core/app/AppStep.scala:286-332` (`ReplyCreated`: an existing contact with
  a different session id sets `AccountMismatch`), `server/src/yochat/server/AppService.scala:198-209`
  (`acceptInvitation` has no mutex and re-checks only the pre-event lifecycle),
  `server/src/yochat/server/AppRuntime.scala:153-208` (read-then-submit is not atomic with the
  event).
- Design/spec: ADR-0009 "single pairing": a different session id for an existing peer is a new
  pairing attempt and is marked `AccountMismatch`; the UI must not treat it as a valid relationship.
- Problem: two concurrent `POST /invitations/:id/reply` calls both read `AwaitingReply`, both create
  a reply (different ephemeral/session), and both submit. The first creates the contact; the second
  hits the "existing contact, different session" branch and persists `AccountMismatch`. The first
  reply may still be sent and accepted by the peer, leaving one side unusable.
- Preconditions / exploit: a double-click or two concurrent local HTTP requests; no attacker needed.
  The service mutex is used for unlock, not for accept.
- Impact: a valid relationship becomes `AccountMismatch` and requires removal/re-pairing; the peer
  may have a working session with a contact that cannot send or receive bodies.
- Reproduced: probe §4.5; output `first=Vector(handshake) second=Vector(AccountMismatch)
  contact=Some(AccountMismatch)`.
- Recommended fix: make `ReplyCreated` idempotent for the same invitation id (if the invitation is
  already `PendingReply`/`Accepted`, return the existing reply text or an empty effect), and/or hold
  the service mutex across the invitation check + reply creation + submit. Add a concurrent-accept
  test.
- Closure evidence: the probe as a product test; an HTTP test with two concurrent accepts.

### M7 — `PersistRebound` discards `Migrated`/`Skipped`

- Source: `server/src/yochat/server/AppEffectExecutor.scala:318-350`
  (`persistRebound` maps `RebindOutcome` to `Unit` with `map(_.map(_ => ()))`),
  `core/src/yochat/core/app/AppStep.scala:99-175` (the event returns the rebound state and pumps
  before the effect result is known).
- Design/spec: Plan 0007 §10.1: only `Migrated` publishes the candidate; `Skipped` writes nothing,
  does not advance `ns`, does not increase attempts, and does not send.
- Problem: the executor treats both outcomes as success, and `applyEvent` sets `stateRef` to the
  candidate (new wire, advanced `ns`) before/independent of the store outcome. On `Skipped`, the DB
  row is unchanged but memory has a new wire and ratchet advance; `pump` may send a wire that is not
  the stored one.
- Preconditions / exploit: an in-memory `Queued/Sent` item whose row is already `Delivered/Failed`
  (a store/app divergence, e.g. a crash between the store commit and the state publish, or a
  future repair path).
- Impact: a message key is consumed for a wire that is never persisted; the in-memory and DB states
  diverge; the design's "no send on Skipped" guarantee is not enforced.
- Recommended fix: return the outcome through the effect result, publish the candidate only on
  `Migrated`, and on `Skipped` remove/align the old outgoing item and re-drain `pump` (§12.3).
- Closure evidence: the Plan §10.1 test with an existing terminal row: row/version/high-water/`ns`/
  attempts unchanged, no `SendWire`.

### M8 — Byte-unbounded inbound buffering

- Source: `transport/src/yochat/transport/RelayWire.scala:23` (`MaxPayload = 1_048_774`),
  `transport/src/yochat/transport/RelayClient.scala:66-70,164` (1,024-entry queue),
  `server/src/yochat/server/AppRuntime.scala:275,301,312-313` (4,096-entry queue before
  `translateInbound` drops unknown sessions).
- Design/spec: threat model A4 ("exhaust buffers"); repo rule "all queues bounded"; packet
  `protocol.md` "Queue/retry paths are bounded".
- Problem: the bounds are entry counts, not bytes. Worst case ≈ (1,024 + 4,096) × 1 MiB ≈ 5 GiB of
  attacker-chosen payload, admitted before the session filter. Unknown session ids are dropped only
  after they occupy the queue.
- Preconditions / exploit: the relay forwards frames from any registered device; registration is
  not authenticated (documented). A sender addressed to the victim's device key fills both queues.
- Impact: local OOM/GC stall; UI unresponsive. Relay availability is out of scope, but local memory
  exhaustion from A4 is not.
- Recommended fix: cap total queued bytes (e.g. a semaphore/`Ref` byte budget), reject or shed
  before enqueue, and drop unknown-session frames at the relay client or before the runtime queue.
  Add a pressure test with 1 MiB frames.
- Closure evidence: a byte-budget test; no OOM under sustained frames.

### M9 — Key source deviates from ADR-0007 and the packet describes a pipe that does not exist

- Source: `docs/reviews/2026-11-24-s1-crypto/storage.md:26` (claims a one-shot inherited pipe),
  `server/src/yochat/server/AppService.scala:264-266` (`KeySource.KeyFile(passphrase)`),
  `server/src/yochat/server/HttpApi.scala:44-58` (`POST /api/unlock` takes the passphrase in the
  JSON body), `shell/main.js:179-195` (`safeStorage` encrypt/decrypt; returns the passphrase over
  IPC to the renderer).
- Design/spec: ADR-0007 §2/§7: the keychain path passes the master through a one-shot inherited
  pipe; the master must not enter the Electron renderer; no argv/env/logs.
- Problem: the implemented path is an Electron `safeStorage` blob → renderer IPC → loopback HTTP
  JSON body → JVM Argon2id. No pipe exists. The packet's preferred key-source description is
  unsupported by the repository.
- Impact: the external reviewer is told to review a mechanism that is not there; the actual
  passphrase handling puts the secret in the renderer process (a broader exposure than the ADR) and
  in an HTTP body. The loopback body is not argv/env/log, but the design's separation is not
  implemented.
- Recommended fix: either implement the pipe (shell writes to an inherited fd; JVM reads once) or
  change ADR-0007 and the packet to the actual `safeStorage` + HTTP body path, with a threat-model
  note for renderer compromise. Do not claim both.
- Closure evidence: a code/test receipt for the chosen path; the shell is outside the repo test
  boundary, so at minimum a source-level check and an updated ADR.

## 4. Reproduced probes

All probes were scratch tests in `core/test`/`store/test`, run with `./mill ...testOnly`, and
deleted before delivery. The predicate and output are reproduced here so a human can re-add them.

### 4.1 B2 (core)

After a full request/accept/confirm/ack handshake with an old-session outgoing item:
disconnect → reconnect (installs the old timer), assert the timer is `Rekey(old,Initiator)` while
the new session is `Active`; fire `TimerFired`, assert the new session is removed and
`deletePendingSessions` contains it. Output:

```
probe-rekey-timer: installed=Some(Rekey(<old>,Initiator)) newActive=Active
probe-rekey-timer: oldPhase=Some(Active) newStillPresent=false deletePending=Vector(Vector(<new>))
```

### 4.2 M1 (core)

Offline, `UserSend` → `PersistOutboundSucceeded` leaves the message `Queued`. Bob signs a DataAck
for seq 1. Feed it to Alice with a fresh DH. Output:

```
probe-ack: remaining=None delivered=Vector(1)
```

### 4.3 M2 (core)

Take a valid first Data wire, deliver it to a state whose old session is `ReadOnly`. Output:

```
probe-readonly-ack: appliedNext=<author>->2 acks=0
```

### 4.4 M5 (store)

Insert session v1 → update to v2 → retire (high-water 3) → `insertSession` again. Output:

```
probe-tombstone: highWaterAfterRetire=Right(3) reinserted=Right(SessionRecord(version=1, generation=0)) loaded=Right(1)
```

### 4.5 M6 (core)

Fresh state with only an invitation; two `ReplyCreated` events for the same invitation id with
different ephemeral keys. Output:

```
probe-double-reply: first=Vector(handshake) second=Vector(AccountMismatch) contact=Some(AccountMismatch)
```

## 5. Crypto core: what was checked and found sound

This is a static read plus the existing vectors; it is not an independent assurance.

- **Primitives**: JDK JCE Ed25519/X25519/ChaCha20-Poly1305; local HKDF-SHA256 and HMAC-SHA256.
  All-zero X25519 output rejected (`Jce.scala:74-89`); no custom primitive.
- **KDF labels**: `yochat-s1-rk`, `-root`, `-rekey-base`, `-rekey-root`, `-rekey-session`,
  `-rekey-request`, `-rekey-accept`, plus store labels, are distinct per purpose. `KDF_RK` uses
  `salt=rk`, `ikm=dh`; `KDF_CK` uses HMAC(ck, 0x02/0x01). Matches ADR-0006.
- **Key/nonce uniqueness**: every Data/control frame consumes a fresh message key; retries reuse
  the stored wire; rebinds re-encrypt under the new session; rekey keys are per `(epoch,id)`.
  No path was found that uses one message key for two plaintexts. Random 96-bit nonces under
  per-message keys; the collision bound is the standard one.
- **Signature/AAD coverage**: `"yochat-s1-msg" || header || ciphertext`; header includes kind,
  session, device, dhPub, pn, n, seq, nonce, and the control extension; signature is verified
  before AEAD. Invitation/reply signatures and AAD bind the invitation hash, ephemeral keys, version
  and account fields.
- **Double Ratchet order**: initial A/B setup, B-first, receive-then-send root steps, `PN`/`Ns`/`Nr`,
  skipped keys before the DH step, retired-DH check, bounded gaps. No deviation from the Signal
  rev-4 data path was identified.
- **Rekey derivation**: `newRoot`/`newRekeyBase`/`newSessionId` bind `(oldSessionId, epoch, id, R1,
  R2)`; request/accept transcripts bind the same; R1/R2 private material is persisted before send.
- **Storage AAD**: all AAD domains are distinct; fixed-length fields; session/message/contact/
  invitation rows bind their identity, version and generation as ADR-0007 specifies.
- **Redaction**: `toString` on secrets is redacted; core/store/server errors are stable codes; the
  server logs only startup lines; HTTP error bodies are codes.

What was **not** done: independent cryptanalysis, a proof, side-channel review, memory-remnant
review, or a cross-implementation KAT. See §7.

## 6. Minor / INFO

| ID | Area | Item |
| --- | --- | --- |
| m1 | rekey | Timer instance token (Plan 0007 §10.2) is not implemented. `TimerId.Message` has no token; a stale `TimerFired` after a rebind matches the new outgoing entry (same id/generation) and consumes one attempt, sending the new wire. |
| m2 | cutover | `SessionRepository.cutoverReady` trusts caller-supplied evidence, and `PersistInboundAndRetire` hardcodes `CutoverRole.Responder` (`AppEffectExecutor.scala:217-223`). The store cannot enforce the role-specific rule of ADR-0007 G12. |
| m3 | rekey | `Rekey.clearTerminal` (`Rekey.scala:153-155`) is only called by a unit test. R1/R2 private material remains in the old session's `rekey` until the row is deleted; with M3 the window is indefinite. |
| m4 | docs | `storage.md:75` says "SQLite uses WAL and explicit foreign keys"; `Schema.scala:7` says there are no foreign keys. |
| m5 | packet | `storage.md:26` keychain/pipe claim is false (M9). |
| m6 | evidence | ADR-0006 §8.2 requires a ratchet vector against an independent reference or hand calculation. `VectorsSpec.scala:23-43` uses RFC 7748 keys and a fixed `shared` (good, but provenance is not recorded); the rekey/session vectors (`:45-75`) and `HandshakeVectorSpec` are generated end-to-end by this implementation. They pin behavior, not correctness. |
| m7 | evidence | No test for `MaxSkipTotal` (the cumulative bound); `RatchetSpec.scala:182` covers only `MaxSkipGap`. No session-row AAD swap test (contacts/invitations only). No duplicate first-new-Data test. No assertion of which session delivered a rebound message. No API-level idle-lock test (B1). |
| m8 | evidence | `store.test` is 25 tests, not 24 (`evidence.md:33`). `core.test` 145 and `server.test` 399 reproduce. The 1k p50/p95 and cold-start numbers in `evidence.md:37-39` have no committed artifact or percentile code in `S1E2E`; the E2E asserts total elapsed only. |
| m9 | evidence | No ScalaCheck/property tests despite the ADR wording; "message key not reused" is not asserted directly. |
| m10 | protocol | `Reply.accept` does not compare `fields.channelLabel` to the invitation's label; the label is display-only, but the packet's "account binding" wording could overstate it. |
| m11 | protocol | ACK is not monotone (part of M1); an old ACK only reduces the set, which is harmless, but there is no last-ACK state. |
| m12 | protocol | `RekeyRequest`/`Accept` transcripts omit the version byte; version is checked separately. Not exploitable in v1; note it for the external reviewer. |
| m13 | relay | The relay's target write lock has no write timeout (`RelayServer.scala:103-128`); a non-reading target blocks the source virtual thread. Relay availability is out of the threat model; Q31 asks for bounded waits. |

## 7. Evidence the external reviewer must produce

The internal review cannot substitute for these:

1. **Independent KAT/protocol cross-check** of the Double Ratchet and rekey derivation against a
   reference implementation or a hand-derived vector, with the derivation script committed.
2. **An answer to Q20** (M4): bounded remote rekey block, accepted or fixed.
3. **A side-channel/memory review** (explicitly out of scope here) or a signed statement that it is
   not required for S1.
4. **A fresh manifest and receipts** after the fixes: `./check.sh`, `./mill server.e2e`, the 1k/10k
   gates, and the new regression tests for B1–B3/M1–M7.
5. **The signed checklist** in `reviewer-checklist.md`, by a human or institution with no conflict
   of interest. No AI output, including this file, can be that signature.

## 8. What remains for the gate

The external cryptographic gate is **open**. This review does not close it and must not be cited as
independent assurance. The sequence that can close it is in `README.md`; in short: fix B1–B3 and
M1–M7, freeze a new source commit and manifest, commission a qualified independent reviewer, have
them verify the manifest and reproduce the evidence, then obtain a signed report with commit, scope,
finding status, and residual risks.
