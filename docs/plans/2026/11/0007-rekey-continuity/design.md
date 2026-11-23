# Plan 0007 design: rekey continuity and failure visibility

## 1. 范围与规模

| 维度 | 需求 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 缺口 | 7A 旧 pending 重绑定、7B 失败可见、7C 跨 cutover 限频 | 3 个闭环 | 1× |
| 新表/字段/协议 | 0 | 0 | 1× |
| 产品效果 | 0 | 1 个 durable `PersistRebound` + 1 个 external `RebindPending`；复用现有消息 row/session row | 1× |
| 配置 | 0 | 0 | 1× |
| 工程师日 | 1–3 人日 + 0.5–1 + 0.5–1 | 3–5（估算） | 1× |
| 发布波次 | 1 个恢复批次 | 1 | 1× |

不新增 wire kind、header、表或配置；重绑定只改变本地 `messages.session_id/body/wire/state/attempts` 和当前 `sessions` 状态。

## 2. 现有 ID 语义（必须先冻结）

- `OutgoingMessage.id` 是 **运行时效果 id**，由 `AppRuntime.messageIds` 分配；它用于 timer/effect 路由，**不是 SQLite `messages.id`**。
- `MessageRepository.append` 生成的 `messages.id` 是 SQLite row id，当前执行器丢弃 `AppendResult` 的 message id；现有 `UpdateMessageState` 的持久化路径使用 **业务键**
  `(conversation_id, session_id, author_pub, device_pub, seq)`，不是 row id。
- 因此 7A 重绑定必须使用 **旧业务键**定位行，不得使用 `OutgoingMessage.id` 当数据库主键。
- `seq` 不因 cutover 重置：重绑定沿用原 `OutgoingMessage.seq`；新 session 的 ratchet `n` 可以不同，`seq` 只保持作者顺序。
- 旧 `messages.id`、`created_at`、author/device/seq 保持不变；只更新 session_id、body、wire、state、attempts。

## 3. 7A 事务与锁序

### 触发

`rekey` cutover 的 responder/initiator 完成后，若旧 session 仍有未确认 outgoing，发出非持久效果
`RebindPending(oldSessionId, newSessionId)`。运行时按旧 session 的 outgoing 顺序生成
`RebindOutgoing(messageId, newSessionId, nonce, nowMs)` 事件；`messageId` 是 `OutgoingMessage.id`，仅用于运行时效果路由。

### 核心

`RebindOutgoing`：

- 只允许新 session `phase == Active`、旧消息仍存在、旧 session 与新 session 属于同一 conversation；
- 用新 session 状态和原 `seq` 重新 `Ratchet.encryptUser`；`seq == 1` 时重新生成首条 `DataPayload` binding，后续为无 binding；
- 更新内存中的新 session 状态与 `OutgoingMessage`（`sessionId=新`、`wire=新`、`state=Persisting`、`attempts=0`），保留 `id`、`seq`、`body`、author、device；
- 发出一个 durable `PersistRebound(message, newSession, oldSessionId)`；不直接发 wire。

### Store 事务

`Store.rebindOutbound(state, oldSessionId, message)` 的单个事务：

1. `Sql.checkLock(expectedLock)`；
2. `SessionRepository.loadProgram(runtime, newSessionId)` 读取当前 session 行；
3. 校验 `generation == lock`、`version` 仍为读到的版本；否则 `StaleGeneration`；
4. `SessionRepository.prepareUpdate` + `writeRow` 更新新 session 状态（`sessions` 行）；
5. 用新 session 的 message key 重封 `message.body`，AAD 使用新 `session_id` 和新 `wire` hash；
6. `UPDATE messages` 按旧业务键：
   `conversation_id=? AND session_id=oldSessionId AND author_pub=? AND device_pub=? AND seq=?`；
   设置 `session_id=newSessionId, body=sealed, wire=newWire, state=Pending, attempts=0`；
   要求 `Sql.changed == 1`；
7. `SessionRepository.writeHighWater`；
8. 不新增 binding meta；原 `seq==1` marker 已存在，且旧历史仍可读。

**锁序：`sessions → messages → meta`**，与现有 `MessageRepository.append` 一致；没有嵌套事务、没有跨行 update 的第二个写者。事务失败时不更新内存状态，不发送旧 wire；保留旧 pending 并暴露 `rekey-failed`/fatal，不静默删除。

### 发送门

`pump` 在任一 outgoing 仍指向 **read-only 旧 session** 时，跳过该 conversation 的**全部** queued 消息；否则新 session 的 `seq+1` 会先于旧 pending 发出，B 会把它当作 gap 缓冲。重绑定把旧消息更新到新 session 后，`pump` 按 `seq` 顺序发送旧消息，再发送后续新消息。

