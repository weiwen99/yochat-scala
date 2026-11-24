# Scope

## System under review

- Repository: `github.com:weiwen99/yochat-scala`
- Commit: `6884926` (main at package creation)
- Product scope: ADR-0003 S1
  - two users, one device per user, one pairwise relationship;
  - relay-only transport; no direct P2P path;
  - write-before-send, restart, reconnect, rekey, first-message binding.
- Local platform: JDK 25 JCE + SQLite + application-layer AEAD; Electron/Laminar are not cryptographic components.

## In scope

| Area | Source | Design |
| --- | --- | --- |
| Invitation/reply and canonical encoding | `core/src/yochat/core/Invitation*.scala`, `Reply.scala` | ADR-0006 §3 |
| Double Ratchet and wire frames | `core/src/yochat/core/Ratchet.scala`, `RatchetState.scala`, `WireFrame.scala`, `MessageHeader.scala` | ADR-0006 §4 |
| First data binding | `core/src/yochat/core/DataPayload.scala`, `core/src/yochat/core/app/AppStep.scala` | ADR-0009 |
| Rekey and cutover/rebind | `core/src/yochat/core/Rekey*.scala`, `AppStep.scala`, `server/.../AppRuntime.scala` | ADR-0006 §6, ADR-0007, Plan 0007 |
| Delivery ACK/Gap/window | `core/src/yochat/core/app/AppStep.scala`, `Ratchet.scala` | ADR-0008 |
| Storage key hierarchy/envelopes | `store/src/yochat/store/Keys.scala`, `StoreCrypto.scala`, repositories | ADR-0007 |
| Relay boundary | `transport/src/yochat/transport/*` | ADR-0002, ADR-0006 |
| Test evidence | `core/test`, `store/test`, `transport/test`, `server/test`, `server.e2e`, `models/` | ADR-0004 |

## Out of scope

- S2/S3: groups, multiple devices, history transfer, revocation/removal, discovery, file transfer.
- Native GUI code, visual design, Electron privilege model except where it touches key storage or startup flags.
- External identity/account providers, push services, OS keychain internals.
- Claims of formal verification. Quint models are finite abstractions and are listed separately.
- Claims of side-channel resistance, secure memory zeroization, or traffic-analysis resistance.

## Snapshot

The exact file list and SHA-256 hashes are in `manifest.sha256`. Any source change after that snapshot
requires a new manifest or an explicit reviewer note; do not silently review a moving tree.
