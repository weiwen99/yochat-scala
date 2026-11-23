# Design review findings — Plan 0003

- 对象: `design.md` 初稿 + 基线 `8977ebd` + `docs/agents/verification.md`
- Review 预算: 最多 3 轮；第 3 轮问题数不下降即停（R9）
- 当前状态: round 0（基线），尚无正式 review 轮

## Round 0 基线

本计划只关闭 S1 功能行为契约 #1–#7，明确不碰性能与 #9–#15。下表登记 gate 与证据；实现记录必须逐项引用。

| ID | 原句位置 | 规则 | 问题与影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- |
| E1 | `verification.md` §Behavior contracts | Y5、R2 | 没有两进程功能 E2E；`AppRuntimeSpec` 同 JVM、`HttpApiSpec` 单服务 | `server.e2e` 启动 relay + 两个真实 Main；失败可复现 | 1–1.5d |
| E2 | `verification.md` #1/#2 | Y5 | 邀请/应答/联系人只有同 JVM 集成证据 | 真实 HTTP 完成邀请/应答，双方 contacts 与 conversation 可见 | 0.5d |
| E3 | `verification.md` #3 + safety gate | ADR-0006、Y5 | 安全门前正文门没有 E2E 证据；HTTP 200 可能被误当成功 | 验证前无正文；验证后一条消息双方各一次，A `delivered` | 0.5d |
| E4 | `verification.md` #4/#5 | Y5、工程规范 | 重启只有 `AppStateRestore.load`/同 JVM 证据 | 锁、停进程、同数据目录重启、解锁；消息仍在且可续发 | 0.5–1d |
| E5 | `verification.md` #6 | Y5 | 只有同 JVM relay 重启；不是真实服务重连 | 关闭/同端口重启 relay；离线写入后送达 | 0.5–1d |
| E6 | `verification.md` #7 | Y5 | 100 条顺序/去重没有 E2E | 100 条顺序 1..100、无重复、全部 `delivered` | 0.5d |
| E7 | `verification.md` §Test integrity | 测试真实性 | 失败若只给一个 assert，无法复现 | 输出契约编号、双方状态投影、HTTP 交换、日志尾部、临时目录 | 0.25d |

## 轮次记录

- Round 0: 基线七项，全部 `open`。未进行正式 review；不得把本表当作通过。
- Round 1: 对 `design.md` / `spec.md` 做独立 Standards + Spec review；7 条发现（1 BLOCKER、5 MAJOR、1 MINOR）。详见下表；实现与设计按关闭证据修改后进入 Round 2。
- Round 2–3: 待实现 diff 冻结后追加；每轮记录对象、两轴覆盖、发现、关闭/撤回、升级。
- 规则: review 前冻结 diff（R8）；发现必须带原文、规则、影响、关闭证据、代价（R5/R6）。

## Round 1 findings

| ID | 严重度 | 原句与位置 | 规则 | 问题与影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- | --- |
| F1 | BLOCKER | `design.md` 原验收第 1 条“`E2E#1`–`E2E#7` 全部 PASS”与“不修改协议 wire” | ADR-0006 §3.3；Plan 0001 `impl.md` §未关闭 | 首条业务消息尚未携带双方渠道账号/安全码绑定；7 条绿色 HTTP 回执不能关闭 `#1/#2` | 设计已改成“服务层流程回执”；`#1/#2/#3` 的绑定契约保持 open；实现不碰 wire | 文档 0.1 人日；协议实现另计 |
| F2 | MAJOR | `design.md` §2 `E2E#3` 安全门前置 | `verification.md` 安全门；Plan 0001 `test-parity.md` | 只断言消息列表为空，不能证明没有 DATA 帧；`/safety` 也只入队 | 在 #1/#2/#3 每个 fixture 加负例；轮询 `safetyVerified`；核对双方各自派生安全码与 `pending/confirmed`；wire 未观测处明确标为未证明 | 0.5–1 人日 |
| F3 | MAJOR | `design.md` §1 端口/就绪 | R5、Y5；契约 #4/#5 | 端口释放到 bind 之间有竞态；HTTP 200 不证明响应者是本次子进程 | 子进程必须打印自己的 bind 行且存活；重启后先断言 `instanceExists=true`、仍锁定；解锁后 identity 相同并等 relay 在线 | 0.2–0.4 人日 |
| F4 | MAJOR | `design.md` §2 #4–#6 夹具 | Y5、ADR-0008 | 新 pair 未验证安全码就发送；离线消息不能合法是 `sent` | #4/#5/#6 都先验证安全码并等双向在线；#6 断言 A 行为 `pending`、B 未收到；#4/#5 用 seq=2 续发 | 0.1–0.2 人日 |
| F5 | MAJOR | `design.md` §2 #7 “按 `(author=device, seq)`” | R5、Y5；`ApiJson` | 公共 API 只返回 `author=me/peer`，没有 device；按 device 断言不可执行 | 直接比较返回数组的 `(author=peer, seq=1..100, body)`，不排序后断言 | 0.1 人日 |
| F6 | MAJOR | `design.md` §1/§3 临时目录与日志 | `verification.md` 回执纪律；ADR-0003 诊断边界 | `finally` 删除临时目录，回执路径消失；完整 state/日志可能带正文/公钥/口令 | `tmp/checks/<timestamp>/s1-e2e.log` 保留脱敏回执；控制台失败摘要只含计数/序号/状态/短 id；完整临时日志不写入回执 | 0.2–0.4 人日 |
| F7 | MINOR | `design.md` §5 “现有全局锁序” | D7/D2 | 本计划没有新事务时却另写了一个全局顺序，和现有 operation-specific 描述冲突 | 改为不新增事务；实际修复 `adoptLockGeneration` 只触碰 `sessions → meta`，其他事务不重排 | 0.05 人日 |

