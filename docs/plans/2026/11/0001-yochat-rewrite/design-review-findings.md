# Plan 0001 设计评审（第 1 轮）

- 状态: round 1 / 最多 7 轮
- 日期: 2026-11-20
- 对象: `docs/plans/2026/11/0001-yochat-rewrite/design.md`、`docs/adr/0001`–`0004`
- 基线: commit `ee298027c4da4f65e81c1872f7f0fdc4ac319b6a`；工作树 `plan-0001-a-first-spike`，分支 `feat/plan-0001-a-first-spike`
- 结论: 7 条发现（6 BLOCKER / 1 SUGGESTION）。本轮没有关闭项；A-first spike 前至少 F1–F6 需要关闭或由用户明确裁决。F1–F7 关闭成本合计约 6.4–9.9 人日（不含“把 #12 加进 S1”的翻倍成本）。

## 1. 对象与冻结

| 文件 | SHA-256 |
| --- | --- |
| `docs/plans/2026/11/0001-yochat-rewrite/design.md` | `0976cd679a7d0b2f8030437e0dd020a3d17ca86715cdb53632f2103f6b576d44` |
| `docs/adr/0001-import-cfa-and-0x1-conventions.md` | `c8cc37b14b7844a5dd60a61aa87607aad69977d279a495242a4b3be7e6eff47d` |
| `docs/adr/0002-technology-stack.md` | `6f3ba225681b64265ba025fc28cf9e42b62f3827ed47e000643a517298d89a83` |
| `docs/adr/0003-v1-scope.md` | `8c0b0233f79d9021c2d29613a95cce40539487ccf69559d11f2ac6dd1e1c03cd` |
| `docs/adr/0004-test-parity-and-performance-gates.md` | `6245ae86c6f4329b6a97236b1099f09331d306ae41ccc721dea5cfbff684c5be` |

评审期间未改设计/ADR。工作树里 `build.mill` 与 `spike/` 是未跟踪的并行工作，不在本轮对象内；本轮未运行 build、lint、test 或性能测量。

方法: `design-scope-discipline` SKILL（`eb1ebfe94a8d094aeed40658b461360a01647a774138530fd0be1217ff35c927`）、`docs/agents/review-quality.md`、`de-ai-flavor` SKILL（`253f5c215797e06c62816f82f0140e4c29b7f2c0d891998f04ebf621d3773491`）与 `references/argument-review.md`（`c570613e633a04c4b1217e947c4ac161b86af41d407ffcd07c11e442531517fe`），均于 2026-11-20 读取。

## 2. 两轴覆盖

### Standards

已读: `AGENTS.md`；`DESIGN-DISCIPLINE.md`；`docs/agents/review-quality.md`；`docs/agents/verification.md`；`docs/agents/engineering.md`；ADR-0001–0004；plan design 全文。

覆盖: D1–D10、Y1–Y8、R1–R10 的设计与评审规则；A-first/B、T1、性能门、膨胀预算、评审字段与记录位置。

缺口: 未核对 CFA/0x1 外部源仓（`~/projects/comp-fert-ai`、`~/projects/project-0x1`）的原始规则文本；ADR-0001 对它们的引用按二手来源处理。未评审未跟踪的 `spike/` 代码。

### Spec

已读: `docs/references/2026-11-20-yochat-reference-study/requirements.md` 的 S1 40 条、S2/S3 分流与第 4–6 节；`survey.md`；`README.md`；`docs/spec/product/` 的 `messaging.md`（S1 相关段落）、`contacts.md`（邀请/应答需求）、`users-and-devices.md`（加密/应用锁需求）、`privacy-and-safety.md`（中转/诊断需求）；`docs/spec/engineering/decisions/encryption-scheme.md`；`docs/plans/2026/11/0001-yochat-rewrite/test-parity.md`（并行任务的未跟踪 WIP，仅用于 T1 现状核对，不作为本轮评审对象）。

覆盖: 用户固定的 v1 = S1、A-first、relay-only、T1、2,000 行覆盖、最后一次整体重写；S1 40 条逐条对照；GBNV 的显式分流要求。

