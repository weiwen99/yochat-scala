# Plan 0006 design: S1 privacy, lock, and automatic rebuild

## 1. 范围与规模

| 维度 | 需求 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 行为契约 | 3 | 3 | 1× |
| 新表/字段/配置/协议 | 0 | 0 | 1× |
| 产品组件/效果 | 1 个自动重建触发 | 1 个非持久 `AppEffect.StartRekey`；复用现有 rekey 协议 | 1× |
| 测试组件 | 日志扫描、锁探针、三次故障 | 复用 Plan 0005 `FaultRelay`，增加 count；新增 3 个 E2E 场景 | 1× |
| 工程师日 | 约 3–5 人日 | 3–5（估算） | 1× |
| 发布波次 | 1 个测试/修复批次 | 1 | 1× |

#12 默认只加测试；只有扫描发现泄漏才改日志/错误边界。#13 复用现有锁路径。#14 是唯一预期生产改动。

## 2. 复用现有机制

- #12 复用 E2E 双进程日志、`HttpResult` 错误体和现有 `ApiJson`；不新增日志框架或诊断端点。
- #13 复用 `POST /api/lock`、`POST /api/unlock`、`GET /api/session`、`GET /api/state`、消息 API 和 `AppLockSpec`；不新增锁状态。
- #14 复用 ADR-0006 §6 的 `Rekey` 状态机、`MaxRekeyAttempts`、cooldown、`RekeyFailed` 通知和已有 `/api/rekey` 路径；只把“达到 AEAD 失败阈值”从通知改为自动触发一次现有 rekey。
- 故障注入复用 Plan 0005 `FaultRelay`，增加同一个 fault 的多次应用计数；不复制 relay 协议。

## 3. #12 日志/诊断边界

定义：

- **诊断**：服务进程 stdout/stderr 日志，以及 HTTP 错误响应体；不是 `/api/state` 这种 UI 数据接口。
- `/api/state` 必须携带联系人的 public key 供 UI 路由；本契约不要求删除它，也不把它当日志。

E2E#12：

1. 完成握手、安全码验证，设置唯一联系人 alias（例如 `contact-secret-<nonce>`）。
2. 发送唯一正文（例如 `body-secret-<nonce>`）；让双方各收到一次。
3. 触发一次可预期错误（安全门未过发送/无效会话 API），记录 HTTP 错误体。
4. 扫描 A/B 的 `server.log` 全量文本，以及错误体，确认不含：
   - 正文标记；
   - alias；
   - A/B 的 user/device public key hex；
   - 已知 key material 标记（测试不会把真实 root/chain 写入日志；扫描关键前缀/测试 seed 不可行，故只扫描可获得的 key 字段和正文/alias）；
   - 安全码。
5. 断言只含错误码/状态/短计数，不把完整 state 写入 receipt。

若发现泄漏，最小修复在日志/错误边界；不新增“日志脱敏框架”，只删除/改写泄漏字段。

## 4. #13 应用锁

E2E#13：

1. 握手、安全码验证、发送一条消息，确认历史存在。
2. A `POST /api/lock` 成功；`GET /api/session` 返回 `unlocked=false`，无联系人/会话/身份。
3. `GET /api/state`、`GET /api/conversations/{id}/messages`、`POST .../messages` 返回 401；锁定时不落盘新正文。
4. 错误 passphrase `POST /api/unlock` 返回 401，仍锁定；正确 passphrase 后返回 200。
5. 解锁后历史仍在；再发一条消息，B 收到，A `delivered`。
6. 空闲自动锁定不在 E2E 重复；由 `AppLockSpec` 的注入时钟覆盖，列为已知边界。

## 5. #14 自动重建

### 生产变更

1. `AppEffect` 新增 `StartRekey(sessionId: SessionId)`，非持久效果。
2. `receiveFailure` 在 `error.error == CoreError.RekeyRequired` 时发射 `StartRekey(sessionId)`，不再只发 `Notify(RekeyRequired)`。
3. `AppRuntime.executeExternal` 处理 `StartRekey`：生成 `r1`、`rekeyId`、`nonce`，向事件队列提交现有 `AppEvent.StartRekey`。
4. 现有 `Rekey.start` 负责：
   - phase/cooldown/attempt 上限；
   - pending 时不重复；
   - 失败时 `Notify(RekeyFailed)`，达到上限后不再自动重试。
5. `AppEffectExecutor` 的穷尽匹配增加 `StartRekey` 分支（外部效果由 runtime 拦截，executor 不应执行；返回 `Unsupported` 仅用于穷尽/防御）。
6. 更新 `InboundSpec` 的阈值断言：`StartRekey(sessionId)` 替代立即 `Notify(RekeyRequired)`；保留 `RekeyFailed` 的上限测试。

### E2E#14

测试进程读取 A 的持久身份/会话，生成一条 A 签名、首条绑定合法的 Data；通过一个随机 relay device 注入 B，避免 A 服务留下旧 pending 消息干扰新会话序号。`FaultRelay` 对密文逐次篡改并用 A 设备私钥重签，注入 3 次；B 只看到合法签名但 AEAD 失败的帧。

1. 握手、安全码验证，记录旧 session id。
2. 注入 3 个签名有效、密文被篡改的 `seq=1` Data 帧；B 不显示伪造正文。
3. 3 次 AEAD 失败触发 B 的自动 rekey；A 服务按现有协议回应 request/accept/confirm/ack。
4. 等待双方 active session id 改变；不等待 UI 手动操作。
5. A 发送新消息；B 收到一次，A `delivered`。
6. 若重建失败，必须有 `RekeyFailed` 通知/可观察失败，不得静默切换。E2E 主路径只验证成功重建；上限失败由 core `RekeySpec`/`RekeyTimerSpec` 覆盖。

## 6. 事务与锁序

- #14 不改变 SQLite 事务；rekey 的持久化沿用既有 `PersistHandshake` 和 `sessions` 写路径。`StartRekey` 是运行时效果，不写库。
- 自动触发只在事件处理线程产生一次效果；现有 `rekeyAttempts`/cooldown 防止同一会话无限循环。
- #12/#13 不改锁序。

## 7. 验收

| Gate | 可证伪证据 |
| --- | --- |
| G1 #12 | 日志/错误体不含唯一正文、alias、双方 public key、安全码；receipt 只写扫描结论。 |
| G2 #13 | 锁后 state/messages/send 401；错误口令仍锁；正确解锁历史保留且新消息送达。 |
| G3 #14 | 3 次 AEAD 失败后无需手动 API 即出现新 session；新消息送达；坏正文不可见。 |
| G4 有界 | `Rekey.start` 的 attempts/cooldown 门保持；失败通知路径仍有测试。 |
| G5 无扩散 | 没有新表/字段/协议/配置；除 `StartRekey` 外无生产行为变更。 |
| G6 范围 | 未做 #8/10k、冷启动/内存、UI DOM、idle E2E。 |

## 8. 已知约束

- 自动重建只在同一会话连续 3 次“已验签、未见过、当前链”的 AEAD 失败时触发；签名/重放/乱序/控制帧失败不触发（ADR-0006 §6）。
- E2E#14 的注入帧使用测试进程读取的 A 持久身份/会话，只验证真实 B 服务与真实 rekey 协议；不是新的生产入口。跨 session 的旧 pending 自动重映射仍不在本计划，已知约束。
- #12 不扫描 `/api/state` 的 public key，因为那是 UI 必需数据；只扫描日志/错误体。
- 空闲锁定由 core 测试覆盖；E2E 只测手动锁。
