# S2-A 只读伴随设备：身份、存储与设备同步边界

**状态**: proposed (2026-11-26)

**前序**: ADR-0002（技术栈/relay-only）、ADR-0003（S1/S2 范围）、ADR-0004（测试/性能门）、ADR-0006（S1 加密）、ADR-0007（存储）、ADR-0008（ACK/窗口）。本 ADR 只覆盖 S2-A；不改变 S1 的 peer 协议。

## 背景

Plan 0011 选择 S2-A：A1 是主设备，持有用户私钥并继续作为联系人 B 的唯一 S1 对端；A2 是只读伴随设备，不持有用户私钥、不直接与 B 建立会话，只接收 A1 加入后可见的展示副本。S2-A 是部分 S2：GBNV 与联系人可见签名吊销仍未关闭。

本 ADR 冻结实现前必须定的三件事：A2 的身份文件、A2 的只读存储投影、A1/A2 的设备同步载体。它不实现历史同步，也不把 D 的 1000 条/秒候选写成门。

## 规模

| 维度 | 需求 | 本 ADR | 倍率 |
| --- | ---: | ---: | ---: |
| 新表 | 0（能复用则复用） | 3 | 不可比 |
| 新列 | 0 | 1 | 不可比 |
| 核心组件 | 1（server/transport 内） | 3 个职责 | 3× |
| 配置 | 0 用户可调 | 6 个内部常量 | 不可比 |
| 工程师日 | 5–8 | 10–15（含 Q7/E2E） | 1.9× |

`devices`/`device_sync`/`companion_messages` 各自有明确缺口：现有表没有设备授权、同步根/游标、只读消息投影。A2 不另造第二套核心状态机。

## 决策

### 1. 身份文件 v2

- v1 的 `IdentityKeys` 同时保存用户私钥、用户公钥、设备私钥、设备公钥；S1 的 `LocalIdentity` 假定本机可签邀请/应答。
- v2 文件格式在 v1 明文后增加 flags：`hasUserPrivate`、`role=primary|companion`。A2 只保存 `userPub || devicePriv || devicePub` 与设备授权摘要；用户私钥缺失是正常状态，不是空占位。
- `LocalIdentity` 增加角色/用户私钥可选；邀请、应答、设备授权、吊销签名等调用必须显式检查。A2 返回稳定的 `NotAuthorizedForCompanion`，不生成伪签名。
- A1 的 v1 文件在打开时迁移为 v2 primary；不改用户/设备密钥。A2 必须是新实例；已有非空实例拒绝配对或用新数据目录，不能覆盖旧数据。
- A2 的 store master/数据库密钥独立；不共享 A1 的 store key。

### 2. 存储：三张表 + 一个变更列

- `devices`：`device_pub` PK、`user_pub`、`role`、`name`、`authorization`、`version`、`created_at`、`revoked_at`、`join_epoch`、`join_manifest`、`join_manifest_hash`、`join_cursor_sync_seq`、`pairing_nonce`、`request_hash`。`name`/`join_manifest` 用 store key 封存；`meta["device-version/<hex>"]` 做高水位。
- `device_sync`：`peer_device_pub` PK、`sync_root`、`last_acked_sync_seq`、`last_batch_seq`、`last_batch_hash`、`version`、`updated_at`。`sync_root` 封存，AAD 绑定 peer device/version/auth hash。
- `companion_messages`：A2 专用只读投影，`source_id` PK、conversation/author/device/seq、`body`（A2 store key 封存）、state、created_at、`sync_seq`。没有 `wire`、session 或发送路径。
- A1 `messages` 增加 `sync_seq`：首次可见、状态变化或可见顺序变化时从 `meta["message-sync-seq"]` 递增；buffered 未可见行不分配。A1 只导出 `sync_seq > lastAckedSyncSeq` 的可见变更。
- `joinManifest` 是确认时刻 A1 对每个 `(conversation, author, device)` 的最大已落盘 `seq`，随设备授权由用户私钥签名；导出和 A2 校验都用它做创建截止线，不使用 `messages.id` 等未进入 AAD 的本地元数据。确认后才到达、此前未落盘的帧按加入后处理；这是 S2-A 的边界定义。
- schema 从当前 2 升到 3；保留 1→2。2→3 用已填充 v2 库夹具验证，失败失败关闭；回退只走备份。

