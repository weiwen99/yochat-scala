# Plan 0013 implementation

Status: implementation complete; owner authorized autonomous closure on 2026-11-27; no external cryptographic audit is claimed.

## Delivered

- `meta.s2b-peer-seen` records that a peer declared S2-B. Device lists declare `S2b` only when the local user has an active companion with a fanout key or a peer has already declared it. S1-only peers keep feature 0.
- Business send chooses `DataPayload` v1 unless the peer list declares `S2b`; `AppEvent.UserSend` carries the gate and `AppStep` emits an order stamp only when it is true. Old payload v1 remains readable.
- Capability, target-list and companion authorization/conversation caches have bounded TTL and are invalidated on device-list receipt.
- Main/companion ordering uses a total sort key `(lamport-or-legacy-seq, device, seq, source)`; the earlier mixed comparator could throw `Comparison method violates its general contract` after A1 went offline.
- `server.s2bperf` is in `check.sh`; `s2bperf` and `s2perf` run in the full gate.

## Receipts

- `store.test.testOnly yochat.store.S2StoreSpec`: PASS; capability starts disabled and survives reopen.
- `core.test`: PASS; `server.test`: PASS; `PeerDeviceListServiceSpec` verifies feature 0 before declaration and `S2b` after it.
- `server.s2e2e`: PASS `old=0 postJoin=200 direct=201 offline=221 restart=222 concurrent=242 revoked=242`.
- `server.s2perf`: PASS `sync_ms=2900 count=1000`.
- `server.s2bperf`: PASS `fanout_ms=4180 count=1000` after capability/order caches.
- Full `./check.sh`: PASS 739.17s; S1 E2E 18/18, E2E#8 3740ms, `PERF#10K` 34,757ms, `PERF#RATE` 301.1 msg/s, cold start 1216/1233ms, idle 729.2MB, Q8 PASS. S2 gates are now inside the full gate.

## Security review (self-review, not external audit)

- Envelope signature is checked before AEAD open; sender user/device is checked against the signed peer list, primary role and active status. ACKs are accepted only from an active companion named by a peer list.
- Capability/target/authorization caches are TTL-bounded and cleared on every device-list receipt; revocation therefore takes effect on the next list.
- A peer without the `S2b` feature keeps receiving v1 payloads and no direct fanout; this is the mixed-version path.
- Residual risk: no independent cryptographic audit; owner may require one before treating S2-B as production-grade.

## Security / rollback notes

- Fanout authorization still requires the signed peer list entry, primary role, `S2b` feature and active status; authorization cache is cleared on every list receipt.
- ACKs are accepted only from an active companion named by a peer list; receipts remain per recipient device.
- Rollback: disable the capability declaration (no peer list feature) → no v2 payload and no direct fanout; schema 5 data stays readable. Backup remains the only schema rollback path.
- Owner authorized closure by the 2026-11-27 instruction; the mapping was revalidated by implementation tests, not independently audited row-by-row.

## Known limits

- Capability is per user/device-list, not per device entry.
- Direct fanout remains best-effort after a bounded retry count; no cross-author dependency protocol beyond S1 gap handling.
