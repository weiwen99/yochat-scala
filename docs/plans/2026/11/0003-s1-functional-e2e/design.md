# Plan 0003: S1 functional behavior contracts

- 状态: draft
- 日期: 2026-11-23
- 前序: Plan 0001（M4 已完成）、Plan 0002（M3 结构加固已合入）、ADR-0003、ADR-0004、ADR-0006、ADR-0007、ADR-0008
- 基线: `8977ebd`
- 活规格: `.scratch/yochat-s1-functional-e2e/spec.md`
- 行为契约来源: `docs/agents/verification.md` §Behavior contracts
- Review 预算: 最多 3 轮；总预算 1–2 人日；第 3 轮问题数不下降就停（R9）

## 规模与交付边界

本计划只做 S1 功能行为契约的前七条的服务层流程，不做性能门。两进程是重启与断网重连所需的运行形态，不是压测目标；不加入 1k/10k 负载、并发压测或吞吐阈值。

**重要区分**：本计划的绿色回执证明「服务层流程跑通」，不证明 `E2E#1/#2` 的安全契约关闭。ADR-0006 §3.3 要求第一条经认证的业务消息携带双方渠道账号与安全码绑定断言；当前 `Ratchet.encryptUser` 只加密调用方给的正文，尚未实现该绑定。该前置缺口不在本计划内偷偷补，`E2E#1/#2`（以及 `E2E#3` 的安全含义）在绑定实现前保持 open。

| 维度 | 用户要的 | 本计划产出 | 倍率 | 说明 |
| --- | --- | --- | --- | --- |
| 需求条数 | 7 条功能行为契约（#1–#7） | 7 条可证伪 E2E gate | 1× | #8 吞吐与 10k 属性能，不混入 |
| 表数 | 0 个新表 | 0 | 1× | 复用现有 SQLite 表 |
| 组件数 | 0 个新生产组件 | 0 个生产组件；1 个 `server.e2e` 测试命令 | 1× | 复用 `server.test` classpath 与现有 Main/HTTP/relay |
| 配置项 | 0 个新用户配置 | 0 个用户配置 | 1× | 测试参数只存在于 harness |
| 工程师日 | 只要求服务层流程，不新增发布级能力 | 实现 3–5 + review 1–2（估算，共 4–7） | — | 不含 #8 与 10k；不是按契约条数换人日 |
| 发布波次 | 1 | 1 | 1× | 一个 worktree、一个可 ff-only 的批次 |
| 生产代码 | 只修 E2E 暴露的功能缺陷 | 预计 0–150 行 | <1.01× | 不新增协议路径、表、运行时 |
| 测试代码 | 每条契约有可重建回执 | 约 600–900 行 | — | 进程夹具与 HTTP 驱动占主要部分 |

结论：本计划是 M5 的功能入口，不是 S1 全量验收。`E2E#8`、10k 两进程、冷启动、空闲内存、`E2E#9–#15` 和 UI 自动化不在本计划内；它们不被本计划的绿色回执冒充。

## 状态与前序

Plan 0001 的 M4 UI/shell 与 Plan 0002 的 M3 结构加固已经合入 `main`。当前没有两进程功能 E2E：`AppRuntimeSpec` 是同 JVM、两个 Store、两个 Runtime、真实 TCP relay 的集成测试；`HttpApiSpec` 只启动一个服务实例。`server.bench` 是 store 基准，不是 E2E。

本计划消费 `docs/agents/verification.md` 的固定行为契约，不修改契约文字。实现只验证前七条；后续契约另立增量，不把本计划写成 15 条全绿。

## 目标

1. 建立一个可重复的环境：一个真实 TCP relay、两个独立 JVM 的 `yochat.server.Main`、两个独立临时数据目录、两个本地 HTTP 服务。
2. 通过真实 HTTP API 完成邀请、应答、联系人、安全码与第一条消息，并断言安全门前正文不落盘、不在双方消息列表显示；wire 级“未发送”在无 frame 计数时明确标为未证明。
3. 真实重启发送方与接收方后，已确认消息仍在，联系人和会话可恢复。
4. 真实断开并重启 relay 后，离线期间写入的消息在重连后送达。
5. 同一对设备发送 100 条消息，接收方按作者顺序显示 100 条且无重复。
6. 每个契约产生可重放的脱敏回执：契约编号、状态、计数/序号、耗时、版本；不把 passphrase、正文、联系人名或公钥写进回执。失败时控制台可给出脱敏摘要与临时目录路径。
7. 明确本计划不关闭 ADR-0006 §3.3 的首条业务消息绑定；`E2E#1/#2` 的契约关闭需要独立的绑定实现与回执。

## 非目标与已知约束

