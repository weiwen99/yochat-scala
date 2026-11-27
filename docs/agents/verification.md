# Verification

Three layers, each with a different job. A pass at one layer never substitutes for another.

## Layer table

| Layer | Tool | What it proves | What it does not prove |
| --- | --- | --- | --- |
| Protocol | Quint + Apalache/TLC + replay | interleavings, timeouts, ordering, membership, retry bounds | Scala implementation correctness, real network |
| Implementation | Scala property/state-machine tests + real SQLite | state transitions, crash/restart, transactions | multi-process timing, GUI, real transport |
| Pure functions | unit/property tests; bounded proofs where available | parse/sequence/token edges | the rest of the system |
| Behavior | E2E on the real app | user-visible paths, restart, reconnect, UI | all interleavings |

## Protocol layer

- Model the kept protocol surface only. v1 has one path: relay-only (no direct attempts, no NAT traversal), single chat, write-before-send, offline retry.
- Time is explicit: every wait is a timeout action that can fire at any time, any number of times. Do not assume a wait eventually ends. Do not assume every device comes online.
- Liveness properties state fairness premises; each has a safety dual that does not depend on them. Remove the fairness premise as a negative control and confirm the property is falsified.
- `REQ_20260918_BXC7RNH3YCQZ9BQY` is read as "the wait ends under the stated fairness premise", with a safety dual. Do not claim unconditional termination.
- Retry, rebuild, and broadcast counts are bounded in every connected epoch or sliding window.
- After the design freezes, the model freezes. The implementation may follow the model; model edits are a separate, human-signed round with re-verification.
- A model check needs a clear `[ok]` verdict. Exit code 0 alone, a type check, a trace sample, or a finite-state exploration is not a proof.
- Replay: export a simulator trace and feed it step by step into the real Scala `step`; compare observable state. A mismatch fails the test.

Quint registry: `models/checks.json` with the main module, backend, invariants, temporal properties, max steps, and source hash. Public positive entry points end with `Checks`. A config that only observes traces must record the obligation it covers, the reason, and the source fingerprint.

## Implementation layer

- Property/state-machine tests use the same generators, properties, and reference model as the old Rust tests for kept features. Port the specification, not the file layout.
- Real SQLite, not a mock, for transaction and crash tests. Crash injection is a test feature, not a production path.
- A storage failure is never translated into "empty", "not found", or "no data". Existence and readability are separate answers.

## Behavior contracts

15 E2E scenarios for S1, fixed across refactors:

1. first invitation and reply;
2. add contact and show it on both sides;
3. send one message, both sides show it;
4. restart the sender, message remains;
5. restart the receiver, message remains;
6. disconnect and reconnect, pending message arrives;
7. 100 messages in order, no duplicates;
8. 1,000-message throughput;
9. wrong/expired invitation rejected;
10. tampered ciphertext rejected;
11. tampered signature rejected;
12. log and diagnostics contain no message content, contact names, public keys, or key material;
13. app lock and unlock;
14. a message that cannot be decrypted triggers channel rebuild, not silent loss;
15. one data frame is dropped while connected; receiver sends `Gap`, sender resends, both sides show it once, and the sender ends in `Delivered`.

Contact removal and blocking are S3, not S1; they are not a behavior contract for v1.

Safety gate (S1 security enhancement, not a new scenario count): after the handshake, `contacts.safety_verified` is false; `#1/#2/#3` must assert that no user body is sent or displayed until the out-of-band safety number is verified. Only control/rekey frames may flow before that.

Unit tests may be rewritten. Every bug fix starts with a failing case. Do not chase coverage percentages.

## Test parity (T1)

`docs/plans/.../test-parity.md` maps every old test for a kept feature:

| Old test | Feature | Layer | New test | Status | Reason if omitted | Human sign-off |
| --- | --- | --- | --- | --- | --- | --- |

- Port the same scenario/assertion, not the same framework.
- Kani proofs have no direct Scala equivalent; record the gap and replace with property tests where possible.
- An omitted or weakened test needs a written reason ("the test asserts an implementation detail" or "it conflicts with the real requirement") and a human decision. Silent deletion is a failure.
- Dependency upgrades must not turn the behavior contracts red. If they do, the test was coupled to an implementation detail; fix the test, not the contract.

## Performance gates

- 1,000 messages two-process end-to-end <5s on the S1 setup. This is 1,000 total messages, not 1,000 msg/s.
- 10,000-message protocol replay <50s through real `Application.step`, fixed seed, no IO.
- 10,000-message two-process end-to-end <60s with real SQLite and the transport harness.
- `PERF#RATE` is an observational two-process rate measurement: 10,000 fresh 128-byte messages, 1,000 warmup, 64 sends in flight, direct SQLite completion counts. It reports `rate_msg_s` but has no rate threshold until a requirement is signed.
- 100,000 messages are not an S1 gate; a future run needs its own definition.
- Cold start p50 ≤2s / p95 ≤4s (calibrate after the first spike).
- Idle memory ≤800MB.
- Same seed, same data, isolated processes; report median and p95; no heavy concurrent load.
- The gate stores numbers in a receipt. A number in a document is not a gate.
- 10k thresholds are derived linearly from the 1k gate, with 20% slack on E2E. Failures are fixed, not relaxed; changing a threshold needs a separate ADR and human sign-off.

