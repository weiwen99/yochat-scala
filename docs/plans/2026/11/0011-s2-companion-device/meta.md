# Plan 0011: S2 只读第二设备

- Plan: 0011
- Date: 2026-11-26
- Status: implementation in progress (A1/A2 pairing and confirmation, read-only A2 server, `sync_seq` incremental batches, checkpoint ACK, automatic change push)
- Domain: product/protocol/storage/UI
- Summary: 在 S1 对端协议不变的前提下，增加主设备 A1 与只读伴随设备 A2。A1 持有用户私钥并继续作为联系人 B 的唯一对端；A2 用设备密钥加入，接收加入后的联系人/会话/消息展示副本，按 A1 的可见顺序展示；A1 可本地移除 A2。A2 不持有用户私钥、不发送、不直接与 B 通信、不接收历史。S2-A 是部分里程碑，GBNV 与联系人可见签名吊销仍未关闭。
- Predecessor: `53bf009` (S1 acceptance and cross-host receipts), ADR-0003 S2 boundary
- Related evidence: 2026-11-25 `AI体验营` messages; `docs/evaluations/2026-11-25-d-sync-throughput-requirements.md`
- Review budget: 3 design rounds used/planned; round 1 found 8 P1 + 8 P2; round 2 found 6 open + 5 new (N1–N5). All findings revised in design; round 3 is closure-only and pending.
- Open decisions: S2-A vs S2-B; whether S2-A counts as S2 delivery; local removal vs contact-visible revocation; long-lived sync root; identity file v2 ADR; 1k sync 5s threshold. See design §16.
- Test parity: `test-parity.md`; T1 rows pending owner sign-off.
- Implementation checkpoint (2026-11-26): A1 `设备` panel generates an invite, inspects the A2 request, displays the same confirmation code/fingerprint shown by A2, requires the inspected code before issuing auth, lists companion devices, and revokes locally. A2 has `pair-request`/`pair-accept` CLI and a `--pair-only` browser page that shows the code/request, accepts the auth, and exits. A1 assigns `messages.sync_seq` after device sync is enabled, sends incremental batches above the A2 checkpoint, and advances `device_sync` on a signed A2 ACK; A2 persists its checkpoint and ACKs after applying a batch. A1 also pushes coalesced batches after visible-state changes; A2 re-requests after reconnect.
- Verification: `DevicePairingServiceSpec` (wrong confirmation rejected), `CompanionPairingServiceSpec` (browser status/accept), `PairingToolSpec`, `HttpApiSpec`, `DeviceSyncServiceSpec` (incremental batch, auto request, change push, signed ACK checkpoint), `S2StoreSpec`, and `server.test`/`store.test` pass; live A1/A2/B run showed contact creation, a new message, and an offline A2 restart converging without the manual sync button; the A2 browser page was exercised against A1 inspect/confirm. Full `./check.sh` re-run stopped at S1 E2E#8 under concurrent host load (5.4–6.6s vs 5s); the quiet pre-checkpoint full gate was 674.54s. E2E gate is not signed off after this checkpoint.
- Still open: signed `joinManifest` filtering for pre-join status updates, `SyncStatus` checkpoint exchange on A1 restart, batch-size limits, contact-visible revocation, GBNV, and a fixed automated S2 E2E. A2 browser pairing exits after auth and requires a separate read-only service start. Do not mark S2-A complete.
