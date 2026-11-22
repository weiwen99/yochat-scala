# S1 送达确认、缺口请求与有界发送窗口

**状态**: accepted (2026-11-22)

**前序**: ADR-0002（relay-only）、ADR-0003（S1 范围）、ADR-0004（测试/性能门）、ADR-0006（会话加密）、ADR-0007（存储）。

## 背景

2026-11-22 的微信群反馈对照把旧实现的失败形状归到三处：固定 30 秒 ACK 与 600 帧/分钟限流互相打架；每条未确认消息逐条重封，10k 回放追加 9,901 个重复日志位置；高优先级消息积压饿死低优先级消息。当前 Scala 分支没有这些问题，但也没有把设计里的“无确认时兜底重发、接收方请求重发”实现出来：`SendSucceeded` 只表示 transport sink 接受帧，没有对端送达证明。

用户在同一天裁决：

1. S1 接受 relay-only；
2. 加数据 ACK 与缺口请求；
3. 先把 `seen`/`applied`/`outgoing` 的有界化做完；
4. 把 10,000 条加进 S1 性能门。

## 规模

| 维度 | 需求 | 本 ADR | 倍率 | 说明 |
| --- | --- | --- | ---: | --- |
| 新表 | 0 | 0 | 1× | 沿用 `messages` 的状态列 |
| 新组件 | 0 | 0 | 1× | 仍在 core/store/transport 边界内 |
| 新消息类型 | 2 | 2 | 1× | `DataAck`、`Gap`，都是控制帧 |
| 新持久状态 | 1 | 1 | 1× | `MessageState.Delivered` |
| 新协议限制 | 4 | 4 | 1× | 在途窗口、出站上限、失败保留、入站缺口沿用 256 |
| 工程师日 | 3 | 3–4（估算） | 1.3× | 含模型改动、测试；不含 M3 transport |

不新增表、不新增服务、不新增第二套重试队列；不为多设备提前造设备清单。

## 决策

### 1. 两种控制帧

新增 `MessageKind.DataAck` 与 `MessageKind.Gap`。两者都：

- 在会话棘轮下加密、签名；
- `control = None`，载荷为空；
- 用 header 的 `seq` 携带序号：`DataAck` 是累计确认的最高连续数据序号，`Gap` 是接收方期望的下一个数据序号；
- 不进入 `messages` 表，不占数据消息序号；
- 不改变 `MessageHeader.Version = 1`，是同一协议版本内的增量类型。

`DataAck` 的语义是“本会话里，我已按序应用到你发来的第 N 条数据”。S1 每方一台设备，所以 `(sessionId, seq)` 足以定位；S2 多设备时必须把被确认的 device 加进语义，另写 ADR，不能直接复用。

### 2. 接收方

- 数据帧按序应用后，发送累计 ACK，N = 当前 `nextSeq - 1`。
- 旧序号重复到达时，不重复落盘，重发当前累计 ACK；这样发送方丢 ACK 后可以靠重传收敛。
- 收到未来序号、出现缺口时，缓冲并发送 `Gap(expected)`；同一缺口只发一次，缺口推进后清除。
- 入站缓冲沿用 `MaxBuffered = 256`。缓冲满时返回 `Overflow`，仍然发送 `Gap(expected)`，并让 UI 可见“等待/缺口”；不静默丢弃。
- ACK/Gap 本身不触发 ACK，避免控制帧互相确认形成环。

### 3. 发送方

- `OutgoingState` 增加 `InFlight`，语义如下：
  - `Persisting`：已生成密文，等待落盘；
  - `Queued`：已落盘，等待发送窗口；
  - `InFlight`：已发出 `SendWire`，等待 transport 接受或失败；
  - `Sent`：transport 已接受，等待对端 `DataAck`；
  - `Failed`：重试耗尽，UI 可见。
- `MessageState` 增加 `Delivered`：收到认证 ACK 后写入。`Sent` 只表示 transport 接受，UI 不得把它显示成“已送达”。
- ACK 覆盖的所有 `InFlight`/`Sent` 消息转为 `Delivered`，取消其 delivery/retry timer，并从核心 `outgoing` 移除；store 保留历史。
- `Gap(expected)` 把 `expected` 之后、窗口内的未确认消息重新置为 `Queued`，再按窗口发送；重发计入尝试次数。
- 重试仍有界：`MaxSendAttempts = 3`，`DeliveryTimeoutMs = 5_000`，`RetryBackoffMs = 2_000`。收到 ACK 后不再重试；耗尽后转 `Failed` 并通知，不静默丢。
- 不做消息优先级；发送顺序按本地消息 id 的 FIFO，窗口满时等待 ACK。

