# S1/S2-A/S2-B Quint 模型

Q1–Q6 的有限模型，对应 `docs/plans/2026/11/0001-yochat-rewrite/design.md` 的协议面。模型只看应用层状态，不建模签名、AEAD、真实网络或 SQLite；这些由 Scala 测试与 E2E 分层承担。模型不是 S1 完成证明。

| 模型 | 覆盖 |
| --- | --- |
| `q1_relationship.qnt` | 邀请/应答一次、账号不符、拒绝静默、安全码正文门 |
| `q2_order.qnt` | 单作者 1..3 序号、缺口缓冲、连续冲刷、去重 |
| `q3_resend.qnt` | 三次尝试上限、耗尽通知、送达先尝试 |
| `q4_reconnect.qnt` | 离线队列不丢、重连重放有界、稳定连接下送达 |
| `q5_rekey.qnt` | 旧会话 ReadOnly、新会话 confirm/ack、零 pending 才删除、安全码保留 |
| `q6_delivery.qnt` | ACK 终止重试、Gap 有界重发、transport 接受不等于送达、失败可见 |
| `q8_device_lists.qnt` | B1 签名设备列表：吊销永久、旧快照不复活、版本非负 |

## 命令

需要 Node/npm、Java 25（TLC 由 Quint 启动）、`jq`。固定 Quint `0.33.1`。

```bash
./models/check.sh
```

脚本先 `typecheck` 七个文件，再按 `checks.json` 逐个执行：

- Apalache `0.62.4` 检查 `safe` 不变量；
- TLC 检查带公平性前提的 temporal：
  - `allArriveApplied`：三个接收动作弱公平时，1..3 最终应用；
  - `deliveryUnderFairReconnect`：连接最终稳定且 `deliver` 弱公平时最终送达；
  - `newSessionEventuallyActive`：`start`/`confirm` 弱公平时新会话最终 active。

`checks.json` 记录文件 SHA-256、backend、invariants、temporal、max steps；改模型必须同步更新 hash，脚本会拒绝漂移。

## 口径

- `q3` 只证明尝试预算与耗尽可见；发送成功仍是抽象的 transport 结果，不证明对端送达。ACK/缺口语义由 `q6` 和 `DeliverySpec` 承担。
- `q4` 的活性明确以“连接最终稳定”为前提；反复断线后耗尽预算是合法失败，不是模型漏项。
- `q5` 是有限状态 cutover 模型；真实 control-frame 编解码、持久化和 SQLite 删除仍由 `core`/`store` 测试承担。
- `q6` 是 ACK/缺口模型，没有固定 trace；真实帧编解码和发送窗口由 `DeliverySpec`、`TenKReplaySpec` 覆盖。

## Trace replay

`--seed=0x1` 生成的固定轨迹放在 `traces/`；`checks.json` 同时固定源码与 trace 的 SHA-256。

| Trace | 状态数 | Scala 重放 |
| --- | ---: | --- |
| `q1_relationship.itf.json` | 11 | `ReplayModelsSpec`：reply、mismatch、安全码、正文门；比较双方联系人与 outgoing 投影。 |
| `q2_order.itf.json` | 9 | `ReplaySpec`：按 `seen` 差分推导 receive 3→2→1，比较 `seen/applied/buffered`。 |
| `q3_resend.itf.json` | 11 | `ReplayModelsSpec`：三次尝试耗尽、通知、离线；比较 attempts/online/pending/notified。 |
| `q4_reconnect.itf.json` | 11 | `ReplayModelsSpec`：离线队列、两次连接重放；比较 attempts/connected/pending。 |
| `q5_rekey.itf.json` | 11 | `ReplayModelsSpec`：按核心 rekey 序列执行 start/confirm/ack/firstData，并对每个 trace 状态检查 Q5 安全不变量。 |

Q1/Q3/Q4 是逐状态观测投影；Q5 的 `start`/`firstData` 是抽象动作，无法与真实 control-frame 顺序一一对应，因此只做安全不变量投影，不声称状态逐字段等价。所有重放都直接调用真实 `Application.step`，不是另写模型。
