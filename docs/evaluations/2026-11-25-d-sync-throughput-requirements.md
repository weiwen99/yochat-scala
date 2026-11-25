# D 的同步速率、事件驱动与状态缓存需求（2026-11-25）

- 对象：2026-11-25 两段群聊里 D 提出的需求与批评，13:20–13:31 谈速率与事务（`133905`–`133916`），13:41–13:47 谈定时器接收、UI 与传输剥离、传输状态缓存（`133949`–`133973`）。D 自述以产品经理身份提需求、不写工程规范（`133907`）。
- 用途：把外部需求留档，供后续范围裁决与性能工作引用。本文不改代码、不改门禁，也不把需求直接升格为验收。
- 关联：[2026-11-23 10k 同步与存储口径讨论](2026-11-23-tenk-sync-discussion.md)、Plan 0008（S1 E2E 性能）、ADR-0003（v1 范围）与 `PRODUCT.md` 的能力边界。

## 需求

| 编号 | D 的原话 | 解读 |
| --- | --- | --- |
| R1 | 「历史同步还是没到 1000条/秒」（`133905`）；「每天累积 1w 行消息了，同步 10秒我我觉得还行，再长觉得有点慢」（`133908`） | 1 万条历史在 10 秒内同步完，折算 1,000 条/秒 |
| R2 | 「你这里好像也是接收端定时收取，而不是网络事件触发」（`133905`） | 接收侧应由网络事件驱动，不使用定时轮询 |
| R3 | 「网络不会是瓶颈，占用的带宽不大的」（`133913`） | D 对瓶颈位置的判断，不是测量结果 |
| R4 | 「一条一个事务的话抗不住的」（`133915`）、「一个事务在 10ms 量级」（`133916`） | 建议批量事务；这是他旧实现的单事务成本 |

补充背景：D 前一天说「历史同步只发生在同一用户的多设备之间，所以不太担心攻击」（`129411`），速率要求针对的是这条路径，不是实时单聊。

## 追加需求（13:41–13:47）

| 编号 | D 的原话 | 解读 |
| --- | --- | --- |
| R5 | 「有点奇怪，为什么 sol 和 dsf 都倾向用定时器触发来接收消息」（`133949`）；「sol 还是在 tauri 的 web 层做的定时器，然后调用到 rust 层」（`133951`）；「。。。网络事件触发啊」（`133952`） | 接收不能靠定时器轮询，要由网络事件驱动 |
| R6 | 「这个我让 AI 改进网络传输跟 UI 剥离了，存储完后给 UI 发个通知——这才是正常脑子想的做法吧，哪有 UI 定时去网络层 poll 的」（`133956`） | 传输层与 UI 解耦；落盘完成后向 UI 推送通知，UI 不主动轮询 |
| R7 | 「还有个傻逼的地方是传输状态没有在内存里缓存最新状态，每次来个消息就从 db 里查一下状态，我让它改成内存里缓存了」（`133972`）；「很典型的 web crud 风格，拿 db 当内存用」（`133973`） | 传输状态以内存为准，不要每条消息都回库读 |

## 与当前实现的对照

### R1 速率

- 已有观测：`PERF#RATE` 335.4 msg/s，条件为 10,000 条 128 字节消息、64 条在途、1,000 条预热；同批三次隔离运行在 325.0–337.7 msg/s 之间。来源是分支 `feat/s-throughput`（源码 `b56bb68`、回执 `d56a99b`、跨主机 `8e4d1f8`），该分支尚未合入 `main`。
- 折成 R1：10,000 条按 335 msg/s 约需 29.8 秒，比 10 秒的目标慢约 3 倍。
- `PERF#RATE` 是观测值，本身没有速率阈值；它不含 UI 渲染。

### R2 事件驱动

- 当前接收路径不是定时轮询：`RelayClient` 用一个有界 `Queue` 接到网络读线程，`incoming` 是阻塞 `take`（`transport/src/yochat/transport/RelayClient.scala:36`）。
- 代码里 10 ms 级的 `IO.sleep` 只看得到三处，都不在接收主路径：`awaitOnline` 的就绪探测、重连退避、测试用的 `awaitState`／`awaitStoredInvitation`（`server/src/yochat/server/AppRuntime.scala:227-252`）。
- 另一处可能被看到的是测试本身：吞吐测试早期用「每 10 ms 轮询历史 API」等待完成，现已改为直接查 SQLite 计数。
- 所以 R2 按当前代码不成立，需要向 D 确认他指的是哪一段；若是要求去掉某一处定时器，要先给出位置。

### R4 单条事务

- 当前实现确实是每条消息一次事务，并且 `synchronous=FULL`（`store/src/yochat/store/Store.scala:433`、`MessageRepository.scala:89`）。
- 但单条成本不是 10 ms：2026-11-25 在 `main`（`e9c90d9`）上重跑 `./mill server.bench`，10,000 条写入 4,864.81 ms，p50 421.88 µs、p95 636.75 µs，折算约 2,055 条/秒（纯 store、夹具预生成，不含加密与网络）。
- E2E 每条约 2.99 ms（10k 两进程 28,958 ms / 10,000），store 单条写入均值约 0.49 ms、p50 0.42 ms，只占其中一个零头。剩下的时间在加密、relay 往返、对端落盘、ACK 与双侧状态推进。
- 结论：把「一条一个事务」当成 335 msg/s 的主因不成立；批量提交能压掉的是每条约 0.4–0.5 ms 里的提交开销，不是其余约 2.5 ms。

