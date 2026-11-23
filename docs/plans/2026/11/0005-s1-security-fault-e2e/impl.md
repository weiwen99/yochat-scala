# Plan 0005 implementation: S1 rejection and fault E2E

- 状态: implemented（E2E#1–#7/#9/#10/#11/#15 全绿；check 全绿）
- 日期: 2026-11-23
- 分支: `feat/plan-0005-s1-security-fault-e2e`
- 基线: `4b55085`
- 设计: `design.md`；review: `design-review-findings.md`

## 范围与结果

本计划只增加测试侧故障注入器和 4 条行为契约，没有改生产协议、表、header、配置、UI 或 Quint 模型。

| 契约 | 结果 | 证据 |
| --- | --- | --- |
| E2E#9 | PASS | 真实 handshake 对照 `valid=1`；错误账号 accept 400；篡改邀请 400；过期邀请 accept 400；无可用 contact |
| E2E#10 | PASS | 未武装代理 `proxy_fidelity=1`；坏密文实际转发 `forwarded=1`；A 重试 `retried=1`；B 恰好一次，A `delivered` |
| E2E#11 | PASS | 坏签名实际转发 `forwarded=1`；A 重试 `retried=1`；B 恰好一次，A `delivered` |
| E2E#15 | PASS | 丢弃 `Data(1)`，B 发 `Gap(1)`；A 重发与原件逐字节相同，`gap_to_resend_ms=24`；B `[one,two]`，A 两条 `delivered` |
| E2E#1–#7 | PASS | 直连 relay 回归保持全绿 |

## 变更

- `server/test/src/yochat/server/e2e/FaultRelay.scala`（新增，测试侧）：
  - 在服务进程与真实 `RelayServer` 之间转发 `RelayWire` 帧；
  - 一次性 `Drop`/`TamperCiphertext`/`TamperSignature`，按 source/target device 和 `seq` 匹配；
  - 记录 `forwarded`、`retry_matches_original`、`Gap` 与重发顺序/耗时；
  - 不解析正文，不记录账号、安全码或密钥；无故障时逐字节转发。
- `server/test/src/yochat/server/e2e/E2ESupport.scala`：
  - `RelayControl` 抽象，`RelayHandle` 与 `FaultRelay` 共用 `Pair` 生命周期；
  - `withFaultPair`；
  - `tryReceiveInvitation`/`tryAcceptInvitation` 返回原始 HTTP 结果。
- `server/test/src/yochat/server/e2e/S1E2E.scala`：
  - E2E#9/#10/#11/#15；
  - `expiredInvitationText` 用 core `Invitation.create` 签发账号匹配、仅过期条件不同的邀请；
  - #10 增加未武装代理的 `proxy-fidelity` 场景；#15 增加 Gap 先于重发、重发字节一致和 <1.8s 断言。
- 生产源码 diff: 0。

## 验证回执

### `./mill server.e2e`

- 结果：`E2E_SUMMARY total=11 passed=11 failed=0`
- 耗时：68s
- receipt：`tmp/checks/1795420331506/s1-e2e.log`
- 关键行：
  - `E2E#9 PASS valid=1 wrong=400 tampered=400 expired=true contacts=0`
  - `E2E#10 PASS proxy_fidelity=1 tampered=1 forwarded=1 retried=1 bCount=1 aDelivered=1`
  - `E2E#11 PASS tampered=1 forwarded=1 retried=1 bCount=1 aDelivered=1`
  - `E2E#15 PASS dropped=1 gap=1 retry_identical=1 gap_to_resend_ms=24 bCount=2 aDelivered=2`

### `./check.sh`

- 结果：全绿（91.38s）；`core.test` 145、`store.test` 24、`transport.test` 7、`server.test` 7；Q1–Q6 `QUINT_CHECKS PASS`；spike 100 条 + reconnect `ok=true`。
- 回执：`TENK_REPLAY messages=10000 elapsed_ms=12571 outgoing=0 next_seq=10001`；`STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1706 read_ms=126 total_ms=1832`。
- `server.e2e` 不在 `check.sh` 内，已独立运行。

## 未关闭与限制

- E2E#10 篡改密文字节也会使覆盖密文的签名失效；本契约证明坏帧被转发并拒绝、原始帧重试成功，不单独证明 AEAD 失败或 #14 的通道重建。
- E2E#9 只断言无可用联系人和可见消息；不检查 SQLite 行数，不声称“零落盘”。
- 代理一次只注入一个 Data 帧；不覆盖乱序、重放、半开连接、持续丢包或任意篡改位置。
- E2E#8/10k、冷启动/内存、E2E#12/#13/#14、UI 自动化未做。
- 外部密码学评审仍是发布门。

## Review

- Round 1 的 R1-1–R1-5 已在实现中关闭：Gap 因果证据、坏帧转发/重试字节一致、过期唯一原因、可见状态限定、代理保真。
- Round 2 复核冻结 diff：无剩余 BLOCKER/MAJOR；残余风险如上。
