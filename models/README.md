# S1 Quint 模型

Q1–Q5 的有限模型，对应 `docs/plans/2026/11/0001-yochat-rewrite/design.md` 的协议面。模型只看应用层状态，不建模签名、AEAD、真实网络或 SQLite；这些由 Scala 测试与 E2E 分层承担。模型不是 S1 完成证明。

| 模型 | 覆盖 |
| --- | --- |
| `q1_relationship.qnt` | 邀请/应答一次、账号不符、拒绝静默、安全码正文门 |
| `q2_order.qnt` | 单作者 1..3 序号、缺口缓冲、连续冲刷、去重 |
| `q3_resend.qnt` | 三次尝试上限、耗尽通知、送达先尝试 |
| `q4_reconnect.qnt` | 离线队列不丢、重连重放有界、稳定连接下送达 |
| `q5_rekey.qnt` | 旧会话 ReadOnly、新会话 confirm/ack、零 pending 才删除、安全码保留 |

## 命令

需要 Node/npm、Java 25（TLC 由 Quint 启动）、`jq`。固定 Quint `0.33.1`。

```bash
./models/check.sh
```

脚本先 `typecheck` 五个文件，再按 `checks.json` 逐个执行：

- Apalache `0.62.4` 检查 `safe` 不变量；
- TLC 检查带公平性前提的 temporal：
  - `allArriveApplied`：三个接收动作弱公平时，1..3 最终应用；
  - `deliveryUnderFairReconnect`：连接最终稳定且 `deliver` 弱公平时最终送达；
  - `newSessionEventuallyActive`：`start`/`confirm` 弱公平时新会话最终 active。

`checks.json` 记录文件 SHA-256、backend、invariants、temporal、max steps；改模型必须同步更新 hash，脚本会拒绝漂移。

## 口径

- `q3` 的发送成功是非确定的，模型只证明“有界 + 耗尽可见”，不证明不公平网络下最终送达。
- `q4` 的活性明确以“连接最终稳定”为前提；反复断线后耗尽预算是合法失败，不是模型漏项。
- `q5` 是有限状态 cutover 模型；真实 control-frame 编解码、持久化和 SQLite 删除仍由 `core`/`store` 测试承担。
## Trace replay

`traces/q2_order.itf.json` 是 `--seed=0x1` 的 Q2 模拟轨迹（9 个状态，接收顺序 3、2、1，最终应用 1..3）。`core/test/src/yochat/core/app/ReplaySpec.scala` 读取该 ITF，按 `seen` 差分推导 `receive1/2/3`，逐步调用真实 `Application.step`，比较 `seen`、`applied`、`buffered`；trace SHA-256 也固定在测试与 `checks.json` 中。

Q1/Q3/Q4/Q5 的 trace replay 尚未实现；当前其它模型与 Scala 语义测试仍是并列证据，不能互相替代。
