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

## 10. Round 2 review 修订（最终实现契约）

### 10.1 A2：终态 no-op 不发布候选

重绑定事务在**任何 session 写之前**按旧五元键读取 message 的 `state`：

- 行不存在：整笔失败，保留旧 pending，暴露 `rekey-failed`；
- `Delivered`/`Failed`：返回 `RebindOutcome.Skipped`，**不写 session、不写 message、不写 high-water**；
- `Pending`/`Sent`：继续 session 写和 message UPDATE；UPDATE 必须命中同一旧键和旧状态，否则整笔回滚并返回失败。

`MessageRepository`/`Store` 返回 `RebindOutcome.Migrated` 或 `.Skipped`，不能只用 `Either[Unit]`；运行时只有 `.Migrated` 才把候选 session/message 发布到内存并发 `PersistOutboundSucceeded`，`.Skipped` 不发布、不消耗新 `ns`、不增加 attempts。测试用仍存在的终态行证明 row、版本、高水位和内存均不变，随后迁移另一行仍可解。

### 10.2 A4：timer 实例 token

`TimerId.Message`/`TimerFired` 增加启动时的 **timer instance token**（`sessionId + attemptSeq` 或等价随机 token，非持久数据）；`sendNow` 只接受与当前 outgoing 的 token 和 session 匹配的回调。cutover 取消旧 timer 后，已经入队的旧 `TimerFired` 也必须被 token 检查拒绝，不发送旧 wire、不增加 attempts；新 timer 使用新 token。测试先入队旧回调，再重绑定/安装新 timer。

### 10.3 A6：恢复三态与退休重查

`AppStateRestore` 不直接安装 timer；由 `Unlock`/重连后的 `rekeyTimers` 按持久 `RekeyState` 分三态：

1. `phase=Requested` 且 `newSessionId=None`：安装 Initiator timeout；超时 `abortRekey` 回旧 Active，不删除任何新行；
2. `phase=Accepted` 且目标新 session 为 `Pending`：安装 Initiator/Responder timeout；超时只 abort pending，保留旧记录；
3. 目标新 session 已 `Active`：绝不安装会 abort 的旧 timer；收到旧 timer 只清 timer，不删新 session、不激活旧 session。

`retirementCandidates` 不再只在“首条新 session Data”检查：`acknowledge` 移除旧 outgoing 后、ConfirmAck 到达后都要重查；只有目标新 session Active **且**旧 outgoing 为空才允许 retire。用 Request 提交后立即重启且不回 Accept 的用例验证有界 timeout。

### 10.4 A7：Confirm/ConfirmAck 重试

发起方在发出 Confirm 后进入 `Pending` 并安装 **Confirm retry timer**；timer 触发时：

- 生成新 nonce/`n`，重新 `Rekey.confirm` 并持久化/发送，不推进应用消息 `seq`；
- 由现有 `MaxRekeyAttempts`/`RekeyTimeoutMs` 限定次数与截止；耗尽后 `abortRekey`/`RekeyFailed`，保留旧记录，不伪造 `Delivered`；
- 应答方已 Active 时收到重复 Confirm，按现有 `Rekey.acceptConfirm` 重发 ACK，不重新初始化。

测试只丢首次 ConfirmAck、不额外发用户消息：重发 Confirm 后原行最终只送达一次；永久丢包时超时有界且 `rekey-failed` 可见。

### 10.5 B1：idle lock 隐藏通知

`/api/session` 以 `AppState.lock` 为准：只要 `!state.lock.isUnlocked`，即使 runtime 仍存在，也返回 locked JSON，`notification=null`；`/api/state` 在 locked 时仍返回 401。测试先产生 `rekey-failed`，再触发 idle timeout，不销毁 runtime，读取 `/api/session` 不返回旧码。

### 10.6 C1：预算口径文字

两份 spec 统一为：**新增的是本端发起预算**，复用 60s/3 数值；限制 `StartRekey`（自动/显式），不限制已认证的对端 `RekeyRequestReceived`，不扣本端发起计数。对端仍只受既有 per-session 认证/pending/epoch 状态约束。不声称全局、双方总计或不可绕过的配额。

