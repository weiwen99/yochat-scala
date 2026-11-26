# Plan 0012 implementation

Status: B1, B2 and the B3 order-metadata slice are implemented and green. Q8 is extended to B2/B3 and frozen by the model check; S2-B owner authorized closure 2026-11-27; independent audit not claimed.

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
| S2-A/B2 + B3 E2E | `./mill server.s2e2e` | PASS; `old=0 postJoin=200 direct=201 offline=221 restart=222 concurrent=242 revoked=242`; B1 ACK persisted; no duplicate after A1 resync; no post-revoke delivery |
| S2 1k sync | `./mill server.s2perf` | PASS; `sync_ms=3054 count=1000` |
| Full S1/S2-A gate | `./check.sh` | PASS 695.48s; S1 E2E 18/18, E2E#8 3776ms, `PERF#10K` 35,802ms, `PERF#RATE` 186.2 msg/s, cold start 1225/1298ms, idle 752.4MB; `QUINT_CHECKS PASS` |

## B2/B3 delivered

- Pairing fanout key, signed device-list fanout public, schema 5, direct `Fanout`/`FanoutAck`, logical-key dedupe and per-device receipts.
- B3 `OrderStamp` inside `DataPayload` v2 (v1 remains readable), `message_order`, Lamport/device/seq ordering in the main API and companion view, causal readiness with join-cutoff handling.
- `S2E2E` now covers A1-offline direct fanout, A2 ACK persistence, no duplicate after A1 resync, concurrent A/B ordering across A1/B1/A2, and post-revocation stop.
- Receipts: core/store/server tests PASS; `server.s2e2e` PASS `direct=201 ... concurrent=242 revoked=242`; `server.s2perf` PASS `sync_ms=3054`; `server.s2bperf` PASS `fanout_ms=4697`; `models/check.sh` PASS; `./check.sh` PASS 695.48s.

## Known limits

- B1 has one user-private-key signer. Multi-signer device-list merge and user-private sync are out of scope and need another ADR.
- B2 direct fanout, per-device receipts and B3 order metadata are implemented; see `b2-impl.md`.
- `test-parity.md` S2-B owner authorization recorded 2026-11-27; no independent row-by-row audit claimed.
- The contact-side list is a signed snapshot; old S2-A peers that do not send the new kind simply retain the primary-only projection.
- A v3→v4 migration regression was added after the full gate and passes in `store.test.testOnly yochat.store.S2StoreSpec`.
