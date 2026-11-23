# Plan 0003 implementation: S1 functional E2E

- 状态: implemented（服务层流程 #1–#7；安全契约 #1/#2/#3 绑定仍 open）
- 日期: 2026-11-23
- 分支: `feat/plan-0003-s1-functional-e2e`
- 基线: `8977ebd`
- 设计: `design.md`；review: `design-review-findings.md`

## 范围与结果

本实现只增加两进程服务层 E2E harness，并修复 E2E 暴露的 lock-generation 采纳缺陷。没有新增表、协议 wire、Quint 模型、UI 屏幕或性能负载；`server.bench` 的数字不用于本计划。

| 契约 | 结果 | 证据 |
| --- | --- | --- |
| E2E#1 | PASS（服务层） | 邀请/应答后双方各 1 个联系人；A `pending`、B `confirmed`；双方 `safetyVerified=false`；各自安全码与本地派生段一致；安全门前正文不落盘/不显示 |
| E2E#2 | PASS | 双方联系人各 1、conversation 各 1；安全门前负例仍为空 |
| E2E#3 | PASS（服务层） | 安全门前消息列表为空；安全码轮询为 true；A `seq=1` `delivered`、B `seq=1` `received` |
| E2E#4 | PASS | A 锁、停进程、同目录重启；`instanceExists=true`、identity 不变；旧消息仍在；新消息 `seq=2` 送达 |
| E2E#5 | PASS | B 锁、停进程、同目录重启；旧消息仍在；A 的 `seq=2` 继续送达 |
| E2E#6 | PASS | relay 关闭后双方离线；A 的离线消息 `pending` 且 B 未收到；同端口 relay 重启后 B 收到一次、A `delivered` |
| E2E#7 | PASS | B 返回数组恰好 100 条 `peer` 1..100，体顺序一致、无重复；A 100 条 `delivered` |

## 变更

- `build.mill`：新增 `server.e2e` 命令，沿用 `server.test.runClasspath()`，不新增模块。
- `server/test/src/yochat/server/e2e/E2ESupport.scala`：进程夹具、HTTP 驱动、relay 生命周期、脱敏摘要、receipt。
- `server/test/src/yochat/server/e2e/S1E2E.scala`：`E2E#1–#7` 场景与断言。
- `store/src/yochat/store/SessionRepository.scala`：新增 `adoptLockGeneration`，在一次事务内按 `sessions → meta` 重新封印已有 session；lock 后旧行仍带旧 generation，解锁直接写会 `StaleGeneration`。
- `store/src/yochat/store/Store.scala`：暴露 `adoptLockGeneration`。
- `server/src/yochat/server/AppRuntime.scala`：restore 成功后、启动 runtime 前采纳当前 lock generation。
- `store/test/src/yochat/store/SmokeSpec.scala`：新增回归：bump 后旧 session 写失败；adopt 后以新 generation 写入成功。

## 验证回执

### `./mill server.e2e`

- 结果：`E2E_SUMMARY total=7 passed=7 failed=0`
- 耗时：32.22s（committed HEAD 上运行）
- receipt：`tmp/checks/1795412581757/s1-e2e.log`（被 `.gitignore` 覆盖；head=`da4b8d6`，含 Java 版本、每条耗时/计数）
- 早期运行暴露并修复：A 重启后发送触发 `StoreFailed:StaleGeneration`；根因是 lock 只推进 meta，session 行仍为旧 generation。修复后 E2E#4/#5 以 `seq=2` 通过。

### `./mill store.test`

- 结果：全绿（24 个测试）；`SmokeSpec` 2 个，新回归覆盖旧 generation、旧 version 与错误预期 generation 的采纳拒绝，以及 fresh 写入成功。
- 回执：`STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1693 read_ms=139 total_ms=1832`（review 修复后本次运行，临时目录）。

### `./mill server.test`

- 结果：全绿；`AppEffectExecutorSpec` 3、`TimerServiceSpec` 1、`AppRuntimeSpec` 1、`HttpApiSpec` 1。
- 说明：`AppRuntimeSpec` 仍是同 JVM 集成层；本计划的 E2E 使用真实子进程。

### `./check.sh`

- 结果：全绿（83.70s）；`core.test` 机器报告 69 个测试、`store.test` 24、`transport.test` 7、`server.test` 6；Q1–Q6 `QUINT_CHECKS PASS`；spike 100 条 + reconnect `ok=true`。
- 回执：`TENK_REPLAY messages=10000 elapsed_ms=12286 outgoing=0 next_seq=10001`；`STORE_THROUGHPUT inserts=1000 reads=1000 insert_ms=1686 read_ms=165 total_ms=1852`。
- `./check.sh` 不包含 `server.e2e`；本计划的 7 条 E2E 由独立命令运行。
- 格式/静态检查范围：`server.checkFormat`/`server.fix` 只覆盖主源码；`server.test` 的 E2E 源码由 Scala 编译 `-Werror` 兜底，但不在 scalafmt/scalafix 的 `checkFormat` 任务范围内。这是当前仓库门禁的实际边界，不写成“测试源码已格式化”。

## 未关闭与限制

- **ADR-0006 §3.3 首条业务消息绑定**：尚未实现双方渠道账号 + 安全码断言；因此 `E2E#1/#2` 的安全契约关闭仍 open，`E2E#3` 的安全含义也保持 open。本轮 7 条绿色只证明服务层流程。
- **wire 级安全门**：安全门前正文不落盘/不显示已证明；没有监测 DATA 帧，所以“wire 未发送”明确未证明。
- `E2E#8` 1k 吞吐、10k 两进程、冷启动、空闲内存未跑；不得用 `server.bench` 或本 E2E 数字替代。
- `E2E#9–#15` 未做，包括错误/过期邀请、篡改、日志内容、锁/空闲、重建、Gap 注入。
- UI 未自动化；本轮只验证 `/api/state` 与消息列表这个 UI 数据源，不证明 DOM/Electron。
- 外部密码学评审仍是发布门。

## Review

- Round 1 的 7 条发现与关闭见 `design-review-findings.md`；F1 通过收窄回执范围关闭，F2–F6 已在实现中处理，F7 改文档。
- Round 2 复核了冻结实现 diff：发现 7 条（0 BLOCKER、6 MAJOR、1 MINOR），已全部关闭。关键修复是 `adoptLockGeneration(expected)` 的恢复代数 fence、旧 generation/version 回归、同 FIFO 安全门 barrier、子进程清理、失败路径脱敏、公开路由复核、完整 100 行历史和回执口径。并发直接打开同一目录记为 S1 不支持路径。
