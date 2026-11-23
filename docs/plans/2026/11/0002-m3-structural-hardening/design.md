# Plan 0002: M3 structural hardening

- 状态: draft
- 日期: 2026-11-23
- 前序: Plan 0001（M4 已合入 `0ce9937`）；ADR-0004、ADR-0006、ADR-0007、ADR-0008
- 基线发现: `docs/plans/2026/11/0001-yochat-rewrite/impl.md` §M3 review round 1
- 活规格: `.scratch/yochat-m3-structural-hardening/spec.md`
- Review 预算: 最多 7 轮，总预算 2–3 人日；第 3 轮问题数不下降就停（R9）

## 规模与交付边界

| 维度 | 用户要的 | 本计划产出 | 倍率 | 说明 |
| --- | --- | --- | --- | --- |
| 需求条数 | 关闭 M3 review 的 9 个风险 | 9 个可证伪 gate | 1× | 不加功能、不加屏幕 |
| 表数 | 0 个新表 | 0 个新表 | 1× | 复用现有表；只为握手回复/尝试次数扩展 sealed payload 或列 |
| 组件数 | 0 个新组件 | 0 个新组件 | 1× | 改 `Store`、`AppRuntime`、`AppStep`、`TimerService`、`RelayPayload` |
| 配置项 | 0 个新用户配置 | 0 个新配置 | 1× | 重试/超时用既有 `AppLimits` 常量，不新增开关 |
| 工程师日 | 9 个风险 | 实现 7–9 + review 2–3 | 约 1× | 估算，不是承诺；review 轮次上限另计 |
| 发布波次 | 1 | 1 | 1× | 一个 worktree、一个可 ff-only 的批次 |
| 生产代码 | 修既有 runtime/store | +约 600–900 行，删约 200–400 行替代路径 | <1.1× | 仍远低于 30k；不建第二套运行时/存储 |
| 测试 | 每个风险有回归证据 | +约 400–600 行 | — | 不删除/放宽既有断言 |

结论：本计划只关闭已定位的结构性风险，不借机做 S2/S3、不改协议路径、不新增 UI 功能。任何一个维度超 3× 就退回当前范围。

## 状态与前序

Plan 0001 的 M3/M4 已合入 `main`（`0ce9937`）。M4 的 UI/Shell 只暴露了运行中的不变量，不会让 M3 的九个结构性问题消失；M3 review round 1 的未关闭项仍是 S1 的合并门槛。本计划消费这些遗留项，不改 Plan 0001 的产品范围。

本计划是设计记录，不是实现记录。实现开始前 design.md 冻结；review 记录追加到同目录 `design-review-findings.md`；实现回执在 `impl.md`。

## 背景与缺口

M3 review round 1 的九个问题如下。每一项都已有原文、位置与代价；本计划只决定关闭顺序与证据，不重新辩护范围。

| ID | 风险 | 当前缺口 | 关闭证据（可证伪） | 估算 |
| --- | --- | --- | --- | --- |
| R1 | Reply/Rekey 握手多事务 | contact/session/conversation/invitation 在崩溃窗口内可部分提交 | 故障注入在任一写入后失败并重启，store 中不存在半套关系；一次握手只产生一个提交区间 | 1–1.5d |
| R2 | 先发布后持久化 | `AppRuntime` 先 `stateRef.set(next)` 再执行 effects；失败后靠 reload+fatal | store effect 失败时旧状态不被替换、WS 不发布 next；恢复后没有“内存已前进、库未前进”的窗口 | 1–1.5d |
| R3 | 接受邀请后 SendReply 失败 | 本地握手已落库，reply 未持久化；首次 transport 失败直接 fatal | relay 断开时接受邀请不 fatal；重启/重连后 reply 仍可重发；成功后邀请才删除 | 1d |
| R4 | Rekey 缺 runtime 重试/abort | Confirm/ACK/Accept 丢失后可能长期 old ReadOnly + new Pending | 四个控制帧各自丢失一次的注入测试最终 active 或按上限 abort；状态有界 | 1.5–2d |
| R5 | Snapshot/restore 无界 | 多事务、100k 前缀读取、`List :+` 累积；不是原子快照 | 10k 消息恢复只做有界查询和一次读事务；查询计划有索引；无 100k 全表 blob 扫描 | 1.5–2d |
| R6 | Restore 不恢复 Gap/retry | 重启后不重发 outstanding Gap；`attempts` 回到 0 | 重启后 buffered gap 自动补拉；已尝试次数持久化并在上限处停止 | 0.75–1d |
| R7 | Reply 匹配无候选上限 | `RelayPayload.Reply` 不携带邀请 id，runtime 扫描全部邀请 | wire 直接带 invitation id；候选匹配 O(1) 或明确有界上限；>上限有可见结果 | 0.5d |
| R8 | timer fiber 保留已触发句柄 | 触发后的 fiber 仍留在 registry，长会话累积 | 触发后 registry 删除；重复 start/cancel 幂等；有界测试 | 0.2d |
| R9 | idle timeout / Lock generation 未接 | runtime 不调度 idle timer；锁代数未持久化到 meta | 空闲到期锁定；重启后 generation 前进；旧代数在途结果不落地 | 1d |