- 不做 `E2E#8` 1,000 条吞吐、10k 两进程、10k 协议回放、冷启动、空闲内存；这些仍是 Plan 0001 的性能门，另立增量或计划。
- 不做 `E2E#9–#15`：错误/过期邀请、篡改、日志内容、锁、重建、Gap 注入。它们需要额外 fault injection 或时钟 seam，不能塞进本计划的 happy path。
- 不驱动 Electron 或浏览器。本轮是**服务层功能 E2E**；UI 的显示与交互沿用 M4 手工验收。`/api/state` 与 `/api/conversations/{id}/messages` 是 UI 的权威数据源，但“服务层通过”不等于“UI 已自动化通过”。
- 不新增第二套 relay、第二套 store、第二套 runtime 或生产 fault flag。harness 只使用现有 `RelayServer`、`Main` 与 HTTP API。
- 不修改 Quint 模型、协议 wire、表结构；若实现暴露协议/模型问题，停止并单独一轮，不在本计划顺手改。
- 不保证跨设备事务；S1 只保证同一作者顺序（GBNV 仍不在 v1）。
- 外部密码学评审仍是发布门；本计划的 E2E 不替代它。
- ADR-0006 §3.3 的首条业务消息「双方渠道账号 + 安全码绑定」尚未实现。本计划不修改 wire，也不把 7 条绿色回执写成该安全契约关闭；实现它需要单独的设计/模型/向量轮次。
- S1 生命周期是一个本地实例；两个服务进程直接打开同一数据目录不受支持。`adoptLockGeneration` 检查恢复时的 generation，但本计划不新增跨进程文件锁或第二套单实例协调层。

## 设计

### 1. Harness 边界

新增 `server.e2e` Mill 命令，复用 `server.test.runClasspath()`，与 `server.bench` 同一入口模式：

```text
./mill server.e2e
./mill -D e2e.only=E2E#3 server.e2e   # 可选：只跑一条，便于失败复现
```

代码位于 `server/test/src/yochat/server/e2e/`。生产 `Main`、`AppService`、`HttpApi`、`RelayServer` 不增加测试模式、隐藏开关或第二套运行路径。

- relay：harness 进程内 `RelayServer.start(0)`。它是真实 TCP relay；`E2E#6` 关闭后用同一端口重启。
- 客户端进程：`ProcessBuilder` 启动 `java -cp <server.test.classpath> yochat.server.Main --port ... --data-dir ... --relay 127.0.0.1:<relayPort> --idle-timeout-minutes 60`。classpath 来自当前 harness 的 `java.class.path`，不手工拼依赖。
- 数据：`Files.createTempDirectory` 各一份；口令每个场景固定；所有临时目录与进程在 `finally` 清理。
- 端口：`ServerSocket(0)` 取得候选端口后关闭；子进程必须自己打印 `YoChat web: http://127.0.0.1:<port>` 且进程仍存活，才认为端口归它所有。若进程启动时绑定失败，输出脱敏摘要并失败，不静默重试到另一个服务状态。
- 就绪：先等子进程自己的 bind 行，再轮询 `GET /api/session` 直到 HTTP 200，最多 20s；不以固定 sleep 代替就绪。重启后必须先观察到 `unlocked=false, instanceExists=true`，解锁后再核对 identity 与重启前一致，并等 relay `online=true`。
- 关闭：正常路径先 `POST /api/lock`，再 `destroy`，等待 10s，超时 `destroyForcibly`；断言进程退出。
- 日志：每个子进程 stdout/stderr 落到临时文件；控制台失败摘要只输出计数、序号、状态码、fatal code 和短 id，不输出正文、账号、公钥或口令。完整脱敏回执写到 `tmp/checks/<timestamp>/`，不依赖临时目录继续存在。

### 2. 场景夹具与契约

每个契约使用独立的 `PairFixture`，不共享上一个场景的会话状态：

```text
relay + service A + service B
unlock A, unlock B
identity A/B
create invitation A -> receive invitation B
accept invitation B -> A receives reply
read contact/conversation from each side
```

`E2E#1` 邀请与应答：

- A 创建邀请，B 收到；B 接受后 A 的 `/api/state` 出现 B 的联系人，B 的 `/api/state` 出现 A 的联系人。
- 断言 A=`pending`、B=`confirmed`、双方 `safetyVerified=false`；用各自 userPub/peer userPub 重新派生安全码，核对每侧显示的是**本方应念段**（20/19 位分段），不把两侧字符串互相比较。
- 在同一 fixture 做一次安全门前发送负例：HTTP 200 不算成功，双方消息列表不得出现该正文；wire 级是否发出在无 frame 计数时明确标为未证明。
- 不要求两侧 conversation id 相同（它们在本机生成）。