### 成功后的持久化反馈

`PersistRebound` 是 durable effect；成功后运行时把它当作 `PersistOutboundSucceeded` 的等价信号，消息从 `Persisting` 进入 `Queued`，由 `pump` 启动 delivery/retry timer。旧 session 保留到重绑定和 ACK 完成；不按“新 session 已 active”立即删除。

### 失败语义

- 旧 session/新 session 不存在、old message 被删、版本/lock 冲突、`Sql.changed != 1`：事务失败，旧 pending 不动，发出 `RekeyFailed`/fatal，不发送旧 wire、不静默标记 delivered。
- 重启恢复：若 active session 已生成但旧 session 仍有 pending，恢复时重新发 `RebindPending`；无法恢复则标记失败并可见。
- 不重绑定 `Failed` 消息；它们保持失败状态。
- 不改变 `seq`、不创建新 SQLite row、不产生重复 UI 正文。

## 4. 7B 重建失败可见性

- `AppRuntime` 增加内存 `lastNotification: Ref[IO, Option[Notification]]`；`RelayAppTransport.notify` 更新该引用并保留现有 bounded queue。
- `ApiJson.state` 增加 `"notification": "<code>" | null`，只映射稳定短码（如 `rekey-failed`），不含正文/账号/公钥/异常文本；`ApiJson.locked` 为 null。
- `AppService.stateJson/sessionJson` 传入该值；不新增表、不持久化。
- 触发路径：现有 `RekeyFailed` 通知（超时/attempt cap/queue full）至少在该字段可见；E2E 通过重复显式 rekey 或注入 rekey 响应丢失触发，断言 `notification=rekey-failed`，不读私有异常。
- 重启后清除；这是运行态诊断，不是 durable history。

## 5. 7C 跨 cutover 窗口限频

- `AppState` 增加两个内存字段：`rekeyWindowStartedAtMs: Long = 0L`、`rekeyWindowAttempts: Int = 0`；不写入 StateCodec（AppState 不持久化），重启重置，列为已知约束。
- 常量：`RekeyWindowMs = 60_000L`、`RekeyWindowMaxAttempts = 3`。
- `StartRekey` 在调用现有 `Rekey.start` 前：
  - 若 `now - windowStarted >= RekeyWindowMs`，重置窗口为 `now`、count=0；
  - 若 count >=3，返回 `Notify(RekeyFailed)`，不启动；
  - 否则 count +=1，再调用 `Rekey.start`；pending/cooldown/per-session attempts 仍由现有状态机负责。
- 成功的 cutover 不重置窗口；窗口按固定 60s 计时（不需滑窗列表）。
- 测试：窗口内 3 次成功/失败尝试后第 4 次可见失败；推进 60s 后允许新尝试；不引入新表。

## 6. 复用与现有边界

- 复用 `Ratchet.encryptUser`、`DataPayload`、`SessionRepository`、message AAD、现有 retry/delivery timer、`Rekey` 状态机和 `Notification.RekeyFailed`。
- 不新增 outbox 表；`sessions` + `messages` 已是持久来源。
- 不改变 B 的旧 session 删除策略之外的行为；7A 只保证 pending 重绑定，旧 session 最终由原有 retirement 逻辑处理。
- 不改 Quint 模型；重绑定是本地持久化/运行时行为，协议帧仍是既有 Data 帧。

## 7. 验收

| Gate | 可证伪证据 |
| --- | --- |
| 7A | 真实 A `sendMessage` 在自动 rekey 后仍送达；消息只出现一次；下一条新消息送达；SQLite 中 row id 不变、session_id/wire 更新 |
| 7A 失败 | 人为删除/改旧 message key 后事务失败，旧 pending 不消失，`notification=rekey-failed` 或 fatal 可见 |
| 7B | 强制 rekey timeout 后 `/api/state.notification == "rekey-failed"`；不含秘密 |
| 7C | 同一 60s 窗口第 4 次 rekey 失败可见；推进 60s 后允许 |
| 回归 | E2E#1–#15、core/store/server tests、`./check.sh` 全绿 |

## 8. Review gates

- G1：message id 只用运行时 id；DB 行只用旧业务键定位。
- G2：事务锁序 `sessions → messages → meta`，失败不发布新状态。
- G3：重绑定后正文只出现一次，seq 不重置，新消息不超车。
- G4：无新表/wire/header/config；无静默失败。
- G5：不引入 #8/10k、UI 自动化或新调度器。

## 9. Round 1 review 修订（取代上文冲突处）

### 9.1 7A 候选与事务版本（A1）