### 4. 有界状态与窗口

| 限制 | 值 | 作用 |
| --- | ---: | --- |
| `MaxInFlight` | 32 | 同时等待 transport/对端确认的消息数 |
| `MaxOutgoing` | 1,024 | 核心 `outgoing` 的硬上限；满了拒绝新发送并通知 |
| `MaxFailedRetained` | 64 | `Failed` 在核心里的保留数，超出从最旧开始移除；store 仍有记录 |
| `MaxBuffered` | 256 | 入站缺口缓冲，沿用现有值 |

- `Conversation.seen` 只保存尚未应用的未来序号；已应用序号由 `nextSeq` 水位判断，旧序号直接拒绝且不记账。
- `Conversation` 不再保存完整 `applied` 历史；应用结果通过 effect 交给 store/UI，store 是历史来源。
- `outgoing` 在 `Delivered` 时删除；`Failed` 受 `MaxFailedRetained` 限制；`UserSend` 在 `outgoing.size >= MaxOutgoing` 时返回 `Notification.QueueFull`，不生成密文。
- `pump` 只在在线时发送；窗口计数包含 `InFlight` 与 `Sent`。断线时把 `InFlight`/`Sent` 转回 `Queued` 并取消 timer；重连后按窗口重放。

### 5. 持久化与效果顺序

- ACK/Gap 帧由 core 在 `InboundFrame` 处理中生成；executor 提供新的 `responseNonce`，core 更新棘轮状态并返回 `PersistSession` + `SendAck`/`SendGap`。先持久化，再发送。
- `DeliveryAckReceived` 由 executor 解密控制帧后送入 core；core 返回 `UpdateMessageState(Delivered)`、`CancelTimer` 和后续 `SendWire`。
- `GapReceived` 只重排/重发已有密文，不重新加密；原 `wire` 必须逐字节复用。
- 所有新效果都幂等：重复 ACK 幂等；重复 Gap 只重发未确认项；旧 timer 的 `CancelTimer` 可重复。

### 6. 10k 门

- 1,000 条两进程端到端 <5s（既有门）。
- 10,000 条协议回放 <50s：真实 `Application.step`、固定种子、无 IO，检查全部 `Delivered` 且核心状态有界。
- 10,000 条两进程端到端 <60s：真实 SQLite、transport harness，S1 交付门。
- 100,000 条不进 S1；需要时单独定义历史同步、持久化和 UI 口径。
- 首次完整回执如实记录；失败先修。确需改阈值，单独写 ADR 并人签字。

## 备选与否决

| 方案 | 否决理由 |
| --- | --- |
| 没有 ACK，只靠 5 秒定时重发 | 无法区分“对端已收到”和“transport 已接受”；UI 只能撒谎或永远显示“发送中” |
| 把 relay 的接受当送达 | relay 只证明它收了帧，不证明对端解密/应用 |
| 每条消息一个 timer、无窗口 | 旧实现的失败形状：20,001 条待发效果、9,901 个重复位置 |
| 消息优先级队列 | 旧实现已出现高优先级饿死低优先级 |
| 固定 600 帧/分钟 + 30 秒 ACK | 限流和 ACK 期限耦合，突发时必然互相打架 |
| 无限重试 | 违反“所有等待/队列/重试有界”，且会拖住历史/新消息 |

## 后果

- 协议模型要改一轮：新增 Q6 覆盖 ACK/缺口；Q3 保留尝试预算；Q4 的重连要覆盖 ACK 丢失和 Gap 重发。模型改动单独签字，不顺手改。
- `MessageState` 增加 `Delivered`；store 测试、executor 测试、UI 状态都要跟着改。
- 10k 门会让 1k 的线性外推暴露出来；如果 10k 失败，先查窗口、ACK 批量、SQLite 事务和 timer 数量，不加优先级队列。
- S1 的 UI 文案必须区分“已交给中继”“已送达”“失败/等待”。没有 ACK 的旧文案不算兼容。