### 3. 只读读取与恢复

- A2 的 `ApiJson`/`AppService` 在 `role=companion` 时只读 `companion_messages`，不调用 `MessageRepository`，不解析 `wire`/peer session。
- A2 的 `AppStateRestore` 有 companion 分支：从 `devices` 判定角色，加载 `contacts`/`conversations` 快照，不要求 peer session；outgoing 为空；发送/加好友/安全码修改在服务端拒绝。
- primary 分支保持 ADR-0007 的严格 wire/session 校验，不因 S2 放宽。
- A2 的 `companion_messages.body` 用新的 AAD 域绑定 source id/conversation/author/device/seq；不绑定可变 state。

### 4. 配对与设备授权

- A1 生成一次性 `PairingInvite`：userPub、primaryDevicePub、X25519 ephemeralPub、nonce、expiresAt、role、用户签名。A2 用自己的设备密钥提交 `PairingRequest`（含 ephemeral、加密名称、proof）。
- A1 必须验证 proof；A2 在自己屏幕显示设备指纹/确认码，用户在 A1/A2 两屏比对后才确认。A1 用用户私钥签 `DeviceAuthorization`：userPub、primary/companion device、role、joinEpoch、joinManifest/hash、joinCursorSyncSeq、syncRootHash、issuedAt。授权用配对 X25519 request key 派生的 AEAD 加密后经 relay 发送，relay 不能读 userPub/名称。
- A1 在单事务写 `devices` + `device_sync` + `meta`；syncRoot 与授权同事务。A2 验证签名、syncRootHash、确认码后写自己的行；没有 device_sync 不得进入已加入状态。
- A1 保存 `pairingNonce`/`requestHash`：同一请求可重发原授权（A2 丢授权），不同请求/不同 nonce 在确认后拒绝。授权丢失不等于第二个设备。

### 5. 设备同步载体与恢复

- 新增 `RelayPayload.DeviceSync` kind（不进入 S1 `WireFrame`/session）。relay 只按设备路由 opaque body；`RelayClient` 在 `AppRuntime.translateInbound` 前分派给 `DeviceSyncChannel`。真实 relay 必须覆盖配对/批次/ACK。
- `DeviceSyncBatch`：version、authHash、batchSeq、prevBatchHash、firstSyncSeq、lastSyncSeq、plaintextLength、nonce、AEAD ciphertext、A1 device 签名。A2 验签/解密后整批写入 `companion_messages` 并更新 checkpoint。
- A2 的 ACK 用设备私钥签 `(authHash,batchSeq,batchHash)`。A2 在连接/重连时发 signed `SyncStatus(lastBatchSeq,lastBatchHash,lastAckedSyncSeq)`；A1 在启动/重连后也发 signed `SyncStatusRequest`，覆盖 A1 重启而 A2 在线的场景。A1 只选一个完整 checkpoint tuple，拒绝旧/冲突 status；`lastAckedSyncSeq` 不得超前 A1 当前高水位。
- 未认证/签名失败帧静默丢弃，不产生应用响应。只有认证后的批次缺口才返回 signed `ResyncRequired`。
- 批次按字节拆到 `RelayPayload.MaxPayload` 以下；单条超限 `SyncRecordTooLarge`，不等待 ACK。A1/A2 锁定时同步暂停；正文不写未加密临时文件/日志。
- 移除 A2：A1 标记 revoked、递增设备版本、删除 device_sync 根/游标；不发可选移除帧。A2 没有对端会话，拿不到未来内容；旧数据不远程擦除；联系人 B 不被告知。联系人可见签名吊销与 GBNV 仍是 S2-B/后续。