缺口: 没有逐行读完 `messaging.md`（1005 行）与 `contacts.md`（801 行）的全部用例；S2/S3 的全文只读到与 S1 边界相关的部分。旧实现代码只通过 reference study 与 `docs/spec/` 间接核对，没有直接读 `~/projects/yochat-reference`。

### 一致性矩阵（问题 3）

| 规则 | AGENTS | DESIGN-DISCIPLINE | ADRs | Plan | 判定 |
| --- | --- | --- | --- | --- | --- |
| A-first | §3 | —（由 ADR/plan 承接） | ADR-0002:26–36 | design:162–172 | 规则层一致；spike 证据层见 F5 |
| relay-only | §4 `直连失败走中转` | Y2 同句 | ADR-0002:25、ADR-0003:30 | design:58、83 | **冲突**，见 F4 |
| T1 | §6 | Y5 | ADR-0004:13–19 | design:141–150 | 规则层一致；Quint 数量见 F6 |
| 2,000 行覆盖 | §5 | Y3 | ADR-0001:38、ADR-0004:30 | design:51、206 | 一致；无 finding |
| 最后一次整体重写 | §1 | Y7 | ADR-0001:41 | design:28 | 一致；无 finding |

## 3. 论证审读

覆盖: `design.md` 1–207 行；ADR-0001 1–47；ADR-0002 1–51；ADR-0003 1–53；ADR-0004 1–50。全部读完。

结论: 没有发现“自设靶子”或“无依据的防误解提醒”。比较/否定段落都能指回真实来源或真实条件：

- design:24 “不要用旧项目 93 条需求论证本计划需要更多”对应 `requirements.md:284` 与复盘的范围放大结论；
- design:172 “spike 产物不是调研结论”对应复盘里“AI 自述当验收”的失败；
- design:123 “A-first 不能直接用 p2panda 群棘轮”是技术事实（A 是 JVM，旧库是 Rust）；
- ADR-0001:38 的 2,000 行覆盖有用户裁决；
- ADR-0002:36 的“不得以 Rust 库更成熟为由直接切 B”是对 A-first 用户决定的执行规则，不是虚构对手。

ADR-0003:41 的删除判据不是论证框架问题，而是规则过宽；归入 F1。F5 里的“若风险不可接受”是没有阈值的 gate，不是自设靶子；归入 F5。

格式: 不适用（review mode，不做密度/基线测量）。保真: 不适用（没有改写）。人工通读: 未做。

## 4. Findings

本轮所有 finding 的 disposition 为 open；下列“关闭证据”是下一轮必须出现的 diff/新句，“noted”不算关闭。

### F1 [BLOCKER] ADR-0003 的删除判据可以删掉 S1 安全不变量

- **原句与位置**: `docs/adr/0003-v1-scope.md:41`“一条需求如果删掉之后，明天仍然能发一条消息，就删或推迟。”
- **规则**: `docs/references/2026-11-20-yochat-reference-study/requirements.md:87`“若要把 S1 再缩小，只能缩小验收场景……不能删掉安全不变量”；`AGENTS.md` §4“安全契约保留且实现极简”；`DESIGN-DISCIPLINE.md` D8。
- **问题与影响**: 该判据按“能否发消息”筛需求。应用锁、空闲自动锁定、密钥不进日志、诊断不含内容都可以被删掉而仍然能发消息；它们因此可被这条判据合法地移出 S1。F2 的缺失与这条判据一致；它使这些缺失看起来是合法的：设计保留了“三块功能”，丢掉了 S1 的跨切面安全不变量。S1 的定义不是“功能少”，而是“只有一条路径 + 不删安全不变量”；这条判据把前者执行了，把后者删掉了。
- **关闭证据**: ADR-0003 的判据改成“删功能，不删 S1 安全不变量”，并附不可删清单（至少 B9Y4E73VMQ8RS99X、YQW27DXZJSHAR0Z0、865PCSHV46Q4X2XF、DVFN7QEAWP32QD9G）；或者把该判据限定为“功能域”，并显式声明安全不变量不受此判据约束。`design.md:53–61` 的“非目标与已知约束”同步引用该清单。
- **代价**: 0.2 人日文档；0 表；0 组件。

