# Plan 0012 B2 design: direct companion fanout

- 状态：implemented（2026-11-26）；B2 直投与 B3 order metadata 已实现，回执见 `b2-impl.md`/`impl.md`。
- 范围：B2 only。一个只读伴随设备直接接收对方主设备的消息；不实现 B3 GBNV。
- 关联：ADR-0011 §3/§5、Plan 0012 design §5.3。

## 1. 规模

| 维度 | 需求 | B2 产出 | 倍率 | 说明 |
| --- | ---: | ---: | ---: | --- |
| 需求 | 1（B 直投 A2） | 1 | 1× | 仅伴随设备直收 |
| 新表 | 0 | 1 | 不可比 | `delivery_receipts`；复用 `companion_messages` |
| 新列 | 0 | 1 | 不可比 | `companion_messages.logical_key`，用于 A1 同步与直投去重 |
| 组件 | 0 新服务 | 2 职责 | 2× | fanout envelope 编解码、A2 接收/回执；都在现有进程 |
| 配置 | 0 | 2 | 不可比 | 单条 envelope 上限、回执重试期限 |
| 工程师日 | 3–5（估算） | 5–8（估算） | 1.6× | 密钥/授权、存储迁移、直投、E2E；不含 B3 |
| 发布波次 | 1 | 1（B2） | 1× | B1 已上线；B2 默认关闭，双方 `s2b` 后才启用 |

## 2. 密钥选择

A2 在 `pair-request` 时生成一对 X25519 fanout key（私钥只存 A2），把 fanout public 放进配对请求并由设备密钥签名；A1 验证后写入 `devices` 的新列 `fanout_pub`，并随用户签名设备列表发给联系人。B1 从设备列表取得 A2 fanout public；每条直投消息使用新的 X25519 ephemeral：

```text
shared = X25519(ephemeralPrivate, A2.fanoutPub)
key    = HKDF-SHA256(shared, salt=conversationId, info="yochat-s2b-fanout/v1", L=32)
FanoutEnvelope {
  version, senderDevice, recipientDevice, conversationId,
  authorDevice, authorSeq, ephemeralPub, nonce, ciphertext,
  signature = Ed25519(B1.devicePriv, domain || header || nonce || ciphertext)
}
```

A2 用 fanout 私钥 + ephemeralPub 派生同一 key，验证 B1 设备签名和当前设备列表后解密。每条消息独立 ephemeral，不引入长期共享密钥；A1 不参与每条消息的密钥分发，也不学 B1 的 fanout 私钥。

备选否决：A1 生成对称 fanout key 再分发给 B1/A2。它把 B1→A2 的每消息密钥交给 A1，虽然 A1 本来能读 A1 会话，但这扩大了 A1 的实时解密面，且需要新的 grant 控制通道；不取。

## 3. 配对与设备列表

- `PairingRequest` 增加 `fanoutPub`，proof 覆盖它；`DeviceRecord`/`DeviceAuthorization` 增加 `fanoutPub` 或设备列表条目携带。
- A2 的 fanout 私钥存于身份目录的独立 sealed key（不复用 user/device 私钥），A1 的列表只含 public。
- A2 被吊销后，B1 从设备列表看到 revoked，停止直投；fanout public 不再接受新 envelope。
- 重新配对产生新 fanout keypair；旧 public 不再被列表接受。

## 4. 消息与去重

- `logical_key = canonical(conversationId, authorDevice, authorSeq)`。A1 的 device-sync 和 B1 直投携带同一 key。
- `companion_messages` 增加 `logical_key`，唯一；A1 同步与直投的 upsert 都按它去重。旧行迁移时由现有 `(conversation, author_pub, device_pub, seq)` 回填。
- A2 的 `CompanionView` 仍按现有 `(seq, source_id)` 展示；B2 不声称 GBNV，B3 才改为统一 `(lamport, device, seq)`。
- B1 在 `AppService.sendMessage` 持久化后读取 `MessageRecord`，产生直投 envelope；A2 收到后写入 `companion_messages` 并回 signed `FanoutAck`。
- `delivery_receipts` 记录 `(conversation, authorDevice, authorSeq, recipientDevice, ack)`；B1 的重发/界面“已收到”按设备保留，不能用一个设备的 ACK 结清另一个。
- 直投失败不阻塞 A1 的 S1 发送；重连后 B1 从本地待发记录/消息表重发未 ACK 的 envelope，有界重试。

## 5. 存储与锁序

新增 `delivery_receipts`，新增 `companion_messages.logical_key`；schema 4→5。锁序：

```text
... messages → message_order → devices → peer_device_lists → companion_messages
  → delivery_receipts → device_sync → meta
```

- B1 发送：读 `messages`，生成 envelope 后写 `delivery_receipts(pending)`；不修改 A1 同步游标。
- A2 接收：单事务 upsert `companion_messages` + 写 ACK 意图；ACK 发送是独立的有界效果。
- B1 收 ACK：只更新对应 receipt；不重写正文，不结清其他设备。

## 6. 失败与安全

| 场景 | 行为 |
| --- | --- |
| fanout public 不在当前设备列表/已吊销 | A2 拒绝；B1 不发 |
| envelope 签名/密钥错 | 静默丢弃；未认证输入不产生应用响应 |
| 重复 envelope | 按 logical_key 幂等；不重复显示 |
| A1 同步与 B1 直投同一消息 | logical_key 唯一 upsert；保留一份 |
| A2 发送 | 仍拒绝；只允许 FanoutAck |
| A1 离线 | B1 仍可直投 A2；A2 的 A1→A2 同步暂停，不影响 B1 消息 |
| B1 离线 | A2 等重连；不向 A1 索要 B1 消息（B2 不做历史补拉） |

## 7. 门与验收

- `S2B#2`：A1 离线时 B1→A2 直投 1 条/1k 条；A2 收到、A1 不在线也能验证；A2 仍不能发送。
- `S2B#2a`：A1 同步和 B1 直投同一消息，A2 只显示一次；logical_key 去重。
- `S2B#2b`：A2 离线后重连，B1 重发未 ACK envelope；按设备回执，不阻塞 B1→A1。
- `S2B#2c`：A2 被吊销后 B1 不再直投；A2 不能解出新 envelope。
- 回归：B1 设备列表/吊销、S2-A 1k 同步、S1 E2E、空闲内存门。

## 8. 待裁决

1. 接受 X25519 pairing-time fanout public（推荐）还是 A1 分发对称 key。
2. 接受 B2 不引入 GBNV；直投消息沿用现有展示顺序（B3 再统一）。
3. 接受 A2 只发 FanoutAck，不发业务消息。
4. 接受 schema 5 与 `logical_key` 去重（A1 同步/直投共用）。
