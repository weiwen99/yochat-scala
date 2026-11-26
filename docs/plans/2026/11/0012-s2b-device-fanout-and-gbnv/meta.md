# Plan 0012: S2-B 设备列表、扇出与 GBNV

- Plan: 0012
- Date: 2026-11-26
- Status: design draft; round 1 review fixed, round 2 closure pending; no implementation yet. S2-B is split into B1 (device list/revocation), B2 (direct companion fanout), B3 (GBNV); all three gates are required.
- Domain: product/protocol/storage/UI
- Summary: 关闭 ADR-0010 留下的联系人可见设备列表、签名吊销与跨作者最终全序；A2/B2 作为只读伴随设备直接接收对方主设备的消息，不再只经 A1 转发。
- Predecessor: `9413244` (S2-A T1 sign-off and P2P evaluation); ADR-0010, ADR-0003
- Related evidence: `docs/spec/product/users-and-devices.md`; `docs/spec/engineering/keys-and-devices.md`; `docs/spec/engineering/messaging.md`; Plan 0011 `test-parity.md`
- Design: `design.md`; ADR-0011 `docs/adr/0011-s2b-device-list-fanout-and-gbnv.md`; review findings `design-review-findings.md`
- Open decisions: B1/B2/B3 split; read-only companion only; Lamport GBNV vs certificates; mixed-version policy; 1k direct fanout gate. See design §11.
- Test parity: `test-parity.md`; no implementation receipts yet.
- Next step: freeze the design/ADR, then implement B1 with migration and device-list/revocation tests before touching B2/B3.