### F2 [BLOCKER] S1 范围与 40 条清单/15 条契约双向不一致

- **原句与位置**: `design.md:46–51`（目标）、`:53–61`（非目标与已知约束）、`:109–119`（协议路径）、`:147`（15 个 E2E 场景）、`:135`（UI）、`:91`（State）；对照 `requirements.md:40–85` 的 S1 40 条表与 `docs/agents/verification.md:36–50` 的 15 条行为契约。
- **规则**: reference study 的 S1 40 条；ADR-0003 的 S1 决策；`AGENTS.md` §4；`docs/agents/verification.md` 的行为契约清单。
- **问题与影响**: 逐条对照后，下列 S1 需求在 design/ADR/plan 中没有完整设计落点，也不在“非目标与已知约束”里。它们不是“S2/S3 的已知延后”，而是被静默遗漏。并行的 `test-parity.md` 清单已经把这些需求映射到测试，但测试计划不是设计：`State`、`Event`、`Effect` 与 UI 仍没有对应元素可测。

  | S1 需求 | 设计现状 | 缺口 |
  | --- | --- | --- |
  | `REQ_20260918_B9Y4E73VMQ8RS99X` 应用锁是安全边界 | `design:135` 只有“解锁/身份”屏 | State（`:91`）没有锁状态/代数；没有“锁定即失效、在途结果丢弃”的规则；E2E 场景 #13 有测试名，没有设计可测 |
  | `REQ_20260918_YQW27DXZJSHAR0Z0` 空闲超时自动锁定 | 无 | 没有空闲定时器、默认开启、配置项或 `Event` |
  | `REQ_20260918_865PCSHV46Q4X2XF` 密钥材料不进日志/崩溃报告 | 无 | `Effect`（`:93`）没有日志策略；行为契约清单没有 log-secrets 场景 |
  | `REQ_20260918_DVFN7QEAWP32QD9G` 诊断默认不记录内容 | 无 | 没有默认日志级别/内容边界 |
  | `REQ_20260918_2276A5JCK1KGNS1A` 邀请由用户私钥签名并绑定发起方渠道账号 | `design:111` 只写“渠道身份” | 没有定向邀请的接收方权威账号；reference study 说 S1 只做定向邀请，但设计没有写“只做定向” |
  | `REQ_20260918_YP7HYFDC5BSKK1BP` 邀请/应答绑定渠道上的权威账号 | `design:111–112` 没有账号比对 | 没有双方各自声明账号、归一化、比对与不符标记；`contacts` 表（`:101`）也没有双方各自记录渠道/账号的列 |
  | `REQ_20260918_A1MZYZ7JV43G7EJ3` 应答方自发出应答起即完成本方确认 | `design:113`“双方核对安全码……双方各自落盘联系人” | 把双方都推迟到 A 解开应答之后；没有 B 的待确认状态、A 的第一条经认证操作、有效期+宽限、过期恢复提示 |
  | `REQ_20260918_KWW858WDBR49C9KS` 拒绝一份应答不产生信号 | 无 | 没有拒绝路径/静默规则 |
  | `REQ_20260918_HYREM42TB44CYAHF` 无匹配记录的入站一律丢弃 | `design:114` 只写解密、验签、去重、排序 | 没有“无匹配联系人/邀请 → 丢弃”的准入规则 |
  | `REQ_20260918_RYGFPP529TDA9R6X` 不与未建立关系的对端交换消息 | 无 | 同上，没有关系门 |
  | `REQ_20260918_MXHYRG96W0VKB031` 对未授权请求不产生响应 | 无 | 没有应用层静默规则 |

  反向不一致：`docs/agents/verification.md:47` 的 15 条行为契约包含 #12“contact removal / blocked sender behavior”，但 S1 40 条里没有联系人删除/拉黑（它们在 S3：`REQ_20260918_ZWMFKB381M0AKCYN`、`REQ_20260918_F7ZBKSBAC1N4KM2K`、`REQ_20260918_SD1XKGXS4DBG5ZTE`），ADR-0003 的非目标也没有列它。`design:147` 让 S1 的 E2E 直接引用 15 条契约，`design:206` 又要求 `test-parity.md` 无未签字省略。结果是 S1 门禁包含一个 S1 范围外的功能：要么从 S1 门禁删掉 #12，要么把它加进 S1 并补成本。并行 `test-parity.md` 也把这条列为“需人裁决”。

  另外，`design:15` 的规模表用“功能域 3”替代了 D1 的“需求条数”，所以 1× 倍率没有覆盖这 40 条不变量；这让缺口在规模表里不可见。`design:119`“S1 只有一台设备”也与 `design:202`“两台设备（各一个用户）”和邀请/应答的双边结构不一致；应写成“每个用户各一台设备”。