九项合计实现估算 8–10 人日；上表按可独立关闭的边界切，不把一项拆成“框架 + 迁移波次”。

这些 gate 都是 **现有 M3 功能已验证的保证** 的缺口（D2/R2）：现有 runtime 已有持久化、重试、恢复和锁代数；本计划只要求它们真正覆盖这些路径，不要求新功能达到旧功能没有的保证。

### 为什么这些不是新功能

- R1–R4、R6–R9 是既有行为的实现缺口，不改变 S1 场景。
- R5 只改恢复的读取方式，不增加历史同步。
- R7 是 transport payload 的字段补齐，不新增协议路径；relay 仍只转发密文与路由。
- 不新增表、配置、UI 屏幕或后台服务。所有改动仍落在 `core/`、`store/`、`server/`、`transport/`。

## 目标

1. 任一手关系或 rekey 切换在 SQLite 中只有一个提交边界；崩溃后要么旧状态、要么完整新状态。
2. runtime 只在持久化提交后发布内存状态；store 失败不产生“内存领先”的窗口。
3. 接受邀请后的应答有界重试，可在断网/重启后继续；不会因一次 transport 失败把本机变成 fatal。
4. rekey 的四个控制阶段有有界重试与 abort；不会永久卡在 old ReadOnly + new Pending。
5. 恢复在 10k 消息上使用有界投影与单读事务；不读取 100k 前缀，不随历史线性复制。
6. restore 恢复 Gap 重发与发送尝试次数；重试预算跨重启仍然有界。
7. reply 通过 invitation id 直接定位，不再扫描全部邀请。
8. timer 触发后从 registry 移除，长会话 timer 数有界。
9. idle timeout 与 `Lock` generation 走同一条锁定路径并持久化；重启后旧代数结果不落地。

## 非目标与已知约束

- 不做 S2 多设备、历史同步、吊销传播；不做 S3 发布级安装/通知矩阵。
- 不新增 relay 认证、账号服务器、直连或 NAT 穿透；relay-only 仍是 S1 已知依赖。
- 不把 100k 历史变成 S1 门；只保证恢复不在 100k 前缀上做无界读取。
- SQLite 提交原子性只覆盖一个事务；进程崩在 SQLite 提交前/后的行为按事务边界定义，不承诺跨设备事务。
- 外部密码学评审、15 条 E2E、两进程 1k/10k、冷启动/内存门仍是 Plan 0001/M5 的范围；本计划用单元/状态机/故障注入与 10k 协议回放兜底，不冒充 E2E。

## 设计

### 1. 事务与锁序

新增的唯一写入边界是现有 `Store.transact` 中的 `HandshakeRepository`（名字可改，但不新增表）：

1. `meta`：读取/比较 `lock-generation`，并更新必要的版本高水位；
2. `contacts`：按 `user_pub` canonical 顺序；
3. `conversations`：按 `conversation_id` canonical 顺序；
4. `sessions`：按 `session_id` 十六进制全序；rekey 的 old/new 两个 session 必须按此顺序写入；
5. `messages`：仅握手不写 message；update state 仍走既有消息锁路径；
6. `invitations`：按 `invitation_id` canonical 顺序；最后执行删除/状态更新。