## State bounds

- After 1,000 sent and received messages, `outgoing` is at most the in-flight window plus the failed-retention cap; `buffered` is at most `MaxBuffered`; `seen` contains only buffered future sequences.
- Replaying an already-applied sequence must not grow `seen` or `buffered`.
- `applied` history is not retained in core state; SQLite is the history source.

## Check scope

During development, run the checks the change actually affects and reuse results that are still valid. The full gate is for an integrable batch or before delivery. Only new changes, failures, or unresolved risks justify widening or repeating a check. Keep the required checks; do not replace them with a targeted subset.

## Test integrity

- Do not fabricate results. Do not claim a test passed when it was skipped, timed out, or produced no verdict.
- An incomplete receipt is not a pass. A batch that ran out of time is not a counterexample and not a pass.
- Gate red: isolate and rerun. A fixed failing location is this change's bug; a drifting location under load is a flake. Do not global-serialize with `-j 1` to hide it.
- Local unit tests are not production acceptance. Say what was actually run.

## Desktop gates

`check.sh` and any automated batch run every Electron gate through `./desktop-gate.sh <command> [args...]`. The `npm run` scripts in `shell/` are developer entry points: they skip the timeout, the session injection, and the runner receipt. `contrast-smoke` is not an Electron gate — it reads the built CSS — so it runs directly and writes no receipt; it cannot hang on a window.

The runner has two platform branches. Everything below applies to both unless stated.

### Linux

- **No injection.** There is no Linux equivalent of `launchctl asuser`, so a gate runs directly. `YOCHAT_GATE_INJECT=asuser` is rejected on Linux.
- **A display is required.** The runner takes `YOCHAT_GATE_DISPLAY`, else `DISPLAY`, else the first X socket that answers `xdpyinfo`. Over ssh `DISPLAY` is unset even with Xwayland running, so the scan is what makes an ssh-driven run work. With no display the run is `INCOMPLETE` with `reason=degraded-no-display`; it is not a pass.
- **Keep-awake** is `systemd-inhibit --what=idle:sleep`, probed once first: logind refuses the inhibitor from a session that cannot authenticate interactively, which is the normal case over ssh. A refused inhibitor does not fail the gate; it prints a warning and records `keep_awake=false`, and then nothing stops the display from sleeping mid-run. Only a run started inside the graphical session gets the guarantee.
- **Display and lock state** come from DRM (`dpms` of connected connectors) and `loginctl LockedHint`. Both are best effort and never used to judge a gate.
- **Electron binary.** The npm distribution is built for FHS systems and cannot load on NixOS (it asks for `libglib-2.0.so.0` and a long chain after it). `shell/electron-runtime.js` resolves `YOCHAT_ELECTRON`, then `electron` on `PATH`, then `nix build nixpkgs#electron`, and adds `--no-sandbox` when the binary comes from outside npm (a nix-store Chromium has no setuid sandbox helper). The same file is the CLI that runs an Electron main script, which is how `check.sh` starts `keychain-smoke`.
- **Version skew is real and recorded.** The repository pins Electron 45.4.1; nixpkgs currently offers 45.3.4. Receipts carry `electron` (the pinned npm version), `electron_env` (the override, if any), and `electron_source`/`electron_resolved` (where the binary actually came from), so a Linux run cannot be mistaken for a 45.4.1 run. Aligning them is an open decision, not something a gate may paper over.
- **`safeStorage` is not the macOS keychain.** Linux uses libsecret/kwallet, or `basic_text` — a hardcoded key, so the API works while the secret is not protected. `keychain-smoke` prints `backend=` (and `insecure=true` for `basic_text`) and exits 78 (`KEYCHAIN_SMOKE skip reason=no-backend`) when no backend exists at all; the runner maps 78 to `verdict=SKIP` and exit 0 only for that gate — any other 78 stays a failure — and a SKIP is **not** a pass. `passphrase-smoke` selects its branch on the keychain's own answer rather than on the session name, so a Linux host with a working backend exercises the same full flow as Aqua.
- **A green Linux run does not stand in for macOS.** The two platforms differ in keychain backend, window chrome, notification surface, and packaging. Use `platform` in the receipt when quoting evidence.
- Observed 2026-11-27 on w7 (NixOS 26.11 x86_64): `keychain-smoke` PASS with `backend=basic_text insecure=true`, `passphrase-smoke` PASS through the full flow (remember, save, load, restart-prefill), `ui-smoke` PASS (100 messages each way, 37.6s), `electron-smoke` PASS (100 each way, both restarts, 226s versus 25s on m1).
- Observed on the same host, `server.e2e`: contracts #1-#7, #9-#15, #17 PASS; contract #8 (1,000 messages under 5s, the same assertion as `PERF#1K`) FAIL at 25385ms; `PERF#10K` FAIL at 225458ms against the 60s gate; `PERF#RATE` 45.0 msg/s against 330-340 on m1. `perf-start` reached p50 1643ms / p95 1702ms inside its limits, with idle 995.7MB over the 800MB gate. Throughput is 3.8-7.4x slower than m1; idle memory is 24% over the gate and does not follow load, so it is a separate unknown. An earlier one-off `store.test` on this host read 8816ms against a 1546-2553ms baseline, without a receipt.
- These are observations, not a verdict. No threshold was relaxed and no isolation rerun was done, so a red on w7 is an unclassified red, not an exemption. What they establish: the functional contracts run on Linux, and performance numbers there are not comparable to m1's.