- **关闭证据**: `design.md` 增加一张 40 行（或至少上述缺口行）的 traceability 表，列为“需求 / 设计段落 / 测试场景 / 已知约束”；对每一条补齐设计元素或写入“非目标与已知约束”并取得用户签字。A1M 的关闭句至少是：“B 在发出应答时完成本方确认并进入待确认；A 在第一条经认证操作后确认；双方在邀请有效期+宽限后降级并显示重新交换提示。”B9Y/YQW 的关闭句至少是：“State 增加锁代数与锁定状态；锁定事件递增代数并丢弃该代数之前的在途结果；空闲定时器触发同一锁定路径。”HYR/RYG/MXH 的关闭句至少是：“入站先按联系人与邀请匹配；无匹配记录或未授权请求不落盘、不回应用层响应。”2276/YP7 的关闭句至少是：“邀请携带发起方渠道标签+权威账号；定向邀请另带接收方账号；应答携带应答方渠道标签+账号；双方收到后归一化比对，不符进待确认并标记；`contacts` 记录本次对应的渠道/账号，不发送给对方。”KWW 的关闭句至少是：“拒绝应答只删本地待确认项，不产生 Send/Notify 效果。”865/DVF 的关闭句至少是：“默认日志级别不含正文、联系人名称、用户公钥或密钥材料；崩溃报告同样过滤；提高日志级别的入口显示告知。”
- **代价**: 2–3.5 人日（设计 1、测试 1–1.5、UI 状态 0.5；#12 裁决 0.2–0.5）；0 新表（`contacts` 加渠道/账号列，`invitations` 加接收方账号/确认状态字段，`meta` 加锁配置）；1 个 core 状态扩展 + 1 个 UI 状态。若把 #12 加进 S1，另加联系人删除/拉黑表与协议状态，成本会翻倍，不建议。

### F3 [BLOCKER] 跨作者最终顺序被静默忽略

- **原句与位置**: `design.md:47`“按作者顺序应用”；`design.md:114`“按 `author_pub + seq` 去重与排序”；`design.md:53–61` 的已知约束没有 GBNV；`requirements.md:93`、`:119`、`:270–276`。
- **规则**: `REQ_20261103_GBNVF8ZY42F1DZX4`；reference study §6.2 第 1 条“要么在 S1 预算里实现，要么写进已知约束；不要静默忽略”；`AGENTS.md` §4 的已知约束纪律。
- **问题与影响**: S1 设计只承诺同一作者顺序。GBNV 要求同一消息集合在设备间得到相同最终顺序，并保留同作者顺序与已证明的因果关系；它不在 S1 40 条表里，但 reference study 明确要求对它做显式分流。设计既没有实现它，也没有把它写进已知约束，也没有在 T1 里标记“不等价”。读者会以为 S1 是 T1 等价的，而 GBNV 既没做也没被承认。这正是用户在任务里点名“不能静默忽略”的项。
- **关闭证据**: 在 `design.md:53–61` 增加一句：“v1 不保证跨作者最终全序：只保证同一作者顺序；GBNV 在 S2 实现，T1 标记为不等价。”在 `test-parity.md` 或 Quint parity 表增加一行：旧 GBNV 测试/模型 → 不移植，理由“S1 只做作者顺序”，人签字。若改为 S1 实现，则在规模表加一行成本（3–5 人日、1 个 Quint 模型），并把 GBNV 加进 5 个模型。
- **代价**: 0.1 人日文档（延后）；若实现 3–5 人日 + 1 个 Quint 模型；0 表。

