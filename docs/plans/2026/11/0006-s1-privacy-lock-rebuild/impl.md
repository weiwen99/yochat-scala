# Plan 0006 implementation: S1 privacy, lock, and automatic rebuild

- 状态: partially implemented（E2E 14 条全绿；#14 真实发送序号连续性 review P1 仍 open）
- 日期: 2026-11-23
- 分支: `feat/plan-0006-s1-privacy-lock-rebuild`
- 基线: `c21210b`
- 设计: `design.md`；review: `design-review-findings.md`

## 范围与结果

| 契约 | 结果 | 证据 |
| --- | --- | --- |
| E2E#12 | PASS | 真实消息 + alias + 错误响应后，A/B 日志与错误体不含正文、alias、双方 public key、安全码 |
| E2E#13 | PASS | 锁后 state/messages/send 全 401；错误口令仍锁且 401；正确解锁历史保留，新消息送达 |
| E2E#14 | PASS | 3 个签名有效、AEAD 失败的注入帧触发 B 自动 rekey；新 session id 改变；新消息送达；假正文不可见 |
| E2E#1–#7/#9–#11/#15 | PASS | 既有契约保持全绿 |

## 变更

- `core/src/yochat/core/app/AppEvent.scala`：新增非持久 `AppEffect.StartRekey(sessionId)`。
- `core/src/yochat/core/app/AppStep.scala`：AEAD 失败计数达到阈值时发射 `StartRekey`，不再只发即时通知；既有 `Rekey.start` 继续负责 pending/cooldown/attempt 上限。
- `server/src/yochat/server/AppRuntime.scala`：`StartRekey` 由运行时生成 r1/rekeyId/nonce，提交现有 `AppEvent.StartRekey`；不自动重映射旧 pending 消息（已知约束）。
- `server/src/yochat/server/AppService.scala`：`StorageError.UnlockFailed` 映射为 `ServiceError.UnlockFailed`（HTTP 401），不再错误地返回 500。
- `server/src/yochat/server/AppEffectExecutor.scala`：穷尽匹配增加 `StartRekey` 防御分支；真实执行在 runtime。
- `core/test/src/yochat/core/app/InboundSpec.scala`：阈值断言改为 `StartRekey(sessionId)`。
- `server/test/src/yochat/server/e2e/FaultRelay.scala`：fault `count`；新增 `TamperCiphertextSigned`，用测试读取的 A 设备私钥重签篡改密文，确保 B 走到 AEAD 失败而不是签名失败。
- `server/test/src/yochat/server/e2e/E2ESupport.scala`：锁/错误口令/状态/消息/session id 探针，日志全量读取。
- `server/test/src/yochat/server/e2e/S1E2E.scala`：E2E#12/#13/#14；#14 用测试进程读取 A 持久身份/会话，生成合法首条 Data，经随机 relay device 注入 B，避免 A 服务旧 pending 干扰。
- 新增表/字段/协议/配置: 0。

## 验证回执

### `./mill server.e2e`

- 结果：`E2E_SUMMARY total=14 passed=14 failed=0`
- 耗时：86s
- receipt：`tmp/checks/1795424320236/s1-e2e.log`
- 关键行：
  - `E2E#12 PASS body=clean alias=clean keys=clean safety=clean errors=clean`
  - `E2E#13 PASS locked=401 wrong=401 reopened=1 history=1`
  - `E2E#14 PASS auto=1 oldA=f553671b newA=25836ab9 oldB=f553671b newB=25836ab9`
  - `E2E#15 PASS dropped=1 gap=1 retry_identical=1 gap_to_resend_ms=23 bCount=2 aDelivered=2`

### `./check.sh`

- 结果：全绿（89.41s）；`core.test` 145、`store.test` 24、`transport.test` 7、`server.test` 7；Q1–Q6 `QUINT_CHECKS PASS`；spike 100 条 + reconnect `ok=true`。
- 回执：`TENK_REPLAY messages=10000 elapsed_ms=13063 outgoing=0 next_seq=10001`；`STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1604 read_ms=126 total_ms=1730`。
- `server.e2e` 不在 `check.sh` 内，已独立运行。

## 未关闭与限制

- E2E#13 只测手动锁；空闲自动锁由 `AppLockSpec` 注入时钟覆盖，不在 E2E 重复。
- E2E#12 的“诊断”限定为服务日志和 HTTP 错误体；`/api/state` 是 UI 数据接口，仍包含 public key，不当作日志。
- E2E#14 的注入帧使用测试进程读取的 A 持久身份/会话；不是生产后门。自动重建后旧 pending 的跨 session 重映射仍未实现；真实发送方先消费一个序号后继续发送的连续性未证明（review R2-1 open）。
- `#14` 失败上限/冷却由既有 `RekeySpec`/`RekeyTimerSpec` 覆盖；E2E 只跑成功重建主路径。
- `#8`/10k、冷启动/内存、UI DOM 自动化未做。
- 外部密码学评审仍是发布门。

## Review

- Round 1 的 R1-1–R1-6 已关闭；Round 2 复查发现 R2-1（真实发送序号连续性）、R2-4（重建失败通知）、R2-5（跨 cutover 窗口上限）仍 open；详见 `design-review-findings.md`。