### 10.7 验收文字

D:109 改为“E2E#1–#7、#9–#15 共 14 条通过；#8 不计入本计划”。7A 的真实发送者验收按 §9.8；不得用注入帧或仅 Active session 代签。

## 11. Round 3 review 修订

### 11.1 A6：退休只接受目标角色的证据

`retirementCandidates` 不再从普通 `DataAck` 推断“新 session 已被对端接受”：

- **发起方**：只有目标新 session 的 `ConfirmAck` 到达后，才允许移除旧行；ConfirmAck 本身证明对端已接受新 session。
- **应答方**：只有目标新 session 的**首个已认证 Data** 被本地接收后，才允许移除旧行；旧 session 的迟到 DataAck 不能作为证据。
- `acknowledge` 若移除旧 outgoing，必须把 `sessionId`/`role` 和证据传入退休检查；旧 session 的 ACK 只更新对应旧消息，不触发 old→new 退休。
- 没有证据不调用 retire、不报 fatal；旧行保留，重复 Confirm 仍可找到 old→new 关联并重发 ACK。

实现上使用 `SessionRetirement` 现有的 `InitiatorAck`/`ResponderFirstData` 作为显式证据参数，不为旧 DataAck 制造新的退休理由。

### 11.2 A7：Accepted+Pending 的恢复接回 Confirm 重试

- 在线：发出 Confirm 后安装 `ConfirmRetry` timer；每次重发生成新 nonce/`n`，先持久化后发，不增加 `rekeyAttempts` 的 `StartRekey` 计数，受现有一个 rekey 截止/次数预算约束。
- 恢复/重连：若旧 rekey 为 Initiator + `Accepted`，目标新 session 为 `Pending`，且 Confirm 尚未收到 ACK，则恢复 `ConfirmRetry` timer，不安装直接 abort 的 timer；只有重试预算耗尽才 abort/`RekeyFailed`。
- `Responder + Pending` 仍等待 Confirm；收到重复 Confirm 时重发 ACK。
- 测试：持久化 `PersistHandshake(old, new Pending)` 后、`SendRekeyConfirm` 前杀进程；重开后不发额外用户消息，确认重试最终使原行 `Delivered` 一次；永久丢 ACK 时有限次重试后公开失败。

### 11.3 R3-N1：Skipped 只跳过棘轮候选，不跳过终态清理

`RebindOutcome` 返回：

- `Migrated`：提交新 session/message，运行时可发布候选并进入发送；
- `Skipped(state: Delivered|Failed)`：不写 session/message/high-water，不推进 `ns`；运行时取得终态后，**移除该旧 outgoing 或对齐为不阻塞的 Failed 内存项**，取消旧 timer，不发送旧 wire；不产生 `PersistOutboundSucceeded`/`SendWire`。

因此“Skipped 不发布候选”不等于“全部内存不变”：终态调度项必须清理，否则旧 `Failed/Queued` 会永久阻塞发送门。测试构造“内存旧 Queued、DB Delivered/Failed、另有一条可发送消息”：旧行/棘轮不变，旧 wire 不发送，下一条真正发出并 ACK。

### 11.4 验收文字

真实发送者 7A 使用 A 服务在自动 rekey 前 `sendMessage`，保留同一 DB 行；B 只显示一次，A 收到新 session 的 ACK 后 `Delivered`，随后发送 k+1 成功。旧 DataAck、仅 Active session 和注入帧均不能代签。

## 12. Round 4 review 修订

### 12.1 R4-N1：Duplicate 也必须持久化退休

目标新 session 的首个已认证 Data 若在 `Conversation.accept` 得到 `Duplicate`（旧正文已存在、旧 DataAck 丢失），也必须执行 `ResponderFirstData` 退休：

- 不追加第二条 Received 行；
- 在同一 durable 事务里持久化新 session 状态，并使用 `PersistHandshake` 的“新 session + retire 旧 session”组合提交退休；
- 旧行和密钥删除后，重开不得再被 `AppStateRestore` 载入；
- 旧 DataAck 仍不是退休证据；只有这个目标新 session 的认证 Data（即使业务消息 Duplicate）算证据。