### F4 [BLOCKER] relay-only 与 AGENTS/DESIGN-DISCIPLINE/verification 的“直连失败走中转”冲突

- **原句与位置**: `AGENTS.md:44`“协议只保留一条路径：直连失败走中转……”；`DESIGN-DISCIPLINE.md:82` 同句；`docs/agents/verification.md:16`“v1 has one path: direct attempts fall back to a relay……”；`docs/adr/0002-technology-stack.md:25`“relay-only……不做 NAT 打洞、不做直连”；`docs/adr/0003-v1-scope.md:30`；`design.md:58`。
- **规则**: 用户固定的 v1 = relay-only；ADR-0002/0003 已接受；`AGENTS.md` 是规则正文；verification 的协议层要求模型覆盖保留的协议面。
- **问题与影响**: 规则正文、设计纪律和验证文档说 v1 有“直连尝试、失败走中转”；ADR-0002/0003 和设计说“relay-only、不做直连”。两套文本不能同时为真。更具体地，verification 要求 Quint 模型覆盖“direct attempts fall back to a relay”，而 plan 的 5 个模型列表（`design:145`）没有直连/中转路径模型。A-first spike 如果按 relay-only 实现，就会与 gate 文本不一致；如果按直连+中转实现，就违反用户固定的 relay-only 决定。无论哪种，spike 都可能证明错误的协议路径。
- **关闭证据**: 以用户决定为准，把 `AGENTS.md:44`、`DESIGN-DISCIPLINE.md:82`、`docs/agents/verification.md:16` 改成“relay-only；不做直连尝试”；或者，如果用户改主意要直连，则改 ADR-0002/0003/design，并在 5 个模型里加入 direct/relay fallback。关闭时同时把选定的路径写进 `design:145` 的模型列表和 A-first spike 的通过条件。
- **代价**: 0.5 人日文档；0 表；若保留直连则 +1 个 Quint 模型。

### F5 [BLOCKER] A-first spike 的范围与通过条件不可测/不可证伪，A/B 决策可能基于错误证据

- **原句与位置**: `design.md:166–170`（spike 表）、`:156–158`（性能门）、`:176`（“若风险不可接受，切 B”）、`:123–129`（两方 ratchet）、`:178`（“性能阈值按实测校准一次”）；`docs/adr/0002-technology-stack.md:32–36`；`docs/adr/0004-test-parity-and-performance-gates.md:23–26`；`docs/agents/engineering.md:88–94`。
- **规则**: ADR-0002 的 A→B 触发条件；ADR-0004 的性能硬门；`DESIGN-DISCIPLINE.md` R5“每条 gate 必须可证伪”、R6“每条意见量化代价”；D2“新抽象必须解释为什么不能复用现有机制”。
- **问题与影响**: 这条 finding 有五个相互关联的缺口：

  1. **性能门在 A-first spike 里不可测。** A-first 行是“传输 + 邀请/应答 + 两方 ratchet”，5 人日；但“性能门”包含冷启动 p50/p95（定义为“进程启动到 UI 可交互”）和空闲内存（“JVM + Electron 合计”）。UI 与 Electron 在另外的 spike 和 M4，5 人日的传输 spike 无法测这两项。若 A-first 因未建组件而“失败”，就会以错误理由切 B；若它不测这两项，就违反 ADR-0002“任一项无证据 → B”。
  2. **性能失败动作与 B 触发冲突。** `design:170` 的性能基线 spike 失败动作是“校准冷启动阈值并写回 ADR-0004”，不是切 B；`design:178` 更宽地说“性能阈值按实测校准一次”。ADR-0002:36 与 `engineering.md:94` 说任一项性能门无证据即切 B；ADR-0004:24 只允许冷启动校准一次。计划因此可以把用户固定的 1,000 条 <5s 或 ≤800MB 门静默放宽。
  3. **安全门漏了设计自己的前向保密要求。** A-first 行的“S1 安全门”指向 ADR-0002:33 的五项（密钥分离、E2E、签名、重放、篡改）；`design:129` 的加密 spike 还要求“前向保密”。spike 可以在不证明设计所需 FS 的情况下通过 A/B 安全门；反之，FS 失败也没有在 gate 文本里被列为 B 触发。
  4. **最高风险组件是自选的，且没有 D2 复用分析。** `design:123–126` 要求从零做两方 ratchet，`design:176` 自己把它列为“最高风险”。但 S1 40 条里的加密需求 `REQ_20260918_DCNS38550F4AY1K5` 只要求 E2E；`design:131` 也明说旧 `encryption-scheme.md` 是参考，不是 S1 既成方案。设计没有解释为什么不能复用现有 JVM ratchet/Noise 实现（D2），也没有把 FS/ratchet 的必要性写进 S1 成本表。结果是：5 天里最可能失败的是自选的 ratchet，而 A/B 决策会把“ratchet 做不出来”记成“JVM/relay 路线不可行”，从而错误切 B。
  5. **多个通过条件不可证伪。** `design:167` 的“查询可用”没有 `EXPLAIN QUERY PLAN`、延迟或 1,000 条读写指标；`design:169` 的“滚动与输入无可感卡顿”没有帧时间/输入延迟阈值；`design:176` 的“若风险不可接受”没有判据；`design:168` 的 Electron 失败动作“换 JavaFX WebView”又是 ADR-0002:41 已经以“webview 与通知/钥匙串集成弱”否决的选项。这些都不能直接给出 PASS/FAIL 或下一步决策。