- 全局锁序就是上面的顺序；同一层多条记录按 canonical key 全序。禁止先持有 session 行再申请 contact/邀请写。
- 一个握手只用一个 `ConnectionIO` 事务；不在 effect 层串多个 `store.*` 调用。
- `PersistSafetyVerified` 保留现有单事务，但归入 contact → sessions 的顺序。
- 事务失败返回既有 `StorageError`；不把失败降级成“空/未找到”。
- 复用理由（D2）：现有 `ContactRepository`/`SessionRepository`/`ConversationRepository` 的公开入口各自开 `runtime.run`，无法在 effect 层拼成一个提交边界；本计划只在既有 `store/` 内提取可组合的 `ConnectionIO` 写入、由现有 `Store.transact` 执行，不新增存储运行时、第二套事务层或新表。R5 的恢复投影同样复用 `Store.transact`，不是新 DAO 框架。

### 2. 提交后发布

把 runtime 的 effect 分为两类：

- **durable**：`Persist*`、`Delete*`、`UpdateMessageState`；
- **external**：`Send*`、`StartTimer`/`CancelTimer`、`Notify`。

`applyEvent` 的新顺序：

1. `Application.step` 得到 `(next, effects)`；
2. 依次执行 durable effects；任何失败：不 `stateRef.set(next)`、不调用 `onState`，进入既有 fatal 路径；
3. 全部 durable 成功后 `stateRef.set(next)` 并发布一次状态；
4. 执行 external effects；
5. `PersistOutboundSucceeded`、`SendSucceeded`、`TimerFired` 等反馈作为**新的 step** 进入循环，基于已发布的 `next` 再计算一次。

这保留“先落盘再发送”，同时让 UI 不会先看到未提交的候选状态。`SendReply` 不在 external 成功前删除 invitation；见 R3。

### 3. 接受邀请的持久化回复

- `InvitationData` 的 sealed payload 增加 v3 可选字段 `replyText`，并增加 `InvitationState.PendingReply`（码 6）；v1/v2 仍可读。没有新表。
- `ReplyCreated` 的 durable effect 是 `PersistReplyHandshake`：同一事务写 contact、conversation、session，并把 invitation 置为 `PendingReply` 且保存 replyText；不删除 invitation。
- 随后 external `SendReply`：
  - 成功：`ReplySent(invitationId)` step 产生 `DeleteInvitation` durable effect；
  - 失败：不 fatal；保留 `PendingReply`，由连接恢复/Rekey 外的 Reply timer 重试；
  - 重试上限用既有常量新增 `MaxReplyAttempts`（代码常量，不是用户配置）；耗尽后保留可复制的 reply 文本并显示可见失败，不静默丢。
- 邀请方收到应答：`ReplyReceived` 的 durable effect 一次事务写 contact、conversation、session 并删除 invitation；不再由多个 effect 拼事务。
- `InvitationLifecycle` 增加 `PendingReply` 映射；UI 现有“应答已生成”分支改为能显示待重发/已送回。

### 4. Rekey 的有界运行

- `TimerId` 增加 `Rekey(sessionId, stage)` 和 `Reply(invitationId)`；`messageId: Long` 的旧 timer 编码保持不变。
- rekey 四阶段各自有 retry cap 与 timeout；重试使用新 nonce/DH，不重放旧密文。
- 达到上限调用既有 `Rekey.abort`，保留一个可解释的失败状态并向 UI 发 `RekeyFailed`；不允许 old ReadOnly + new Pending 无限停留。
- 不改 Q5 模型。若实现发现必须改模型，单独一轮、人签字；不能在本计划中顺手改。

### 5. 恢复投影与尝试次数

- `Store.restoreProjection` 用**一个**读事务读取 contacts、invitations、sessions、conversations、每会话的 `last_applied_seq` 与 outbound `MAX(seq)`；不调用 `listAllMessages(100k)`。
- peer 的下一序号来自 `conversations.last_applied_seq`；outbound 的下一序号来自本地作者消息的 `MAX(seq)+1`。`PersistInbound` 在同一事务更新 `last_applied_seq`。
- 只读取恢复所需的 future/buffered messages，按 `(author_pub, seq)` 有界 `LIMIT`（`MaxBuffered`），不在内存里拼接全历史。
- `messages` 增加 `attempts INTEGER NOT NULL DEFAULT 0`，由发送尝试更新；restore 用它恢复 retry 预算，而不是一律归零。
- 恢复后若存在 buffered/gap，在线时对每个会话发送一次 `Gap(expected)`；不持久化 gap 本身，由消息投影重建。

### 6. Reply 的定位

