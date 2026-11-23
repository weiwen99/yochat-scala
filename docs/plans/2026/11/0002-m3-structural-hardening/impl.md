# Plan 0002 implementation: M3 structural hardening

- 状态: implemented，`./check.sh` 绿；未合入 `main`
- 日期: 2026-11-23
- 分支: `docs/plan-0002-m3-structural-hardening`
- 设计: `design.md`；基线: Plan 0001 `impl.md` §M3 review round 1

## 范围与结果

本实现只关闭 Plan 0001 M3 review round 1 的九项结构风险，不新增表、协议路径、配置项或 UI 屏幕。`core`、`store`、`server` 的现有测试保留并在需要处改为断言新的原子 effect；未删除或放宽既有行为断言。

| ID | 关闭证据 | 代码落点 |
| --- | --- | --- |
| R1 Reply/Rekey 握手多事务 | `HandshakeRepository` 在一次 `Store.transact` 内按 contact → conversation → sessions → invitation → retire 的顺序写；首次数据 cutover 由 `appendInboundAndRetire` 在同一事务内追加消息并退役旧 session。`HandshakeStoreSpec` 验证完整写入与中途失败全回滚。 | `store/src/yochat/store/HandshakeRepository.scala`、`SessionRepository.scala`、`MessageRepository.scala` |
| R2 先发布后持久化 | `AppRuntime.applyEvent` 先执行 durable effects；全部成功后才 `stateRef.set(next)`、`onState(next)`，之后执行 external effects 和反馈 step。durable 失败只置 fatal，不替换内存状态。 | `server/src/yochat/server/AppRuntime.scala` |
| R3 接受邀请后 SendReply 失败 | `InvitationData` v3 持久化 `replyText/replyAttempts`，`InvitationLifecycle.PendingReply` 保留邀请；`SendReply` 成功才 `DeleteInvitation`，失败则持久化尝试并安排有界 timer；重启后 `Unlock` 重新调度。`ReplyRetrySpec` 覆盖成功前不删除、失败重试、重试上限。 | `core/src/yochat/core/app/{AppTypes,AppStep,AppEvent}.scala`、`StoreData.scala` |
| R4 Rekey 超时/abort | Initiator/Responder 两条 rekey timer 覆盖 Request/Accept/Confirm/Ack 丢失；超时调用 `Rekey.abort`，原子持久化 old active 并删除 pending new session，通知 `RekeyFailed`。`RekeyTimerSpec` 覆盖 abort 与持久化。 | `AppStep.scala`、`AppRuntime.scala` |
| R5 Snapshot/restore 无界 | `Store.snapshot` 改为单个 `SnapshotRepository` 读事务；消息按 `(author_pub, seq)` keyset 分页，每页 512，达到 100k 上限仍有剩余则显式失败。`HandshakeStoreSpec` 验证 snapshot 投影读取 attempts。 | `store/src/yochat/store/SnapshotRepository.scala`、`MessageRepository.scala`、`Store.scala` |
| R6 Gap/retry 恢复 | 恢复后的 `gapRequested` 由 runtime 在 unlock/重连时生成 `ResumeGap`，持久化 Ratchet 前进后发送 Gap；`messages.attempts` 跨重启保存，每次发送/重试前 `PersistAttempt`。`GapResumeSpec`、`HandshakeStoreSpec` 覆盖。 | `AppStep.scala`、`MessageRepository.scala`、`Schema.scala`、`AppStateRestore.scala` |
| R7 Reply 候选无界 | 只考虑 `Issued/AwaitingReply` 且持有私钥的邀请，按到期时间排序后最多尝试 `MaxReplyCandidates = 8`；不再扫描无界全表。 | `AppRuntime.scala`、`AppLimits` |
| R8 Timer fiber 泄漏 | registry 存 `(token, fiber)`；触发/取消后仅在 token 匹配时移除；`activeCount` 有界测试验证触发后为 0。 | `AppRuntime.scala`、`TimerServiceSpec.scala` |
| R9 Idle timeout / Lock generation | `Unlock` 调度 `TimerId.Idle`；到期走同一 `lock` 路径并产生 `PersistLockGeneration`；`AppService.lock` 等待 durable lock 完成再关闭；重启从 `meta` 恢复 generation。 | `AppStep.scala`、`AppRuntime.scala`、`AppService.scala`、`ConversationRepository.scala` |

## 关键实现说明

- 持久化 effect 与 external effect 的边界是 `AppRuntime.isDurable`；`PersistOutbound` 成功后由反馈 step 重新计算发送，不在原 step 里提前发送。
- `InvitationState.PendingReply`（码 6）与 `InvitationData` v3 保持 v1/v2 可读；`messages` 表升到 schema v2 并迁移 `attempts INTEGER NOT NULL DEFAULT 0`。
- `PersistAttempt` 用 `(conversation_id, session_id, author_pub, device_pub, seq)` 定位消息，避免拿 app message id 当 SQLite row id。
- `RekeyAckReceived` 和 responder 首次新数据都把旧 session 退役放进同一 durable 写入；不再先写新 session、再单独删旧 session。
- `SendReply` 的 transport 失败不进入 fatal；`SendReply` 成功或失败都作为新事件回到单写者队列。

## 验证

- `./check.sh`: 绿（见下方回执）。
- `core.test`: 20 个测试类，含新增 `ReplyRetrySpec`、`RekeyTimerSpec`、`GapResumeSpec`。
- `store.test`: 含新增 `HandshakeStoreSpec`（原子握手、回滚、attempts、snapshot 投影）。
- `server.test`: 含新增 `TimerServiceSpec`；现有 `AppRuntimeSpec` 覆盖双 Store/双 Runtime/真实 TCP relay 的邀请、应答、消息 ACK、rekey、重连与恢复。
- 模型门 `./models/check.sh` 由 `check.sh` 执行。

### 回执

- `./check.sh` 全绿（85.23s）；`core.test`、`store.test`、`transport.test`、`server.test` 全部通过。
- `TENK_REPLAY messages=10000 elapsed_ms=12943 outgoing=0 next_seq=10001`（<50s 门）。
- `STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1727 read_ms=148 total_ms=1875`。
- `QUINT_CHECKS PASS`；`SPIKE messages=100 total=101 reconnect=true aliceSent=101 aliceAcked=101 elapsedMs=52 reconnectMs=25 bobReceived=101 ok=true`。

## 未关闭与限制

- R7 为有界候选（8），不是 `RelayPayload.Reply` 直接携带 invitation id；设计允许“O(1) 或明确有界上限”，当前选择后者，避免改 relay payload 协议。
- R4 采用超时 abort，不在同一 epoch 内重传控制帧；重试需要 `RekeyCooldownMillis` 后由调用方发起新一轮，使用新 r1/nonce/DH。
- R5 未做 10k 条 store 恢复计时；当前只证明单读事务、keyset 分页和上限失败，两进程 1k/10k E2E 仍是 M5。
- `AppRuntimeSpec` 仍是同 JVM、两 Store/两 Runtime 的集成测试，不是两进程 E2E。
- 外部密码学评审、15 条行为契约、冷启动/内存门仍未完成；本计划没有冒充这些门。