- **关闭证据**: 增加一张“gate → spike → 证据”矩阵：ADR-0002 的每一项对应哪个 spike、哪个固定场景、哪个 receipt 路径、哪个数值阈值。把冷启动/内存移到包含 Electron 壳（或命名的最小壳夹具）的 spike，并说明这两项的 A/B 判定在该 spike 而不是 5 天传输 spike；1,000 条 <5s 与 ≤800MB 不可校准，只有冷启动按 ADR-0004 校准一次。把 FS 加进安全门，或显式写“FS 不是 A/B 触发项”。对 ratchet 增加 D2 复用分析（现有 JVM ratchet/Noise 候选、为什么不用、缺口是什么），并把“ratchet 失败”与“JVM/relay 路线失败”分开决策。把“查询可用”换成“`EXPLAIN QUERY PLAN` 无全表 blob 扫描 + 1,000 条读写 p95 数值”，把“无可感卡顿”换成帧时间/输入延迟数值，删除“若风险不可接受”或绑定到命名触发条件。Electron 失败动作换成 ADR-0002 允许的选项，或先立新 ADR。
- **代价**: 1.5–2.5 人日（计划与测量脚本 1–1.5、D2 复用分析 0.5、最小壳夹具 0.5）；0 表；0 组件（最小壳夹具算 1 个测量夹具）。

### F6 [BLOCKER] T1 的 Quint 等价从“原样保留”缩成 5 个模型，但没有 KEEP/DROP 映射或签字

- **原句与位置**: `docs/adr/0004-test-parity-and-performance-gates.md:16`“Quint 模型与性质原样保留并对拍……”；`design.md:145`“目标 5 个模型，不是 144”；`design.md:149`“`test-parity.md` 在实现计划里逐条登记旧测试”；`design.md:187`“5 个 Quint 模型”。
- **规则**: ADR-0004 的 T1；`AGENTS.md` §6；`docs/agents/verification.md` 的 Quint registry；reference study §5 难点 9（先列 KEEP/DROP）与 §6.3（不要用旧测试全绿当 S1 验收）。
- **问题与影响**: ADR-0004 说旧 Quint 模型与性质“原样保留并对拍”；计划说 5 个模型，没有列出哪些旧模型/性质是 KEEP、哪些是 DROP、DROP 的理由、谁签字。`design:149` 仍把 `test-parity.md` 描述为“逐条登记旧测试”；当前清单（并行任务的未跟踪 WIP）已开始列 170 个模型与 5 个目标，但没有逐模型/性质的 KEEP/DROP 行与人签字。这违反 ADR-0004 的 T1 保留要求，也违反它“不得静默删除、跳过或放宽”的原则。reference study 支持“S1 只保留 5 个模型”，但同一份文档要求先列 KEEP/DROP；计划跳过了这一步，导致 T1 验收（`design:206`“无未签字省略”）无法核对。5 个模型里也没有 `identity_creation.qnt` 或 `idle_autolock.qnt` 的槽位，而它们覆盖 KEEP 的 S1 身份/锁功能；当前清单把这一点列为“接受 Scala 属性等价并签字，还是把模型预算加到 7 个”的人裁决。
- **关闭证据**: 在 `test-parity.md` 增加 Quint 行：旧模型/性质 → 新模型/性质、状态 KEEP/DROP、理由、人签字；或者修改 ADR-0004 为“S1 对 KEEP 协议面重新建模为 5 个模型，映射见 `test-parity.md`”，并给每个新模型列出它保留的旧性质。把 GBNV 的 DROP/不等价写进同一张表。`design:145` 同时写出 5 个模型的名字与各自覆盖的旧性质。
- **代价**: 2–3 人日映射（170 个模型的逐行 KEEP/DROP；若只映射 5 个新模型的性质来源，1 人日）；0 表；0 组件。