### R5、R6 事件驱动与 UI 剥离

- UI 侧没有网络轮询：主界面订阅 `ws://<host>/api/events`（`ui/src/yochat/ui/ApiClient.scala:193`），收到 `state` 消息才刷新；代码里只有两个定时器，一个是断线重连退避 750 ms（`ApiClient.scala:208`），一个是提示条 4.2 秒后自动消失（`ui/src/yochat/ui/UiState.scala:29`），都不涉及取网络数据。
- 服务端在落盘完成后推送：`AppRuntime.start` 的 `onState` 回调挂在 `hub.publish("state")`（`server/src/yochat/server/AppService.scala:291`），经 WebSocket 发给所有订阅者。
- 与 D 的要求相比有一处只对了一半：UI 收到 `state` 后走 `loadSession()`，即 `GET /api/session` 再 `loadMessages`，全量重取会话与当前会话的消息列表（`ApiClient.scala:36-58`），不是接收增量。落盘一次就触发一次全量拉取，在高吞吐下会放大开销。`PERF#RATE` 不含 UI，所以 335 msg/s 这个数字看不到这部分。
- 若要按 D 的要求做到底，改动点是把推送载荷从「一个 state 事件」升级为带回变更范围的通知，UI 按需增量取；这是 UI 与协议层的改动，不是存储改动。

### R7 状态缓存

- 运行态在内存：`AppState` 含 `outgoing`（待发与在途消息）与 `connection`（连接状态），入站处理直接读内存（`core/src/yochat/core/app/AppTypes.scala:257`）。
- 有一处仍是读-改-写：消息状态迁移（Sent / Delivered / Failed）先 `store.loadMessage` 再 `setMessageStateByKey` 带旧状态做 CAS（`server/src/yochat/server/AppEffectExecutor.scala:405-431`）。每条消息通常两次迁移（交给中继后 Sent、收到 ACK 后 Delivered），即两次按主键点查。
- 这与 D 说的「每条消息都从 db 查状态」不是一回事，但方向一致：可以改成内存里带版本号直接判迁移，省掉两次点查。改动会碰 ADR-0007 的并发契约（版本与 CAS 在库内完成），需要单独设计。
- 这两次点查的耗时没有单独测过；在一个 E2E 每条约 2.99 ms 的预算里占多少，目前只能说未知。

## 范围与待裁决

- 历史同步属于多设备，ADR-0003 把它排在 v1 之外，`PRODUCT.md` 的“Not in S1”也列了 history sync。R1 要变成门，先要决定是否把多设备历史同步纳入范围。
- R1 的 1,000 条/秒比现有 10k 门隐含的 167 条/秒严约 6 倍。新增门还是替换旧门，需要裁决；直接替换会让当前实现一次性落后 3 倍。
- R2 与 R5 是同一件事：按当前代码，接收侧已经是事件驱动，不成立；需要 D 指出他看到的定时器在哪个进程、哪段代码。
- R6 的推送链路已经存在，但载荷是全量重取的触发信号。要不要改成增量通知，取决于是否真的把 UI 放到 335 msg/s 以上的场景；这属于 S1 之外的范围。
- R7 的两次点查可以省，但会动 ADR-0007 的乐观并发实现；先测出它在每条 2.99 ms 里的占比，再决定是否值得改。
- R3 未测量，只作假设记录。本机两进程直连下的带宽占用确实不大，但这条没有独立计时证据。

## 未核实与待办

- `PERF#RATE` 与 `server.bench` 都是单机回环、测试夹具；不代表跨网、真实 UI 渲染或移动端。
- 335.4 msg/s 来自未合入 `main` 的分支；合入前不能算作 `main` 的现状。本文只在 `main` 上重跑了 store 基准，没有在 `main` 上重跑 E2E 吞吐。
- 待确认两项：D 说的「定时收取」指哪一段；历史同步是否进入 v1。
- 消息状态迁移的两次点查耗时未单独测量；R7 的改造收益目前只能定性描述。

## 来源

- 群消息：`133905`、`133906`、`133907`、`133908`、`133912`、`133913`、`133914`、`133915`、`133916`、`133949`、`133951`、`133952`、`133956`、`133972`、`133973`（`wechat_message.id`，本地时间见上表）。
- 吞吐回执：分支 `feat/s-throughput` 的 `docs/plans/2026/11/0008-s1-e2e-performance/impl.md`（`d56a99b`）；`docs/plans/2026/11/0010-s1-ui-automation/acceptance.md` 的状态更新。
- 本次复测：`./mill server.bench` 于 2026-11-25 在 m1 上运行，源码 `e9c90d9`。
- 代码位置：`transport/src/yochat/transport/RelayClient.scala`、`server/src/yochat/server/AppRuntime.scala`、`server/src/yochat/server/AppService.scala`、`server/src/yochat/server/AppEffectExecutor.scala`、`ui/src/yochat/ui/ApiClient.scala`、`ui/src/yochat/ui/UiState.scala`、`core/src/yochat/core/app/AppTypes.scala`、`store/src/yochat/store/Store.scala`、`store/src/yochat/store/MessageRepository.scala`。
