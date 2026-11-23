# Plan 0004 implementation: first-message binding

- 状态: implemented（check 全绿；Round 2 review 已关闭）
- 日期: 2026-11-23
- 分支: `feat/plan-0004-first-message-binding`
- 基线: `4445297`
- 设计: `design.md`；ADR: `docs/adr/0009-first-message-binding-payload.md`；review: `design-review-findings.md`

## 范围与结果

本实现增加 ADR-0006 §3.3 的 `DataPayload` v1 envelope，并在 `Application.step` 的 Data 路径验证首条绑定。没有新增表、header 字段、配置、UI 屏幕、服务组件或 Quint 文件。

| Gate | 结果 | 证据 |
| --- | --- | --- |
| DataPayload 编码/解码 | PASS | 固定字节向量、未知 version/flags、截断/多余字节、空账号、非规范 NFC、非数字安全码、body/account 上限 |
| 首条发送绑定 | PASS | `BindingSpec` 解密真实 `UserSend` wire，核对 sender=`@alice`、peer=`@bob`、39 位安全码；store/API body 仍是原正文 |
| 首条缺失/错绑定 | PASS | 缺失、错账号、错安全码、空账号、`AccountMismatch`、`seq>1` 带绑定均无 `PersistInbound`/ACK/状态推进 |
| B-first | PASS | B 的首条带绑定，A 用 `newDh` 验证并应用正文 |
| 重复帧 | PASS | 未承认非法首条重复仍静默；已承认首条重复只重发累计 ACK，不再落盘 |
| 单一配对 | PASS | 同一 session 的重复 `ReplyCreated`/`ReplyReceived` 幂等；已有 peer contact 时不同 session 的第二个事件把 contact 标为 `AccountMismatch` 并持久化，不创建第二套 session |
| 缓冲可见性/顺序 | PASS | `ApiJson.visible`/`ordered` 按 cursor 隐藏未应用正文、按 `(seq,id)` 排序；API 过滤先于 `takeRight(limit)`；preview 读 `MaxBuffered+1`；`ApiJsonSpec` 覆盖 |
| 首条 marker / 旧库 | PASS | 首条 Data 同事务写 `meta binding/<conversation>`；无 marker 的旧会话 `bindingEstablished=false`，历史可读、不能续发/收 Data；`AppStateRestoreSpec` 覆盖 |
| 发送拒绝 | PASS | 安全门/legacy/mismatch 发送返回 HTTP 400；E2E 安全门前置断言 400，不再假 200 |
| E2E#1–#7 | PASS | 真实 relay + 两个真实服务进程，首条绑定后仍全绿 |

## 变更

- `core/src/yochat/core/DataPayload.scala`：v1 envelope、canonical encode/decode、账号/安全码/body 上限、redacted `toString`。
- `core/src/yochat/core/CoreError.scala`：新增 `InvalidBinding`。
- `core/src/yochat/core/app/AppStep.scala`：
  - `UserSend` 通过 `outboundPayload` 在首条加入绑定；
  - `relationshipUsable` 拒绝 mismatch/空账号；`Application.canSend` 提供发送前置条件；
  - `decodeInboundPayload` 只在 Data 分支解码并验证，legacy `bindingEstablished=false` 拒绝 Data；
  - 保留 DataAck/Gap 控制路径；
  - 重复 invalid 首条不推进状态；已承认重复只 ACK；
  - 已有 contact 时的第二套 `ReplyCreated`/`ReplyReceived` 标 `AccountMismatch` 并 `PersistContact`。
