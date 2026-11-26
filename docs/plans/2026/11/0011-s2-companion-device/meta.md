# Plan 0011: S2 只读第二设备

- Plan: 0011
- Date: 2026-11-26
- Status: implementation in progress (A1 pairing UI, A2 CLI pairing, read-only A2 server; snapshot is manual)
- Domain: product/protocol/storage/UI
- Summary: 在 S1 对端协议不变的前提下，增加主设备 A1 与只读伴随设备 A2。A1 持有用户私钥并继续作为联系人 B 的唯一对端；A2 用设备密钥加入，接收加入后的联系人/会话/消息展示副本，按 A1 的可见顺序展示；A1 可本地移除 A2。A2 不持有用户私钥、不发送、不直接与 B 通信、不接收历史。S2-A 是部分里程碑，GBNV 与联系人可见签名吊销仍未关闭。
- Predecessor: `53bf009` (S1 acceptance and cross-host receipts), ADR-0003 S2 boundary
- Related evidence: 2026-11-25 `AI体验营` messages; `docs/evaluations/2026-11-25-d-sync-throughput-requirements.md`
- Review budget: 3 design rounds used/planned; round 1 found 8 P1 + 8 P2; round 2 found 6 open + 5 new (N1–N5). All findings revised in design; round 3 is closure-only and pending.
- Open decisions: S2-A vs S2-B; whether S2-A counts as S2 delivery; local removal vs contact-visible revocation; long-lived sync root; identity file v2 ADR; 1k sync 5s threshold. See design §16.
- Test parity: `test-parity.md`; T1 rows pending owner sign-off.
- Implementation checkpoint (2026-11-26): A1 `设备` panel generates an invite, accepts a request and returns an auth code, lists companion devices, sends one snapshot, and revokes locally. A2 has `pair-request`/`pair-accept` CLI and a read-only HTTP/UI mode. Verified with `DevicePairingServiceSpec`, `PairingToolSpec`, `HttpApiSpec`, `S2StoreSpec`, S1 E2E (18/18, `PERF#RATE` 299.2 msg/s), full `./check.sh` PASS (674.54s), and a live A1/A2 run.
- Still open: two-screen fingerprint comparison, automatic snapshot scheduling/retry, `sync_seq` incremental stream, A2 receive/restart E2E, contact-visible revocation, GBNV. Do not mark S2-A complete.