`E2E#2` 双方显示联系人：

- 双方 contacts 非空，`displayName`/`channelAccount` 来自既有 API；会话存在且 `peerUserPub` 对得上。
- 在同一 fixture 重复安全门前负例；不新增联系人屏幕或 UI 断言。

`E2E#3` 一条消息与安全门：

- 安全门前置：在双方 `safetyVerified=false` 时，A 调 `POST /api/conversations/{id}/messages`；HTTP 可能返回 200（当前 `sendMessage` 只入队），因此**不能把 200 当成功**。等待一个短有界窗口后断言双方 `GET .../messages` 都没有正文、A 的 outgoing 未产生可显示消息。
- 调双方 `POST /api/contacts/{userPub}/safety` 验证安全码；`/safety` 也只是入队，必须轮询 `safetyVerified=true`，不能只看 HTTP 200。
- A 发送一条消息；B 的消息列表出现一次，作者为 `peer`、`seq=1`；A 的消息列表出现一次，作者为 `me`；A 的 state 最终为 `delivered`，B 为 `received`。
- 不使用 `Sent` 冒充 `Delivered`；以 API 的 `state` 字段为准。

`E2E#4` 重启发送方：

- 先确保 A 的消息为 `delivered`；`POST /api/lock`，停止 A，启动同数据目录/端口的新 A 进程；启动时断言 `instanceExists=true` 且仍锁着，解锁后 identity 与重启前一致，relay 在线。
- A 的 contacts、conversation、message 仍在；B 仍能看到同一条消息；A 发送**下一条 seq=2**并送达，不能拿旧的 seq=1 行冒充续发。
- B 的 relay 连接在 A 重启期间可能短暂离线，不要求 B 重启。

`E2E#5` 重启接收方：

- 同 #4 对 B 执行；B 解锁后消息仍在，A 的 seq=2 发送可在 B 重启后继续送达。

`E2E#6` 断网重连：

- 关闭 harness 的 relay；轮询双方 `/api/state` 的 `relay.online=false`。
- A 发送一条消息；断言 A 的消息已落盘且状态为 `pending`，B 尚未收到；不能把 `sent` 当离线预期。
- 用同一端口重启 relay；轮询双方 `online=true`；轮询 B 收到该消息一次、A 最终 `delivered`。
- 不以停止 A/B 进程代替 relay 断网；那是重启，不是重连。

`E2E#7` 100 条有序无重复：

- 在干净 pair 上验证安全码后，A 顺序发送 100 条正文（例如 `m-001`…`m-100`）。
- B 的 `GET .../messages?limit=200` 恰好 100 条，直接按返回数组比较 `(author=peer, seq=1..100, body)`；不排序后再断言，避免隐藏显示顺序回归。
- A 的 100 条最终为 `delivered`；不统计吞吐、不设 `<5s` 阈值，不跑 1k/10k。
- 发送是顺序 HTTP 调用，不做并发压测；这是功能契约，不是 #8。

### 3. 断言与失败处理

- 每个契约返回 `Pass(contract, evidence)` 或 `Fail(contract, message, evidence)`；进程退出码非零当且仅当存在 Fail。
- 所有等待都有上限：HTTP 请求 10s、状态轮询 20s、重启 20s、进程退出 10s。没有无限等待。
- `GET /api/session` 是启动/锁定状态的唯一 bootstrap；`/api/state` 用于联系人与 relay 在线状态；消息列表用于正文与状态。
- 失败时控制台只输出脱敏摘要：契约编号、计数、序号、状态码、fatal code、短 id、临时目录路径；不输出正文、账号、公钥、口令或完整 state。
- 每个契约结束把脱敏行写入 `tmp/checks/<timestamp>/s1-e2e.log`；回执在 fixture 清理后仍可读，至少包含命令、时间、契约、verdict、耗时、计数/序号。
- 不把超时、进程启动失败、HTTP 500、缺少字段写成“未覆盖”或“通过”。

### 4. 与现有测试的关系

- `AppRuntimeSpec` 继续作为同 JVM 集成层；本计划不删除、不放宽它。
- `HttpApiSpec` 继续覆盖单服务 unlock/invite/lock；本计划不把它包装成两进程。
- `server.bench` 继续作为 store 基准；本计划不使用它的数字。
- `E2E#1–#7` 与 Plan 0001 的 `test-parity.md` 映射一致；本计划只增加 E2E 证据，不改变 T1 分类。

### 5. 事务与锁序