### 6. 性能与测试

- S2#1：50 条旧消息 + 加入后 A1/B 各 100 条；B 下一对有效帧 `N+2/N+1` 乱序；A1/A2 的 `/api/conversations/<id>/messages` 序列一致；旧消息 0、无重复。
- S2#2：1k 同步 ≤5s；S1 1k/10k 门不回退。
- S2#3：ACK 丢失、A1 重启而 A2 在线、旧 status 重放；checkpoint 恢复。
- S2#4：本地移除后 A2 不再收到新消息。
- S2#5：无用户私钥、配对确认/重发、伪造/未认证帧、加入前消息状态更新/重标 id、relay 字节不含 userPub/名称、contact-only restart、Received/Sent/Delivered/Failed 重开不重发。
- Q7 设备同步模型覆盖配对授权、批次重放/重复、游标续传、移除；Q1–Q6 不改。
- T1 映射见 Plan 0011 `test-parity.md`（T1-2323/T1-2341/T1-2400/T1-2405/T1-2600）；未签字不得宣称 S2 完成。

## 备选与否决

| 方案 | 否决理由 |
| --- | --- |
| A2 复制用户私钥/完整 peer session | 违反只读边界；A2 可冒充 A1 |
| A2 复用 `messages` 并置空 `wire` | `MessageRepository.decode` 会拒绝；伪造 wire 削弱 S1 校验 |
| A2 直接与 B 建会话/扇出 | 是 S2-B；需要设备列表、多会话、GBNV，超出本 ADR |
| 历史同步/1000 条/秒 | 候选需求，未采纳；本 ADR 不做 |
| 设备同步走第二个 relay 服务 | 新增服务面；复用现有 relay 的 opaque kind |
| 仅 A2 重连时发 status | A1 重启而 A2 在线时无法恢复；增加 A1 `SyncStatusRequest` |

## 后果与已知约束

- A2 依赖 A1 在线；A1 丢失时 A2 不能接管，没有用户私钥/恢复短语。
- A2 只看到加入后的消息；没有历史回填。
- 移除是本地停止同步，不是联系人可见吊销；旧同步内容不远程擦除。
- 同步根是配对长期根 + 每批 HKDF，不是 Double Ratchet，无逐消息前向保密；A2 被攻破时旧数据已在本机。
- 正式实现前仍需 owner 确认 S2-A 作为部分 S2、接受 GBNV/签名吊销未关闭；本 ADR 不替代 Plan 0011 §16 的裁决。

## 可证伪 gate

| ID | PASS 证据 |
| --- | --- |
| G1 | v1 主设备身份迁移到 v2 仍可收发；A2 文件无用户私钥且不能签邀请/授权 |
| G2 | schema 2→3 在 v2 夹具上迁移，S1 消息可读、查询计划不变；旧库失败关闭 |
| G3 | A2 的 contact-only restart 可读，`companion_messages` 的 Received/Sent/Delivered/Failed 重开不重发 |
| G4 | 真实 relay 配对/批次/ACK；A2 收到的可见序列与 A1 history JSON 一致 |
| G5 | ACK 丢失、A1 重启而 A2 在线、旧 status 重放均恢复；无重复/缺口 |
| G6 | 加入前消息状态更新/重标 id 不泄漏正文；未认证帧静默丢弃 |
| G7 | 1k 同步 ≤5s；S1 1k/10k/空闲内存门不回退 |
| G8 | 移除后 A2 不再收到新消息；A1/B 正常；重启后仍如此 |

未跑就是未跑，不写成 PASS。

## 取代关系

本 ADR 不取代 ADR-0003/0004/0006/0007/0008；它把 Plan 0011 的 S2-A 身份/同步选择落成实现前置。GBNV 与联系人可见签名吊销仍由后续计划关闭。