测试顺序：B 已保存 Data(k)，旧 ACK 丢失；A 重绑同 seq k；B 收到新密文得到 Duplicate；断言 B 仍只有一条正文、旧 session 持久删除、重开后仍不存在，A 获得新 ACK。

### 12.2 A7：恢复后的首次 Confirm 必须立即补发

`Initiator + Accepted + Pending` 在启动/重连恢复时：

- 不等待一个新的完整 responder timeout；若本次握手仍在预算内，**立即**生成 fresh nonce/`n`、先持久化再发送一次 Confirm；
- 随后按 ConfirmRetry 预算安装重试 timer；首次补发不调用 `Rekey.start`、不扣 7C 本端预算；
- 若 responder 已放弃/ACK 永久丢失，则在同一握手有限次后公开失败，不承诺离线必达。

双端虚拟时钟测试：B 原 pending 到期是第 5s；A 在第 1s 重开，无额外用户消息；B 在第 5s 前收到 A 的首次补发 Confirm 并回 ACK，原行最终 `Delivered` 一次。不得从恢复时刻再等 5s。

### 12.3 R3-N1：批次结束后重新 drain

`RebindPending` 的内联批次维护“仍有待迁移项”的状态；无论最后一项结果是 `Migrated` 还是 `Skipped`，批次结束都调用现有发送泵重新检查**其余 queued**：

- `Skipped` 旧项被移除并取消 timer，但不产生成功反馈/`SendWire`；
- 其他 `Migrated` 项已按 seq 排队；批次结束后的 pump 让最早的 queued 自行进入 delivery；
- 不依赖用户 POST、重连、timer 或无关 ACK 才唤醒。

固定测试顺序：同一 conversation `Migrated(k) → Skipped(Failed,k+1)`；两项完成后不进行额外操作，k 自行发出并获 ACK，k+1 不发送、不消耗 `ns`。

## 13. Round 5 review 修订

### 13.1 R4-N1：DuplicateSeq 不能绕过新 session 认证

`InboundFrame` 的 `seqSeen` 只表示**业务键已存在**，不表示“同一 session 已收过该帧”。在目标新 session 的首次新密文上：

- 旧 session 已收过业务 k 时，不能直接走 `DuplicateSeq` 返回；必须以 `seqSeen=false` 进行签名/AEAD/binding 认证；
- 认证成功后才允许进入“无正文退休”事务：更新新 session ratchet，使用 `PersistHandshake` 退休旧 session，不追加 Received 行；
- 认证失败、payload/binding 失败或同 session 重放仍走原拒绝/去重路径，不退休；
- 不能把 `Left(DuplicateSeq)` 本身当退休证据。

实现上使用目标新 session 的 `retireOldOnFirstData`（或等价 cutover 标记）区分“新 session 首次认证数据”和“同一 session 重放”，并保留旧 session 的 AAD/key 边界。测试：旧 ACK 丢失、旧业务 k 在新 session 重现，**不发 k+1** 即重开 B；旧 session 不载入、正文仍一条、A 收到新 ACK；拼接篡改/错误 binding 不退休。

### 13.2 R5-N1：首次 Confirm 只在可发状态补发一次

`Initiator + Accepted + Pending` 的立即补发条件：

- 只在同一握手第一次满足 `state.connection == Online && state.lock.isUnlocked` 时执行；
- 锁定或离线时不改 ratchet、不写 wire、不扣确认预算；
- 启动的 `Connection(true)`/`Unlock` 顺序不会造成两次补发；后续同握手的 retry 由 timer/预算路径处理，不另建队列；
- 首次补发仍先持久化 fresh `n` 再发，失败只按同一握手预算处理，不重新 `StartRekey`。

测试两种顺序：`在线 → 解锁` 与 `离线解锁 → 在线`；ACK 延迟返回，断言刚就绪时仅一笔 durable Confirm/一帧，之前为零，且不等满 5s。