Round 1 结论：F1 是“不能宣称契约关闭”的范围修正，不是让 harness 停建；F2–F6 在实现中已按上表关闭；F7 改文档。Round 2 需冻结实现 diff，核对 `adoptLockGeneration` 只触碰 sessions/meta、E2E 回执脱敏、没有协议/表变更。

**Severity correction（Round 2）**：F1 从 BLOCKER 降为 MAJOR。S1 生命周期是单实例；并发直接打开同一数据目录不是支持路径。该修正不改变“`E2E#1/#2` 绑定契约仍 open”的结论，只改变实现阻塞级别。

## Round 2 findings（冻结实现 diff）

Round 2 对象：`build.mill`、`server/test/.../e2e`、`SessionRepository.adoptLockGeneration`、`AppRuntime` 调用点、`SmokeSpec` 回归、`impl.md` 回执。结论：无 BLOCKER；`adoptLockGeneration` 的事务边界/AAD/version 高水位与 `sessions → meta` 顺序正确，剩余问题是调用方 fence、harness 证据和回执口径。

| ID | 严重度 | 原句与位置 | 规则 | 问题与影响 | 关闭证据 | 代价 |
| --- | --- | --- | --- | --- | --- | --- |
| F1 | MAJOR | `AppRuntime.scala` restore 后调用 `adoptLockGeneration`；`SmokeSpec` 只测采纳前的 stale | ADR-0007 §5；单实例生命周期 | 两个同时打开同一目录的进程可让旧 runtime 借用新 generation/version 写通；S1 不支持这种并发 | `adoptLockGeneration(expected)` 检查恢复时的 generation；回归覆盖错误预期、采纳后旧 generation、旧 version、状态未变、fresh 写入成功；并发直接打开同一目录记为 unsupported | 0.1–0.25 人日 |
| F2 | MAJOR | `S1E2E.scala` 安全门和 #6 的 500ms sleep | Y5/R5；安全门 | sleep 既不能证明发送事件已处理，也可能误杀慢的离线提交 | 安全门后追加一个会等待持久化完成的同 FIFO 命令（本地邀请），再读历史；#6 轮询 A 的 `seq=2/me/pending` 行，不 sleep | 0.2 人日 |
| F3 | MAJOR | `E2ESupport.scala` `getAndSet(None)` 早于 lock | 设计 §进程生命周期 | lock 失败时丢失子进程句柄，清理可能留下 JVM/端口 | `stop` 保留局部 handle，lock 失败也 `terminate`；`Pair.close` 对两个 JVM 和 relay 分别兜底 | 0.1–0.2 人日 |
| F4 | MAJOR | `S1E2E.scala` 打印异常 message；`E2ESupport` 失败含原始 body/log | 设计回执/诊断边界 | 失败路径可能泄露账号、正文、公钥；receipt 只有异常类，难以定位 | 控制台/receipt 只输出契约、异常类、S1E2E 栈位置；receipt 含 HEAD/Java/耗时/计数；原始日志仅在临时目录，不写入回执 | 0.2–0.4 人日 |
| F5 | MAJOR | `S1E2E.scala` 校验两侧 conversation id 不相等，重启后复用缓存 id | 设计 #2/#4/#5 | 本地 id 不相等不是契约；重启后必须从公开 state 重新解析路由，且 user+device identity 都要一致 | 删除 id 不等断言；重启后 user+device 校验；从各自 public state 重新取 conversation；旧/新消息完整历史各一次 | 0.2 人日 |
| F6 | MAJOR | `S1E2E.scala` #7 过滤后再计数 | 设计 #7 | 额外错误行会被过滤掉而仍报 100 | 对 B/A 的完整返回数组做精确比较（作者/seq/state/body）；多一行即失败 | 0.1 人日 |
| F7 | MINOR | `impl.md` 写 `core.test 145`；receipt 无输入指纹 | R5/回执纪律 | 与机器报告 69 个测试不一致；无法确认审的是哪一版输入 | 改为 69；receipt 记录 `git rev-parse HEAD`；说明 `server.checkFormat` 只覆盖主源码，E2E 测试源码仅由 `-Werror` 编译兜底 | 0.1 人日 |

Round 2 结论：F1–F7 已按上表关闭；无剩余 BLOCKER/MAJOR。并发直接打开同一数据目录记为 S1 已知不支持路径，不引入文件锁或新组件；若以后支持，需要单独设计。
