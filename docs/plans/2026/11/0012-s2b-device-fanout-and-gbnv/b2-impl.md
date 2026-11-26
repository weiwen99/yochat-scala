# Plan 0012 B2 implementation

Status: B2 direct companion fanout implemented on the B2 branch; B3 order metadata is implemented in the same branch. Full-gate receipt is recorded in `impl.md` after it finishes.

## Delivered

- Pairing-time X25519 fanout key: A2 keeps the private key in 0600 `fanout.key`; A1 stores the public in `devices.fanout_pub` and publishes it in the signed device list.
- `PeerDeviceList` v2 carries optional fanout public keys and the `s2b` feature; v1 snapshots still parse and verify with the legacy signing domain.
- Schema 5: `devices.fanout_pub`, `companion_messages.logical_key`, `delivery_receipts`, `message_order`; migration from 1/2/3/4, idempotent column checks.
- Transport `Fanout` / `FanoutAck` payloads route to the device path, never the S1 runtime.
- B1 calls fanout after the outbound message transaction; writes a pending receipt per active companion and retries pending receipts on relay reconnect and a bounded periodic loop.
- A2 verifies the envelope signature, sender user/device against the signed peer list and feature bit, maps B1's local conversation id to A2's synchronized conversation, dedupes by logical key, stores the body and order row, then sends a device-signed ACK.
- A2 remains read-only: no business send path, no user private key.

## Receipts

- `./mill core.test.testOnly yochat.core.s2.S2CoreSpec` PASS (envelope, ack, order stamp).
- `./mill store.test.testOnly yochat.store.S2StoreSpec` PASS (logical-key dedupe, per-recipient receipt, schema migration).
- `./mill store.test` PASS 10s.
- `./mill server.test` PASS 14s.
- `./mill server.s2e2e` PASS: `old=0 postJoin=200 direct=201 offline=221 restart=222 concurrent=242 revoked=242`; B1 ACK state observed, no duplicate after A1 sync, no post-revoke delivery.
- `./mill server.s2perf` PASS: `sync_ms=3054 count=1000`.
- `./mill server.s2bperf` PASS: `fanout_ms=4697 count=1000` (A1 offline, B1→A2 direct).
- `./models/check.sh` PASS including extended Q8 (`q8DeviceLists` safe; SHA-256 in `models/checks.json`).

## Known limits

- Direct fanout 1k has its own `server.s2bperf` gate; it is close to the 5s limit and should stay monitored.
- Cross-author missing dependencies are hidden until present and are repaired by the existing per-author gap path; there is no separate cross-author dependency resend protocol.
- S2-B owner sign-off for `test-parity.md` remains pending.