- `core/src/yochat/core/app/AppTypes.scala`：`AppSession.bindingEstablished`（运行时/恢复派生，默认 true）。
- `core/src/yochat/core/app/AppStep.scala` 的 rekey 新 session 继承 `bindingEstablished`。
- `store/src/yochat/store`：首条 Data 在 `MessageRepository.append` 同一事务写 `meta binding/<conversation>`；`StoreSnapshot.bindings` 读取 marker。
- `server/src/yochat/server/AppStateRestore.scala`：有 Data 无 marker 的 session 恢复为只读历史，`outgoingFrom` 跳过旧 pending。
- `server/src/yochat/server/AppRuntime.scala`：`sendMessage` 用 `Application.canSend` 预检查并返回 `send-rejected`（HTTP 400）。
- `server/src/yochat/server/ApiJson.scala`：`visible`/`ordered`/`lastVisible`；`AppService.messagesJson` 过滤后排序/截断。
- `core/test`：`DataPayloadSpec` 6 个、`BindingSpec` 9 个测试；`AppTestSupport` 适配真实账号/envelope；`RekeyAppSpec`/`ReplayModelsSpec` 手动 wire 适配。
- `server/test`：`ApiJsonSpec`（可见性/顺序）、`AppStateRestoreSpec`（无 marker 只读历史）。

## 验证回执

### `./mill core.test`

- 结果：全绿（145）；新增 `DataPayloadSpec`（固定向量、畸形输入、账号/body 上限、redaction）与 `BindingSpec`（发送、接收负例、B-first、重复、mismatch、单一配对）。
- `TENK_REPLAY messages=10000 elapsed_ms=12545 outgoing=0 next_seq=10001`（一次运行，<50s）。

### `./mill server.test`

- 结果：全绿（7）；`ApiJsonSpec` 1、`AppStateRestoreSpec` 1、`AppEffectExecutorSpec` 3、`TimerServiceSpec` 1、`AppRuntimeSpec` 1、`HttpApiSpec` 1。
- 说明：`AppRuntimeSpec` 仍是同 JVM 集成层；E2E 仍由 `server.e2e` 独立运行。

### `./mill server.e2e`

- 结果：`E2E_SUMMARY total=7 passed=7 failed=0`
- 耗时：36.0s
- receipt：`tmp/checks/1795418096570/s1-e2e.log`
- 说明：E2E 运行的是带 envelope 的新 wire；`E2E#3` 的正常首条现在包含绑定，安全门前置发送断言 HTTP 400。

### `./check.sh`

- 结果：全绿（104.63s）；`core.test` 145、`store.test` 24、`transport.test` 7、`server.test` 7；Q1–Q6 `QUINT_CHECKS PASS`；spike 100 条 + reconnect `ok=true`。
- 回执：`TENK_REPLAY messages=10000 elapsed_ms=24515 outgoing=0 next_seq=10001`；`STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=2348 read_ms=205 total_ms=2553`。
- 说明：回执是并行门禁下的单次读数，store 与 replay 明显受负载影响但仍低于硬阈值；不作为稳态 p50/p95。
- 不包含 `server.e2e`；E2E 已独立运行，见上。

## 未关闭与限制

- **Quint 模型缺口**：Q1–Q6 未新增 `bindingValid` 字段；Q1 仍只表达安全门。Scala 固定向量/状态机测试覆盖本轮 wire 断言。若 formal review 要求模型，单独一轮并重生成 trace。
- **旧格式/版本混用**：双方升级并重新配对是支持边界；旧已应用历史仍可读，但旧格式新入站首条拒绝，旧 pending seq>1 重试最终失败；无双解码器。
- **单一配对**：同一 peer 的第二个关系被忽略；重新配对需要先移除旧关系，属后续范围。
- **wire 级安全门前无 DATA**：仍未证明；本轮证明的是“有 Data 时首条必须带并验证绑定”。
- `E2E#8`、10k 两进程、冷启动/内存、`E2E#9–#15`、UI 自动化未做。
- 外部密码学评审仍是发布门。

## Review

- Round 1 设计评审的 P1/P2 共 7 项已按 `design-review-findings.md` 关闭：缓冲可见性、DataAck/Gap 分支、mismatch/空账号、单一配对、旧格式边界、重复语义、envelope 作为唯一 plaintext。
- Round 2 已复核冻结实现 diff；R2-1–R2-8 关闭（含重复 reply 幂等），无剩余 BLOCKER/MAJOR。结论见 `design-review-findings.md`。
