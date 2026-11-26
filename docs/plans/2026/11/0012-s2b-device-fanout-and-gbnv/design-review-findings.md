# Plan 0012 design review findings

Object: `design.md` + ADR-0011, round 1. Standards and Spec axes. Internal review; not external audit. Findings are frozen before edits.

| ID | Severity | Finding and impact | Rule / source | Closure evidence | Cost |
| --- | --- | --- | --- | --- | --- |
| R1-1 | P1 | “只读设备会话”只说设备密钥握手，没有 key exchange、绑定字段、列表版本或拒绝条件；无法实现，也无法证明 B 直投 A2 的授权边界。 | ADR-0006 密钥边界；ADR-0010 只读设备；D8 | §5.3 固定 X25519 ephemeral + 签名绑定用户/双方 device/列表版本/用途；拒绝错用户、未列出、已吊销、旧列表 | 2 d |
| R1-2 | P1 | 文中称 `WireFrame` 扩展“向后兼容”，但旧 parser 不解析新字段；若直接发 v2，S2-A/S1 对端会失败。 | D2/Y2；ADR-0008 协议冻结 | §5.2 改为显式 `WireFrame` v2；未声明 `features` 的对端不发 v2，保留 S2-A 路径 | 1 d |
| R1-3 | P1 | GBNV 只写 Lamport/前驱，没有说明它们如何进入签名帧、如何与作者序号绑定；可被 relay 篡改或作者重放。 | ADR-0003 GBNV；ADR-0006 签名 | `WireFrame` v2 签名字段 + `message_order` 存 `(lamport, deps)`；逻辑键绑定作者 device/seq；S2B#3 负向测试 | 1 d |
| R1-4 | P2 | 5 张新表与 4 条需求接近 1.25×/条；`peer_devices` 是可重建投影，需说明为何不直接查原件。 | D2/D5 | §4.1 明确 `peer_devices` 只做合并投影、与原件同事务；不新增独立签发/快照 | 0.5 d |
| R1-5 | P2 | 设备操作/吊销原件只说“有界”，没有超限行为；可能静默丢原件导致吊销复活。 | D6/D8 | §5.2/§8 补“超限失败关闭并保留原件，不静默丢弃”；B1 测试容量 | 0.5 d |
| R1-6 | P2 | 验收入口写 `server.s2be2e`/`s2bperf` 但未实现，容易把计划写成已通过。 | D6/Y5 | §11 明确未实现不写 PASS；实现后创建 target 与 receipt | 0.1 d |

## Status

- Round 1: found 3 P1 + 3 P2; all revised in design/ADR.
- Round 2: pending closure-only review of the revised sections; no external review claimed.
