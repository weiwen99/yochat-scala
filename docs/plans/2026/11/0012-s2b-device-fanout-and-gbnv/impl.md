# Plan 0012 B1 implementation

Status: B1 implemented; B2 direct fanout and B3 GBNV are not started. Q8 model is frozen for the B1 device-list/revocation slice.

## Delivered

- Core `PeerDeviceList` / `PeerDeviceEntry`: user-signed, strict-version snapshot with device role, name, join/revocation times, canonical encoding and signature verification.
- Schema 4: `peer_device_lists`; migration from 1/2/3; old S2-A data remains readable.
- Transport `RelayPayload.DeviceList`; routed only to the device-sync scheduler, not the S1 runtime.
- `PeerDeviceListService`: builds the primary's current list from `devices`, publishes to active contact sessions, verifies/stores received snapshots, rejects stale/conflicting lists and revocation rollback.
- A1 publishes after pairing/revocation and on relay connect; the contact receives and persists the list; local removal is now a user-signed snapshot revocation visible to the contact.
- `GET /api/contacts/<user>/devices` plus a contact-card device line and UI model/parser.
- Q8a `q8_device_lists.qnt`: revocation is permanent, old snapshots cannot resurrect a revoked device, version is non-negative.

## Receipts

| Gate | Command | Result |
| --- | --- | --- |
| Q8 model | `./models/check.sh` | PASS; `q8DeviceLists` Apalache `safe`, hash in `models/checks.json` |
| Core | `./mill core.test` (S2CoreSpec added) | PASS |
| Store | `./mill store.test` | PASS; peer list round-trip/sealing/revocation rollback and schema migration |
| Server | `./mill server.test` | PASS |
| S2-A regression + B1 list | `./mill server.s2e2e` | PASS; `old=0 postJoin=200 offline=220 restart=221 revoked=221`; B sees two A devices and A2 revoked |
| S2 1k sync | `./mill server.s2perf` | PASS; `sync_ms=2694 count=1000` |
| Full S1/S2-A gate | `./check.sh` | PASS 696.06s; S1 E2E 18/18, E2E#8 3818ms, `PERF#10K` 35,332ms, `PERF#RATE` 294.4 msg/s, cold start 1297/1328ms, idle 757.6MB; `QUINT_CHECKS PASS` |

## Known limits

- B1 has one user-private-key signer. Multi-signer device-list merge and user-private sync are out of scope and need another ADR.
- B2 direct device sessions, per-device receipts and B/GBNV are not implemented.
- `test-parity.md` S2-B owner sign-off is pending.
- The contact-side list is a signed snapshot; old S2-A peers that do not send the new kind simply retain the primary-only projection.
- A v3→v4 migration regression was added after the full gate and passes in `store.test.testOnly yochat.store.S2StoreSpec`.