`RebindOutgoing` 增加 `expectedSessionVersion: Long`；运行时为**每一条**旧 pending 从 `Store.loadSession(newSessionId)` 取得当前 version，按 `(seq,id)` 串行处理：处理一条并在 `PersistRebound` 提交后才能生成下一条。`PersistRebound` 也携带 `expectedSessionVersion`；store 读取新 session 行后要求 `current.version == expectedSessionVersion` 且 `current.generation == lock`，否则 `StaleGeneration`，候选不发布、不发送。

`RebindPending` 由运行时**同步内联**执行，不排到普通命令队列尾部；在新命令进入事件循环前完成旧项迁移，避免新 `UserSend` 先消耗 ratchet `n`。这是 7A 的顺序门。

### 9.2 源消息键与终态（A2/A3）

- 重绑定 SQL 使用旧五元键并附加 `state IN (Pending, Sent)`；`Pending` 和 `Sent` 都可重发，`Delivered/Failed` 不重封。
- 同一事务先复核旧 session 存在且仍为源 session；`Sql.changed == 0` 时若行已是终态则幂等无操作，否则整笔失败，不发布候选、不发送。
- 旧 `Failed` 行不进入迁移集合，也不参与 `pump` 的阻塞谓词；阻塞集合只包含 `Persisting/Queued/InFlight/Sent` 且属于旧 session 的 outgoing。

### 9.3 Timer 与投递门（A4）

- cutover 立即取消旧 session 的 `Delivery`/`Retry` timer；
- `TimerFired(Message)` 在 `sendNow` 前复核 message 仍在当前 outgoing、代次匹配、其 session 仍是当前发送路径；旧回调不发送旧 wire、不消耗新尝试次数；
- `pump` 的阻塞谓词与 9.2 的非终态集合一致，`Failed` 不阻塞后续消息。

### 9.4 恢复与旧 rekey timer（A6/A7）

- `AppStateRestore` 不再用“旧 session 有 `rekey`”直接恢复未完成握手 timer；只有新 session 仍为 Pending 且旧 session 未 Active 时才安装 timer。
- 旧 session 的 rekey 超时不能删除已 Active 的新 session；`deletePendingProgram`/retire 前检查目标 phase 和是否仍有未确认行。
- 应答方已 Active、ConfirmAck 丢失：发起方只重发 Confirm（现有控制路径），应答方对已 Active 的 Confirm 重新生成 ACK；发起方不得因此重新初始化 ratchet。若 ACK 永久丢失，最终进入既有有界 `RekeyFailed`，保留已 Active 的新 session 和可重发记录，显式失败，不自动删行。
- 恢复崩溃切点按 review 的三个场景：未提交 rebind、部分提交、已发未 ACK；每项测试重开后 next wire 可解且不重复推进。

### 9.5 授权与序号（A8）

重绑定发送前检查 `relationshipUsable(contact)`、`bindingEstablished`、未锁定和身份存在；失败时保留旧 pending 并显式失败。`seq == 1` 的 binding 使用**原 message.seq**生成，不读取新 session 的 `nextSeq`；后续 seq 不带 binding。

### 9.6 7B 可见性（B1/B2）

- `lastNotification` 更新不依赖 notification queue 是否满；`StartRekey` 入队失败时同步写 `rekey-failed` 到该 Ref，并保留 `fatal` 作为独立公开出口，不要求向满队列再投事件。
- 7B E2E 必须用**真超时**：只启动一笔 rekey，代理丢失控制响应，等待原 pending 超时，再从 `/api/state` 读 `rekey-failed`；连续两次显式 POST 不算证据。
- 锁定状态下 `/api/session` 的 locked JSON notification 为 null；错误体仍 code-only。

### 9.7 7C 限频口径（C1）

本计划把 60s/3 预算明确限定为**本端发起**的 rekey（自动或显式 `StartRekey`）；对端请求的 `RekeyRequestReceived` 不扣本端发起预算，只受既有 per-session attempts/cooldown 约束。设计不再声称全局或双方总计。

- 固定 60s 窗口，跨成功 cutover 不重置；
- 同一 runtime 生命周期内有效；锁/解锁、进程重启会重建 `AppState`，预算清零，列为已知约束；
- 第 4 次本地发起失败可见；59,999ms 不放行，60,000ms 后新窗口允许；
- 测试区分本地发起与对端请求两种角色。

### 9.8 修订后 7A 验收

E2E 必须让 **A 服务本身** 在自动 rekey 前 `sendMessage` 并消费序号 k；旧持久行在重绑定后仍为同一 row id，B 只显示一次原正文，A 收到 ACK 后 `Delivered`；随后 `sendMessage` k+1 也按序送达。仅观察 Active session、仅新正文、或注入帧都不算 7A 通过。