本计划不新增表。E2E 暴露了一个真实缺陷：lock 只推进 `meta.lock-generation`，已有 session 行仍带旧 generation，解锁后第一次写入会被 `StaleGeneration` 拒绝。修复在现有 `SessionRepository` 内新增一次 `adoptLockGeneration(expectedLockGeneration)` 事务：先核对 DB 当前 generation 等于恢复时的 generation，再只触碰 `sessions` 和它们各自的 `meta` 高水位，顺序 `sessions → meta`；不新增全局锁序、不新增旁路写入、不改其他事务。两个服务进程直接打开同一数据目录仍不受支持。

其余事务保持现有 operation-specific 顺序，不在本计划重排。任何需要跨表重排或新增事务的缺陷，停止并单独设计轮。

### 6. 实施顺序

1. 先做 harness 与 `E2E#1/#2/#3`；这三条共享一次完整握手，先证明服务边界。
2. 修 E2E 暴露的 lock-generation 采纳缺陷；在 `SessionRepository` 内完成，禁止改协议或表。
3. 加进程重启与 `E2E#4/#5`；重启后仍使用同一数据目录与口令，并核对 identity/seq。
4. 加 relay 断网/重启与 `E2E#6`；确认离线写入状态与重连补发。
5. 加 `E2E#7`；只验证顺序/去重，不测吞吐。
6. 全部通过后，再决定是否把 `server.e2e` 加入 `./check.sh`；本计划先保持独立命令，避免把进程启动开销混入当前 check 时长。最终 S1 仍需要它进入可重复门。

## 测试规格等价

- 行为契约编号固定，不因重构改。每条 E2E 都断言用户可观察的 HTTP 投影，不断言私有实现细节。
- `E2E#1/#2/#3` 的安全门断言是新增证据，不替代 core 的 `RelationshipSpec` 或 ADR-0006 的加密测试。
- `E2E#4/#5` 使用真实进程重启；不以 `AppStateRestore.load` 的单元测试冒充。
- `E2E#6` 使用真实 relay 关闭/重启；不以 `AppRuntimeSpec` 的同 JVM relay 重启冒充。
- `E2E#7` 只证明功能顺序/去重；`E2E#8` 的 1k 门仍开放。

## 性能与预算

- 不设置 E2E 吞吐阈值；`server.e2e` 的目标是可在开发机重复运行，不是性能回执。
- 进程启动与 100 条顺序发送可能耗时数十秒；该耗时只记录，不当作 `<5s` 门。
- 生产代码预算：不增加新文件类别；若修 bug 超过 150 行，停止并重新评估范围。
- 测试代码预算：≤900 行；超过说明 harness 在复制生产逻辑，先砍。

## 取代的旧文档

**none（有理由）**：本计划消费 `docs/agents/verification.md` 与 Plan 0001 的 M5 范围，不取代任何旧文档。完成后在 Plan 0001 `impl.md` 增加指向本计划实现的 M5 增量记录，不重写历史。

## Review 预算与轮次

总预算 3 轮，不是每条契约 3 轮。每轮先冻结对象（R8），审完才改。

| 轮 | 对象 | 通过条件 | 失败/停止 |
| --- | --- | --- | --- |
| 1 | 本设计 + 现有 `AppRuntimeSpec`/`HttpApiSpec` | 边界、进程生命周期、清理、7 条 gate 均有可证伪证据 | 修正后进第 2 轮 |
| 2 | harness 设计 + 实现 diff（冻结） | 没有第二套 relay/存储/运行时；无无限等待；失败可复现 | 修正后进第 3 轮 |
| 3 | 最终实现 + 回执 + 增补记录 | 7 条契约绿；`./check.sh` 绿；开放项明确 | 第 3 轮问题数不降即停（R9） |

## 验收

1. `./mill server.e2e` 输出 `E2E#1`–`E2E#7` 全部 PASS；每条都有 `tmp/checks/<timestamp>/s1-e2e.log` 脱敏回执；这是**服务层流程**回执，不是 `E2E#1/#2` 安全契约关闭。
2. 安全门：验证前没有正文进入双方消息列表；验证后一条消息双方各显示一次，发送方最终 `delivered`。wire 级“未发送”未由本计划证明；`E2E#1/#2/#3` 的首条业务消息绑定仍 open。
3. 发送方、接收方各自重启后，消息与联系人仍在；重启后以 `seq=2` 续发并送达，证明不是旧行冒充。
4. relay 断开期间写入的消息状态为 `pending`，relay 重连后送达，且只在接收方显示一次。
5. 100 条消息按返回数组顺序显示 1..100，无重复。
6. `./check.sh` 绿；没有修改 Quint 模型、协议 wire、表结构或生产 fault flag；lock-generation 修复只触碰 sessions/meta。
7. 未关闭项（#8、10k、#9–#15、UI 自动化、ADR-0006 §3.3 绑定）明确写到 `impl.md`，不写成“已覆盖”。