### F7 [SUGGESTION] 旧实现最大文件数引用了 CFA 的数字

- **原句与位置**: `design.md:38`“结构：模块边界不按变更频率切，单文件最大 5,454 行”；`docs/references/2026-11-20-yochat-reference-study/survey.md:20`“最大文件 13,373 行……生产代码最大 6,008 行（`handshake.rs`）”；`docs/evaluations/2026-11-20-cfa-session/summary.md:18`“CFA: ……largest 5,454”。
- **规则**: `AGENTS.md` §9 证据纪律（一手来源、对象/版本/日期、口径；估算不升级为实测）；`DESIGN-DISCIPLINE.md` D1（数字是估算时标明）。
- **问题与影响**: 5,454 是 CFA 的 `SessionEntity.scala`，不是 YoChat 的；YoChat 的生产最大文件是 6,008 行（`handshake.rs`），测试最大 13,373 行。该句把 CFA 的数字安到旧实现上，低估了旧实现的结构问题，也让旧实现结构诊断的案例来源不可核。它不影响 spike 的技术判断，但违反证据纪律，应在实现前修正。
- **关闭证据**: 改成“旧实现生产代码最大 6,008 行（`handshake.rs`），测试最大 13,373 行”，并引 `survey.md:20`；如果确实要引用 CFA 的 5,454，写明“CFA 的 `SessionEntity.scala`”，不要写成旧实现。
- **代价**: 0.1 人日；0 表；0 组件。

## 5. 出口与升级

- **已知约束出口**: `design.md:53–61` 有出口，但不完整；F2 的 S1 缺口和 F3 的 GBNV 都没有进入。关闭 F2/F3 后，出口才算覆盖 S1。
- **测试出口**: `test-parity.md` 在 ADR-0004:17、`design:149`、`design:206` 都有名字；当前清单有 Quint 段，但没有逐模型/性质的 KEEP/DROP 行与人签字；F6 关闭前，T1 的出口不完整。
- **文档出口**: `design.md:195–198` 声明了 `technology-stack.md`、旧 v1 范围、`encryption-scheme.md` 的群棘轮、旧 `docs/plans/` 的处置。没有发现“取代了谁”缺失；旧 `docs/spec/product/*` 仍作为后续版本输入，符合 ADR-0003:52。
- **人工升级**: 本轮不升级。若关闭 F2 时选择把 #12 加进 S1 或删掉任何 S1 安全不变量、或关闭 F3 时选择 S1 实现 GBNV、或关闭 F6 时选择 DROP 旧 Quint 性质，需要用户签字，不能只靠文档改写。

## 6. 未纳入本轮 finding 的观察

这些不是 round-1 finding（受 7 条上限），但会影响关闭质量：