### macOS

- **Keychain items are created from an interactive session.** On macOS `safeStorage` is backed by a per-app keychain item. Creating it needs the Aqua session; over ssh (`launchctl managername` reports `Background`) creation fails with `errSecInteractionNotAllowed`. Once the item exists with an ACL that allows the app, later Background reads can succeed — so a gate must not depend on which state it finds. The runner joins the Aqua session with `launchctl asuser` when it has passwordless sudo, runs directly when already in Aqua, and refuses to run with `verdict=INCOMPLETE` when it can do neither. `YOCHAT_GATE_INJECT=asuser|direct|degraded` overrides the choice.
- The keychain identity is per app. `electron <script>` uses `Electron Safe Storage`; the product (`electron .` in `shell/`) uses its own item. `keychain-smoke` probes the raw `safeStorage` layer, `passphrase-smoke` drives the real app's IPC handlers in whichever session it runs.
- `passphrase-smoke` asserts the Aqua path (remember writes the file, save resolves true, load returns it, a relaunch prefills the field) and, with `YOCHAT_KEYCHAIN_UNAVAILABLE=1`, the unavailable path (available false, save false, load null, no file). `check.sh` runs both through the runner; the Background branch is only reachable by running the gate outside the runner, and its boolean contract was verified that way. The hook is ignored in a packaged app (`!app.isPackaged`), so a stray variable cannot disable remember-password for a user. That guard is a code-reading conclusion: no packaged build was exercised.
- `passphrase-save` returns `false` for every failure, including an unwritable data directory; the reason goes to the shell log (message only, never the passphrase). `passphrase-load` returns `null` for both a missing file and an unreadable one — the pre-existing contract.
- The runner forwards every `YOCHAT_*` variable to the gate except its own controls, so an injected run reads the same jar paths, relay endpoint, and cross-host peers as a direct one. The receipt lists the forwarded **names**; a run with `YOCHAT_SERVER_JAR="/tmp/a b/c.jar"` and `YOCHAT_RELAY="127.0.0.1:7654"` showed both values intact inside the injected session.
- `caffeinate -dims` holds display and idle sleep for the duration (macOS; Linux uses `systemd-inhibit`).
- `YOCHAT_GATE_TIMEOUT` (seconds, default 1800) bounds each gate run through the runner, via a bash watchdog that marks the run before signalling, so a timeout is INCOMPLETE whatever the child reports. Exit 124, 129 (HUP), 130 (INT), 137 and 143 all print `verdict=INCOMPLETE` and are not a pass. The gate and the watchdog each run in their own process group: a timeout, Ctrl-C, or a dropped ssh session takes the whole tree down, so the next gate does not inherit a stale instance.
- Each run writes `tmp/checks/desktop-gate-<epoch>.json` plus a `.log` with the combined output, recording the commit and dirty-file count, tool versions, injection mode, executed session, forwarded environment names, display state, lock state, and duration. The gate's own receipt holds the measurements; compare performance numbers only at the same display state.
- A gate that hangs is a failure, not a result. `keychain-smoke` exits 2 (unavailable), 1 (mismatch or error), or 3 (30s watchdog); `passphrase-smoke` exits 3 after a 180s watchdog. A failed `passphrase-smoke` window is screenshotted to `/tmp/yochat-passphrase.png` and the path is recorded when the window still exists (a failure before launch has no screenshot).
- A process the gate itself detaches into another process group is not reachable by the group kill; no current gate does that.

- Verified 2026-11-27 on m1 at commit `b696e37` (`dirty_files=0`): `electron-smoke` passed
with the display on (100 messages each way, both restarts), as did `ui-smoke`, `perf-start`
(p50 1269ms / p95 1317ms / idle 741.7MB), `keychain-smoke`, all three `passphrase-smoke` paths,
and the interruption cases (timeout 124, INT 130, HUP 129, degraded 2; all INCOMPLETE).
`electron-smoke` also passed with the display **off** (100 messages each way, both restarts) at
`8ad1d44` and `6a57864`; repeated attempts at `b696e37` had the display woken within a second, and
their receipts record `display_before=on` rather than claiming otherwise. The product change since
`6a57864` is the keychain-hook guard, which touches no message path.
The **locked** screen state is recorded in receipts but unverified: unlocking needs the user's
password, so no automated run can cover it.

## Receipts

Checks write under `tmp/checks/<timestamp>/` (ignored). Each receipt records the command, tool versions, input hash, duration, verdict, and the log path. Only a complete receipt with an explicit verdict counts.
