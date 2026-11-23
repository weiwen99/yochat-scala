# Plan 0005 design: S1 rejection and fault E2E

## 1. 范围与规模

目标：把 `docs/agents/verification.md` 的 4 条未完成行为契约落到真实两进程 E2E：

- #9 错误/过期邀请被拒绝；
- #10 篡改密文被拒绝；
- #11 篡改签名被拒绝；
- #15 一个 Data 帧在连接中被丢弃，接收方发 `Gap`，发送方重发，双方各显示一次，发送方最终 `Delivered`。

| 维度 | 需求 | 本计划产出 | 倍率 |
| --- | --- | --- | --- |
| 契约条数 | 4 | 4 | 1× |
| 产品表/字段/配置/协议 | 0 | 0 | 1× |
| 产品组件 | 0 | 0 | 1× |
| 测试组件 | 1 个故障注入 relay 代理 | 1 个 `FaultRelay`，复用现有 `ServiceProcess`/`RelayHandle`/`Http` | 1× |
| 工程师日 | 约 2–3 人日 | 2–3（估算） | 1× |
| 发布波次 | 0（测试门） | 0 | 1× |

没有产品代码变更是默认结果；若契约暴露产品缺陷，单独最小修复并新增失败回归，不借机扩展协议。

## 2. 复用现有机制

- 复用 `server/test/src/yochat/server/e2e/E2ESupport.scala` 的两进程夹具、HTTP 驱动、临时目录和 receipt。
- 复用 `RelayHandle` 启动的真实 `RelayServer`；故障代理只插在服务进程与真实 relay 之间，不替换真实转发逻辑。
- 复用 `transport.RelayWire` 的帧读写和 `RelayPayload` 编解码；测试不复制 relay wire 格式。
- 复用 core `WireFrame`/`MessageHeader` 解析并定位密文、签名和 `Gap`，不复制密码学。
- #9 不新增测试 API；用 API 的 400 响应和现有 state 判断拒绝。

为什么不能只加 core 单测：本计划验证的是“真实 HTTP -> service -> runtime -> SQLite -> RelayClient -> relay -> 对端”路径；core 已有覆盖，但不能替代两进程行为契约。

## 3. `FaultRelay`（仅测试）

在 `server/test` 新增一个 TCP 代理：

```text
service A/B  ->  FaultRelay(public port)  ->  RelayServer(private port)
```

- 每个客户端连接先注册 device key；代理记录连接对应的 device。
- 双向按 `RelayWire.readFrame/writeFrame` 转发；注册帧、RegisterAck、控制帧默认原样通过。
- 一次性故障：
  - `Drop(sourceDevice, targetDevice, seq)`：匹配到该 Data 帧时消费它，不转发；
  - `TamperCiphertext(...)` / `TamperSignature(...)`：匹配后用 core `WireFrame.parse` 重建一个被篡改的合法帧，再转发。
- 同时记录是否观察到一个 `Gap` Data 帧，供 #15 断言。
- `arm` 只允许一次；`awaitApplied` 有超时；连接、监听器和上游 relay 在 `finally` 中关闭。
- 代理不记录正文、账号、安全码或密钥；receipt 只写 contract 计数/状态。

## 4. 各契约

### #9 错误/过期邀请

- 截断/改坏一条真实邀请文本：`POST /api/invitations/receive` 必须拒绝（4xx），state 不出现可用 contact。
- 用测试代码生成一条签名有效但 `expiresAt` 已过的邀请，账号与接收方期望匹配（`@A`）：接收/接受路径必须拒绝，state 不出现可用 contact；另跑一次真实 valid handshake 作为必须接受的对照。
- 断言没有可用联系人和可见消息；不声称检查 SQLite 行数，不依赖私有错误类型文本。

### #10/#11 篡改

- 完成握手和安全码验证，确保只差业务 Data。
- 武装代理，A 发送一条消息。
- 代理篡改首条 Data 的密文（#10）或签名（#11）一次，并实际转发坏帧。
- 断言：
  - 代理回执 `forwarded=1`，证明不是直接丢包；
  - B 不出现该正文；
  - A 的重试 `WireFrame` 与原始帧逐字节相同；
  - B 最终恰好显示一次该正文，A 的该消息为 `delivered`；
  - receipt 记录 `tampered=1`、`forwarded=1`、`retried=1`。
- 只篡改一个字节/一个帧；不声称覆盖所有篡改位置。

### #15 丢弃 `seq=1`

- 完成握手和安全码验证。
- 代理武装为丢弃 A -> B 的 `seq=1` Data。
- A 连续发送 `one`、`two`。
- B 收到 `two` 后必须发出 `Gap`；代理观察并记录 `Gap` 与 A 的 `seq=1` 重发顺序。
- A 的重发必须与原始 `seq=1` 逐字节相同，且在 `Gap` 被转发后 `<1.8s` 内到达；仅靠 2s retry backoff 或 5s delivery timer 不能通过。
- A 重发后 B 显示恰好 `[one, two]`，A 两条都 `delivered`。
- 这是主要路径的一个丢帧，不是通用丢包/网络仿真。

## 5. 事务与并发

- 本计划不新增数据库事务；不改锁序。代理只消费/重建单个 relay 帧。
- `FaultRelay` 的故障状态用原子引用；每个连接两个转发线程，`finally` 关闭 socket；测试结束按 service -> proxy -> upstream relay 顺序清理。
- 任何等待都有截止；失败路径写 receipt 并抛出可定位断言。

## 6. 验收

1. E2E#9/#10/#11/#15 在真实 relay + 两个真实服务进程上通过。
2. 现有 E2E#1–#7 不回归。
3. 代理每个故障只应用一次；#15 观察到 `Gap`。
4. 产品代码 diff 为空；若不为空，说明失败暴露了真实缺陷，需单独记录并评审。
5. `./mill server.e2e`、`./mill server.test`、`./check.sh` 回执完整。

## 7. 已知约束

- 不测通用网络损坏、乱序、重放、连接半开、长时丢包。
- 不测 UI DOM/Electron；仍以 HTTP state/messages 为 UI 数据源。
- 不替代 1k/10k 性能门。
- 过期邀请由测试代码用 core 生成；不增加可配置的测试时钟或生产后门。
- 代理不解析密文正文，只按 `WireFrame` 边界定位签名字节和密文字节。

## 8. Review gates

| Gate | 可证伪证据 |
| --- | --- |
| G1 代理保真 | 未武装代理跑通注册、握手、消息与 ACK（`proxy_fidelity=1`）；直连 E2E#1–#7 同时保持全绿。 |
| G2 #9 拒绝 | 篡改/过期输入后 state 无可用 contact，消息为空；4xx 或 accept 拒绝。 |
| G3 #10/#11 拒绝 | 被篡改帧的正文从未出现；重试后恰好一次且 `delivered`。 |
| G4 #15 Gap | 丢弃 `seq=1` 后代理观察到 `Gap`，最终顺序 `[one,two]`。 |
| G5 无产品扩散 | 产品源码/表/协议/配置 diff 为空，或每处变更都有失败回归。 |
| G6 范围纪律 | 未加入 #8/#12–#14、10k、UI 自动化或网络 fuzzer。 |