- `design:119`“S1 只有一台设备”应改为“每个用户各一台设备”；否则 spike 可能把邀请/应答做成单设备自聊。0.1 人日。
- `design:21` 的“旧 144”与 `survey.md:21` 的 170 个 `.qnt` / 154 个模型文件口径不同；引用时写明“复盘口径 144”或改用 survey 口径。0.1 人日。
- `design:61` 的“中继可见端点、时间、字节数”与 `docs/spec/product/privacy-and-safety.md:39–48` 的已接受限制一致；内容与用户身份不可读的边界没有被设计扩大。本轮不作为 finding。
- A-first、2,000 行覆盖、最后一次整体重写三条在 AGENTS/DESIGN-DISCIPLINE/ADR/plan 之间一致；本轮不作为 finding。relay-only 与 T1 的例外已在 F4/F6 记录。

## 7. Round 1 修复与关闭状态

本节是修复记录；上面的对象、冻结哈希与 finding 正文保留评审时的状态，不回写。

修复轮修改了：`AGENTS.md`、`DESIGN-DISCIPLINE.md`、`docs/adr/0002-technology-stack.md`、`docs/adr/0003-v1-scope.md`、`docs/adr/0004-test-parity-and-performance-gates.md`、`docs/agents/engineering.md`、`docs/agents/verification.md`、`docs/plans/2026/11/0001-yochat-rewrite/design.md`、`docs/plans/2026/11/0001-yochat-rewrite/test-parity.md`、`docs/plans/2026/11/0001-yochat-rewrite/test-parity-quint.md`。

用户 2026-11-20 签字：#12 OMIT、11 条 S1 不变量范围、GBNV 在 S2/T1 不等价。

| Finding | 状态 | 关闭证据 | 未完成 |
| --- | --- | --- | --- |
| F1 删除判据可删安全不变量 | 已关闭（用户签字 2026-11-20） | ADR-0003「删功能，不删 S1 安全不变量」+ 不可删清单；AGENTS/DESIGN-DISCIPLINE 同步；`design.md`「S1 不变量落点」引用 | 无 |
| F2 S1 范围与 40 条/14 条不一致 | 已关闭（用户签字 2026-11-20） | `design.md` 新增 11 行落点表；`State.lock`/`idleDeadline`、日志脱敏、定向邀请/账号绑定、A1M 确认、准入规则进入状态机与协议路径；`contacts`/`invitations` 增加渠道/确认列；`verification.md` 改为 14 条，#12 改为日志/诊断；`test-parity.md` 40 行映射 | 无 |
| F3 跨作者最终顺序被静默忽略 | 已关闭（用户签字 2026-11-20） | `design.md` 非目标/已知约束与验收写入 GBNV S2、T1 不等价；AGENTS/DESIGN-DISCIPLINE 同步；`test-parity.md` 1.3 增加 DROP 行 | 无 |
| F4 relay-only 与规则文本冲突 | 已关闭 | `AGENTS.md`、`DESIGN-DISCIPLINE.md`、`docs/agents/verification.md` 改为 relay-only；ADR-0002/0003/design 一致 | 无 |
| F5 A-first gate 不可测/不可证伪 | 已关闭 | ADR-0002 命名 gate；`design.md` 增加 gate→spike→证据、SQLCipher/Laminar/Electron 阈值、冷启动/内存移出 A-first、ratchet 单独方案 gate、D2 复用分析入口、移除 JavaFX WebView fallback | 阈值是估算，spike 回执为准 |
| F6 T1 Quint 等价缩水无映射/签字 | 映射完成，待逐行签字 | `test-parity.md` 1.3 增加 Q1–Q5 与 identity/idle lock 的 KEEP/DROP；ADR-0004 改为 5 模型 + 映射表；`test-parity-quint.md` 覆盖 170 模型/334 配置，5 KEEP、22 REWRITE、143 DROP | 170 行逐行签字 |
| F7 旧实现最大文件数引用 CFA | 已关闭 | `design.md` 改为生产 6,008（handshake.rs）、测试 13,373；5,454 标明是 CFA 的 SessionEntity.scala | 无 |

未纳入 finding 的观察已处理：`design.md` 改为「每个用户各一台设备」；Quint 规模行改为「旧 144（复盘口径；当前检出 170）」。

Round 2 只做 F6 的 170 行逐行签字核对；映射已补完，不新增设计范围，不新增 finding，除非出现与上述关闭证据直接矛盾的新事实。