`RelayPayload.Reply` 改为携带 `(invitationId, text)`。runtime 只按 id 取邀请；id 缺失/未知直接丢弃，不再扫描全部邀请。payload 有界检查沿用 transport 现有上限；该字段不改变端到端密文内容。

### 7. Timer 与 Lock

- `TimerService` 在 fiber 完成/取消后从 registry 删除；`start` 对同一 `TimerId` 幂等替换。
- idle deadline 由 runtime 在 `Unlock` 后调度；`IdleTimeout` 走 `Lock` 同一事件路径。
- `Lock` 在 store 中先 `bumpLockGeneration(expected)`，再把 generation 写入状态；失败则 fatal，不发布新代数。`Unlock` 只在 store 的 generation 与状态一致时成功。
- 旧代数发送/持久化结果继续由 generation 检查拒绝；重启测试覆盖。

## 测试规格等价

- 不改变 15 条行为契约的语义，不删除或放宽 Plan 0001 既有断言；本计划只增加回归测试与故障注入。
- T1 映射不变：`docs/plans/2026/11/0001-yochat-rewrite/test-parity.md` 仍有效。若为 R1–R9 新增测试改变了某条契约的解释，必须在 `impl.md` 引用该条并说明原因；不静默改契约。
- 复现固定：每个 bug 先写失败用例；事务/崩溃测试用真实 SQLite；retry 测试用真实 `Application.step` 或 runtime 注入，不以源码字符串/类型检查替代。

## 性能门

- 10,000 条协议回放仍 <50s；本计划不新增性能功能，只要求恢复不因历史线性放大。
- 10k 恢复的测试记录查询计划与峰值行数；禁止 100k 全表 blob 扫描。
- 两进程 1k/10k E2E、冷启动、空闲内存属于 M5，不因本计划变绿而关闭。

## 取代的旧文档

**none（有理由）**：本计划不取代 Plan 0001；它把 Plan 0001 `impl.md` 的 M3 review round 1 九项作为输入，关闭后在 0001 的对应段落留指向本计划实现的指针。Plan 0001 仍是 S1 范围和协议设计的来源。

## Review 预算与轮次

总预算 **7 轮**，不是每个风险 7 轮；review 约 2–3 人日。每轮先冻结 diff/文档（R8），审完才修；不边审边改。

| 轮 | 对象 | 通过条件 | 失败/停止 |
| --- | --- | --- | --- |
| 0 | 基线（本文件 + 0001 M3 review 九项） | 九个 gate 均有证据定义 | 缺证据的 gate 撤销 |
| 1 | 设计：R1/R2、锁序、事务边界 | 任意写入失败都能回到旧状态；无跨锁序 | 修正设计后进第 2 轮 |
| 2 | 设计：R3/R4/R5/R6/R7/R8/R9 | 每条有可执行的 fail→pass 测试与上限 | 修正设计后进第 3 轮 |
| 3 | 冻结实现 diff + 设计 + 规格 | 问题数较第 2 轮下降；否则停（R9） | 停止，重划范围，不加轮 |
| 4 | 实现：R1/R2/R3 | 故障注入与真实 store 测试通过 | 修复后重审 |
| 5 | 实现：R4/R5/R6 | 丢帧重试、10k 恢复、Gap 恢复回执 | 修复后重审 |
| 6 | 实现：R7/R8/R9 + 回归 | id 定位、timer registry、idle/lock 重启 | 修复后重审 |
| 7 | 最终：七个维度 + 未关闭项 | 零开放发现，或明确升级人工 | 7 轮后停止自动迭代 |

R1 硬上限优先于本表；任何一轮出现范围/协议变化，视为新设计轮，不混入当前轮。

## 验收

1. 九个 gate 各自有测试或故障注入回执；未关闭项必须在 `impl.md` 写“关闭证据”或“撤回理由”，不能只写“已知”。
2. `./check.sh` 全绿；`core.test` 的 10k replay <50s；真实 SQLite 恢复测试覆盖 10k 投影。
3. `AppRuntimeSpec` 与 `HttpApiSpec` 覆盖断网重连、reply 重试、rekey 丢失控制帧、restart 后 Gap/attempts。
4. Review 记录不超过 7 轮；第 3 轮问题数不下降即停并升级人工。
5. 本计划实现并合入后，才把 Plan 0001 `impl.md` 的 M3 round 1 段落标记为“由 0002 关闭”，不重写历史记录。
