# S1 测试等价清单（T1）

- 状态：per-test inventory generated 2026-11-24；append-only，等待人工签字。需求级映射仍为 40 行；逐测试 CSV 与签字材料见 `test-parity-signoff.md`。标 `PARTIAL`/`NOT YET` 的需求行仍必须先裁决。
- 范围：ADR-0003 的 S1，只映射 KEEP 功能；S2/S3/D 不映射。
- 旧基线：`/Users/weiwen/projects/yochat-reference` 当前只读检出。
- 方法：仅静态扫描与读文件；**本文不声称任何测试通过，未运行构建、测试、lint 或 Quint**。
- 计数口径：以下数字是当前检出里的声明数，不是运行数。旧计划回执（旧仓 `docs/plans/2026-09/20260918-yochat-f2f-messenger.md`）曾记 Core 1119、Protocol 251、App e2e 430、Net lib 56/集成 61；当前静态扫描不同，原因是检出已变化且运行计数与声明计数不是同一口径。T1 以当前检出为映射基线，实际运行数只认回执。
- 旧研究里的 144/154 个模型是历史快照；当前检出是 170 个 `.qnt`，本文按 170 记录。

复现主要计数的命令：

```bash
jq '.checks|length' models/checks.json
jq -r '.checks[].file' models/checks.json | sort -u | wc -l
rg -o '#\[(tokio::)?test\]' crates -g '*.rs' | wc -l
rg -o '^\s*(it|test)\(' e2e/specs -g '*.e2e.ts' | wc -l
rg -o '^\s*(it|test)\(' ui/tests -g '*.test.ts' | wc -l
```

状态图例：

| 状态 | 含义 |
| --- | --- |
| `PORT` | 旧断言/场景按同语义移植，只换语言或框架。 |
| `REWRITE` | 需求不变，但旧测试绑定到已删除的架构（group/welcome、p2p/iroh、旧 Tauri/Svelte、恢复短语、多设备、历史同步等）；按新结构重写，不是逐行等价，需人签字。 |
| `OMIT` | 旧测试覆盖非 S1 行为，不移植；理由必填。 |
| `GAP` | 旧层没有足够测试；新增测试补足，不冒充等价移植。 |

清点列：

| 值 | 含义 |
| --- | --- |
| `FULL` | 已定位具体旧文件/场景与计数，本行可进入实现。 |
| `PARTIAL` | 主入口已定位，尚未逐测试登记。 |
| `NOT YET` | 只有候选域，尚未逐文件核对。 |

## 1. 旧测试分层清点

### 1.1 分层总量

| 层 | 位置 | 静态计数 | S1 关系 |
| --- | --- | ---: | --- |
| Quint 模型 | `models/*.qnt`、`models/checks.json` | 170 个模型；334 条 check 配置；170/170 个模型已登记；327 条 TLC、7 条未写 backend；655 个 invariant 引用、379 个 temporal 引用 | 只保留与 S1 一条路径有关的模型；目标 5 个，不是全量移植。 |
| Quint 回放 | `tools/export_*_traces.sh`、`*_parity.rs` | 61 个 trace 导出脚本；多个 Rust 对拍测试 | 回放器与导出格式绑旧 Rust 状态名，需按 Scala `step` 重写。 |
| Rust 测试 | `crates/*` | 460 个 `.rs`；199 个 `tests/` 下的 `.rs` 文件（含 support 模块）；2200 个 test 属性（core 809、protocol 778、app 486、net 125、node 2）；15 个文件含 `proptest!` 块；1 个 proptest-state-machine 文件（`protocol/tests/journal_parity.rs`）；1 个 `#[ignore]` | 大量测试绑定旧 group/DCGKA/p2p 架构；只按 S1 保留功能移植性质与场景。 |
| Kani | `yochat-core`、`yochat-protocol` | 19 个 harness，分布在 5 个文件 | 无 Scala 等价物；只能登记缺口并以属性测试补足或人工裁决。 |
| UI 单测 | `ui/tests` | 32 个文件、313 个 test | 旧 Svelte 组件测试；新 Laminar 结构下按用例意图重写，不移植组件 API。 |
| E2E | `e2e/specs`、`e2e/wdio.*.conf.ts` | 51 个 spec、193 个 `it/test` 声明、29 个 wdio 配置 | 大部分是 S2/S3；S1 相关候选为 13 个 spec、52 个场景：`invite`、`exchange`、`identity`、`identity-restart`、`backend-restart`、`backend-restart-checkpoint`、`idle-lock`、`log-secrets`、`diagnostics`、`late-arrival`、`resend`、`rebuild`、`manual-maintenance`。 |
| 原生/人工 | `tools/run_native_suite.py`、`tools/NATIVE_ACCEPTANCE.md` | 35 个 NativeCase（含 1 个需人工点击系统通知）；IME 仅人工观察 | 新 Electron 壳不能复用旧 Tauri 原生 harness；只保留场景意图。 |

### 1.2 S1 相关旧测试入口

| 领域 | 旧测试入口（声明计数） | 备注 |
| --- | --- | --- |
| 身份、锁、静态加密 | `core/tests/app_lock.rs`（6）、`keyfile.rs`（1 个 proptest）、`store_at_rest.rs`（3）；`identity_creation.qnt`（2 配置）、`idle_autolock.qnt`（1 配置）；`e2e/identity`（6）、`identity-restart`（4）、`idle-lock`（1）、`log-secrets`（2）、`backend-restart`（1，2 个阶段）；`ui/tests/idle-lock`（12）、`identity-flow`（22） | 恢复短语相关场景属 S3；S1 只取创建、口令解锁、锁边界、静态加密、日志无密钥。 |
| 邀请、应答、关系 | `core/tests/invite.rs`（18）、`reply.rs`（15）、`exchange.rs`（18）、`relationship.rs`（9）、`safety.rs`（8）、`invite_lifecycle.rs`（8）；`exchange.qnt`（2 配置）、`contact_reply.qnt`（1）、`contact_confirmation.qnt`（1）、`authenticity.qnt`（1）；`e2e/invite`（7）、`exchange`（12）；`ui/tests/invite-flow`（13）、`exchange-flow`（20）、`invite-copy`（3） | 旧 `exchange.qnt` 含 group/welcome 机制；只取一对一关系性质。`e2e/exchange` 中 2 个隐私告知场景与 `e2e/invite` 的公开张贴/渠道复用场景属 S3。旧 contact-bootstrap 家族是更复杂的有界发现/接管协议，不属于 S1 的一条路径，不在此表。 |
| 排序、去重、先落盘 | `core/tests/ordered_apply.rs`（8）、`claimed_time.rs`（5）、`state_roundtrip.rs`（5）、`core/src/message_order/tests.rs`（7）；`protocol/tests/delivery.rs`（19）、`ordering.rs`（13）、`presentation_order.rs`（3）；`message_order.qnt`（2）、`cursor.qnt`（4）、`order_frontier.qnt`（2）、`order_content.qnt`（11）、`local_order_promotion.qnt`（1） | `order_content`/`order_frontier` 大量服务 S2 跨作者全序；S1 只取按作者顺序、去重、等待与提示。 |
| 重发、重建、通道 | `protocol/tests/resend.rs`（52）、`rebuild.rs`（29）、`send_retry.rs`（13）；`core/tests/fallback_resend.rs`（10）、`retransmit_dispatch.rs`（5）、`sustained_undecryptable.rs`（2）；`resend.qnt`（2）、`send_retry.qnt`（3）、`rebuild.qnt`（5）、`retransmit_dispatch.qnt`（1）；`e2e/resend`（5）、`rebuild`（7）、`manual-maintenance`（1） | 旧实现有 native dependency、history scope、批准队列；S1 只保留一条缺失/重发/重建路径。 |
| 重启、崩溃恢复 | `protocol/tests/journal_crash.rs`（2）、`journal_faults.rs`（3）、`journal_parity.rs`（1 个状态机测试）；`core/tests/state_roundtrip.rs`（5）；`e2e/backend-restart`（1，2 阶段）、`backend-restart-checkpoint`（1）、`identity-restart`（4）；`e2e_restart_generation.qnt`（1）、`handshake_reconnect.qnt`（1）、`packet_journal.qnt`（1） | 旧 history-sync 的断网/重启场景属 S2；S1 要的是完整进程重启后消息仍在。 |
| 有界队列、帧截止、交付范围 | `inbound_backpressure.qnt`（4 配置）、`inbound_frame_deadline.qnt`（1）、`delivery_scope_reconcile.qnt`（1） | S1 只保留有界队列与固定上限；旧配额、暂停/恢复、按对端预算属 S3。 |
| 加密篡改、重放、签名 | `core/tests/reply.rs`（2 个篡改 proptest）、`envelope.rs`（12 + 1 proptest）、`core/src/message_order/tests.rs`（7，含签名/篡改）、`protocol/tests/live_authentication.rs`（3）、`inbound_gate.rs`（10）；`authenticity.qnt`（1）、`shared_prekeys.qnt`（1）、`buffered_decryption.qnt`（3）；19 个 Kani harness | 旧 E2E 没有“篡改密文/签名被拒”的端到端场景；这一层是 S1 新契约。 |
| Relay、发现、诊断 | `e2e/diagnostics`（4）、`log-secrets`（2）；旧 `yochat-net` 125 个 test 属性（iroh/QUIC/PKARR） | 旧传输是 p2p+relay 回退，S1 是 relay-only；只保留“内容不可读、未授权不响应、发现不发布直接地址”的性质。 |

### 1.3 Quint KEEP/DROP 映射

S1 只冻结 5 个新模型。下表先列每个新模型的性质来源与显式 DROP；170 个旧模型/334 条配置的完整逐行映射见 `test-parity-quint.md`（5 KEEP / 22 REWRITE / 143 DROP，逐行签字待补），**未签字不得 DROP**。

| 新模型 | 覆盖 | 旧模型/性质来源 | 处理 | 理由 | 人签字 |
| --- | --- | --- | --- | --- | --- |
| `Q1` 邀请/应答 | 一次性密钥、签名、双方确认、拒绝静默 | `exchange.qnt` 的 `relationshipConsistency`、`responderFollowsInitiator`、`responderSilentWhileConfirmed`、`noBundleUsedTwice`；`shared_prekeys.qnt` 的 `noDoubleUse`；`authenticity.qnt` | `REWRITE`（一对一性质保留；group/welcome `OMIT`） | S1 是定向邀请、两方关系；group/welcome 属 S2 | |
| `Q2` 排序/去重 | 同作者顺序、去重、缺失检测 | `message_order.qnt`、`cursor.qnt` | `KEEP` | S1 只保证同一作者顺序 | |
| `Q2` 跨作者全序 | GBNV 的最终全序 | `order_content.qnt`、`order_frontier.qnt` 的全序部分 | `DROP` → S2 | S1 不实现 GBNV；T1 标记为不等价，不得静默忽略 | |
| `Q3` 等待/重发 | 超时结束、有界重发、发送方兜底 | `resend.qnt`、`send_retry.qnt`、`retransmit_dispatch.qnt` | `REWRITE`（有界与截止保留；history/native 批准队列 `OMIT`） | S1 只保留单会话小范围重发 | |
| `Q4` 重连 | 重连后重放未确认消息 | `handshake_reconnect.qnt`、`e2e_restart_generation.qnt`、`packet_journal.qnt` | `REWRITE`（重连/journal 语义保留；历史同步 `OMIT`） | S1 是完整进程重启后消息仍在，不做历史同步 | |
| `Q5` 通道重建 | 解密失败→重建→重传未确认、限频 | `rebuild.qnt` | `REWRITE`（重建有界与不静默分叉保留；群 epoch 细节 `OMIT`） | S1 是两方 ratchet/通道重建，不是群 epoch | |
| 无模型槽 | 身份创建、空闲自动锁定 | `identity_creation.qnt`、`idle_autolock.qnt` | `REWRITE` 为 Scala 属性 | 5 个协议模型没有这两个槽位；是否加模型预算需人裁决 | |

说明：本表的处理是旧文件级判定。`Q1` 的 `authenticity.qnt`/`contact_reply.qnt`/`contact_confirmation.qnt` 为 `KEEP`，`exchange.qnt`/`shared_prekeys.qnt` 为 `REWRITE`；`Q2` 的 `message_order.qnt`/`cursor.qnt` 为 `KEEP`；`Q3`/`Q4`/`Q5` 的旧文件均为 `REWRITE`。170 行整表签字见 `test-parity-quint.md`（用户 2026-11-20）。

## 2. 40 条 S1 需求映射

需求 ID 省略公共前缀 `REQ_20260918_`。旧测试计数是声明数，不是运行数。40 行中有 34 行 `FULL`、5 行 `PARTIAL`、1 行 `NOT YET`；`PARTIAL`/`NOT YET` 表示尚未逐测试清点，不能当作已完成映射。

新测试缩写：

- `Q1` 邀请/应答、`Q2` 排序/去重、`Q3` 等待/重发、`Q4` 重连、`Q5` 通道重建：设计规定的 5 个 Quint 模型。
- `SM`：Scala 属性/状态机测试（真实 SQLite 或纯 `step`）；`ST`：Scala 存储测试；`UT`：Scala 单元/性质测试；`VEC`：ADR-0006 加密测试向量。
- `E2E#n`：`docs/agents/verification.md` 行为契约编号。

### A. 身份、密钥、本地加密、应用锁、诊断

| ID | 需求 | 旧测试/场景 | 层 | 新测试计划 | 状态 | 理由与省略 | 清点 | 人签字 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `4DYMP7NVJDKNPTKR` | 用户是本机生成的一对密钥 | `identity_creation.qnt` 2 配置（`durableCreationConfirmationChecks`、`initialAuthorBindingChecks`）；`core/tests/keyfile.rs` 1 个 proptest；`e2e/identity` 6 场景；`app/src/identity.rs` 创建路径 | Quint + Rust prop + E2E | `UT-keyfile-create`；`E2E first-run-create`（移植 identity 创建路径）；无 Quint 模型（见理由） | `REWRITE` | 旧 E2E 同时断言 24 词恢复与回填，属 S3；设计只冻结 5 个协议模型，`identity_creation.qnt` 没有模型槽位。属性改由 Scala 承担，模型层等价缺失需签字。 | FULL | |
| `R1J6B5CH21YFFP32` | 用户私钥只存在于用户指定的设备上 | `keyfile.rs`；`app/src/identity.rs::holds_user_key`；`e2e/user-key` 4 场景（S2 多设备）；`backend-restart` 断言只有第一台设备持有用户私钥 | Rust prop + E2E | `UT-keyfile-single-device`；`E2E#4/#5` 重启后仍持钥；S2 的转交场景 OMIT | `PORT` | S1 退化为单设备；`user-key` 的转交、D 设备加入属于 S2，明确 OMIT。 | FULL | |
| `ZTSEXMZQ2VHS21R6` | 本地数据静态加密 | `core/tests/store_at_rest.rs` 3 场景：全目录找不到消息/联系人/密钥标记、正确密钥可读回、实例互解不开 | Rust + E2E | `ST-store-at-rest`（真实 SQLite + 全目录标记扫描 + 重开）；`E2E#4/#5` | `REWRITE` | 旧 SQLCipher 还断言「SQLite 文件头非明文」；plain SQLite 的头本身可见，ADR-0007 用应用层 AEAD，新断言改为目录无消息/联系人/密钥标记、正确密钥可读、错误密钥/实例在解锁或首条会话解密失败。删去的头断言需人签字。 | FULL | 待签字 |
| `B9Y4E73VMQ8RS99X` | 应用锁是安全边界而不是界面切换 | `core/tests/app_lock.rs` 6（含 1 个 200 例 proptest：旧代数结果永不落地）；`idle_autolock.qnt`；`ui/tests/idle-lock` 12；`e2e/idle-lock` 1 | Rust prop + UI + E2E | `SM-app-lock`；`E2E#13` app lock/unlock | `PORT` | 锁代数、在途结果失效、切换实例拒旧凭据均保留。 | FULL | |
| `YQW27DXZJSHAR0Z0` | 空闲超时自动锁定 | `idle_autolock.qnt`（`safe`、`expires`、`lockEnds`）；`app_lock.rs::空闲超时触发锁定_且不被后台活动重置`；`e2e/idle-lock` 1；`ui/tests/idle-lock` 12 | Quint + Rust + UI + E2E | `SM-idle-lock`；`E2E#13` | `REWRITE` | 设计只保留 5 个协议模型，空闲锁不在其中；模型属性降为 Scala 属性测试。若要求 Quint 级等价，需另加模型并修改设计的模型预算。 | FULL | |
| `865PCSHV46Q4X2XF` | 密钥材料不进入日志与崩溃报告 | `e2e/log-secrets` 2 场景（最高级日志确有内容、秘密标记一处不命中）；`e2e/diagnostics` 默认日志场景 | E2E | `E2E-log-secrets`（扫描日志与崩溃输出）；`UT-log-redaction` | `PORT` | 场景与断言保留；旧 Tauri harness 换成 Electron harness。 | FULL | |
| `DVFN7QEAWP32QD9G` | 诊断默认不记录内容 | `e2e/diagnostics` 4 场景，其中 1 个断言默认日志不含正文和联系人标识；另 2 个是连接方式展示（S3） | E2E | `E2E-diagnostics-content` | `PORT` | 只移植内容边界；“界面显示直连/中转”属 S3 的 `REQ_20260918_377PNHQWJ8PGCN80`，OMIT。 | FULL | |

### B. 邀请、应答、关系、安全码

| ID | 需求 | 旧测试/场景 | 层 | 新测试计划 | 状态 | 理由与省略 | 清点 | 人签字 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `1VXJQ68HXHM0JR0Q` | 关系需要双方各自确认 | `exchange.qnt` 2 配置（`relationshipConsistency`、`responderFollowsInitiator`、`responderSilentWhileConfirmed`）；`core/tests/relationship.rs` 9；`core/tests/exchange.rs` 18；`e2e/exchange` 12（待确认与联系人分离、确认后出现） | Quint + Rust prop + E2E | `Q1`；`SM-relationship`；`E2E#1/#2` | `PORT` | 旧 exchange 的 group/welcome 部分 OMIT；只保留非对称确认：应答方先确认，发起方后确认。 | FULL | |
| `2276A5JCK1KGNS1A` | 邀请由用户私钥签名并绑定发起方渠道账号 | `core/tests/invite.rs` 18；`exchange.qnt`；`e2e/invite` 7；`ui/tests/invite-flow` 13 | Rust + Quint + E2E + UI | `Q1`；`UT-invite-signature`；`E2E#1` | `PORT` | S1 只做定向邀请；不定向邀请与公开张贴的代价说明 OMIT（S3）。 | FULL | |
| `37F049FH3B007X48` | 每份邀请使用专属的一次性公钥加密应答 | `core/tests/reply.rs` 15（正常应答、长期用户公钥解不开）；`shared_prekeys.qnt`（`noDoubleUse`）；`exchange.qnt`（`noBundleUsedTwice`） | Rust + Quint | `Q1`；`VEC-reply-seal`；`E2E#1/#9` | `PORT` | 端口“一次性密钥、只可解一次”的性质；旧 group prekey 与 welcome 机制 OMIT。 | FULL | |
| `GXF8WYYMWT40CFSM` | 应答只有邀请发起方能打开且不可改换作者 | `reply.rs` 15（改换作者、换邀请、重放、明文头篡改、2 条任意字节破坏 proptest 性质）；`envelope.rs` 12 + proptest；envelope 4 个 Kani harness | Rust prop + Kani | `Q1`；`VEC-reply-tamper`；`E2E#11` | `PORT` | 验签前拒绝的断言保留；Kani 层无 Scala 等价，登记为缺口并由属性测试补足。 | FULL | |
| `YP7HYFDC5BSKK1BP` | 邀请与应答必须绑定渠道上的权威账号 | `reply.rs`（账号不符进待确认并带标记、渠道标签归一化）；`e2e/exchange` 12；`ui/tests/exchange-flow` 20 | Rust + E2E + UI | `SM-reply-verify`；`E2E#1` | `PORT` | 渠道身份复用属 S3；S1 只校验本次邀请声明的账号。 | FULL | |
| `6J1EMT8Y9HZ68STG` | 邀请各自独立，有有效期且可随时作废 | `core/tests/invite.rs` 18；`invite_lifecycle.rs` 8（过期与作废分开、作废覆写、跨重启）；`reply.rs` 过期/作废拒绝；`exchange.qnt noEstablishAfterInviteDead`；`e2e/invite` 7 | Rust + Quint + E2E | `Q1`；`UT-invite-lifecycle`；`E2E#9` | `PORT` | S1 只做本地有效期与作废；已张贴文本的撤回是交互边界，OMIT（S3）。 | FULL | |
| `HYREM42TB44CYAHF` | 无匹配记录的入站一律丢弃 | `core/tests/admission.rs` 10；`inbound_discard.rs` 10；`inbound_gate.rs` 10（陌生对端什么都不发生） | Rust | `SM-admission`；`UT-inbound-discard` | `PORT` | 单元层已覆盖；旧 E2E 没有“陌生对端零响应”场景，E2E 层记 `GAP`，由新捕获测试补。 | FULL | |
| `KWW858WDBR49C9KS` | 拒绝一份应答不产生任何 YoChat 层面的信号 | `core/tests/exchange.rs::拒绝一份应答只删本地那一行`；`exchange.qnt responderSilentWhileConfirmed` | Rust + Quint | `SM-reject-silent`（断言无 Send/Notify 效果） | `PORT` | 旧 E2E 只测界面不出现联系人，没有断言网络静默；新测试在核心层断言零效果。 | FULL | |
| `A1MZYZ7JV43G7EJ3` | 应答方自发出应答起即完成本方确认 | `exchange.qnt responderFollowsInitiator`；`e2e/exchange` 12（应答方生成后立刻看到联系人）；`exchange.rs` 18 | Quint + E2E | `Q1`；`E2E#1/#2` | `PORT` | 发起方仍须另行确认；不把两侧确认合并成一次。 | FULL | |
| `FXAWEFJ6Q0S22E5S` | 安全码可带外比对 | `core/tests/safety.rs` 8（含 3 条 proptest 性质：对称、两侧应念段不同且覆盖 39 位、零填充）；`authenticity.qnt` 1；`e2e/exchange` 两段勾选；`tools/safety_number_vectors.py` | Rust prop + Quint + E2E | `SM-safety-number`；`E2E#1`；`VEC-safety` | `PORT` | 人工带外核对本身不可自动化；测试验码的派生与展示，并验 `safety_verified=false` 时禁止正文（ADR-0006 安全门）。 | FULL | |
| `WEXAYYYH18M9XH5Y` | 关系建立之后的一切由签名承担 | `envelope.rs` 12；`core/src/message_order/tests.rs` 7（伪造高度/作者、截断、旧 tag）；`live_authentication.rs` 3；`inbound_gate.rs` 10；envelope 4 个 Kani | Rust prop + Kani | `SM-signed-message`；`VEC-signature`；`E2E#11` | `PORT` | 旧 group membership 签名场景 OMIT；S1 的签名对象是消息与关系声明，不是成员变更。 | FULL | |

### C. 单聊发送、加密、准入

| ID | 需求 | 旧测试/场景 | 层 | 新测试计划 | 状态 | 理由与省略 | 清点 | 人签字 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `JW13382752N6T1XR` | 发送与接收一条消息 | `protocol/tests/delivery.rs` 19（写入、发送、收到、离线、状态）；`e2e/exchange` 12；`ui/tests/chat-flow` 89 | Rust + E2E + UI | `E2E#3`；`SM-message-lifecycle` | `PORT` | 旧测试包含多设备/历史分支；S1 只取一台发送、一台接收、先落盘再发。 | PARTIAL | |
| `DCNS38550F4AY1K5` | 消息端到端加密 | `protocol/tests/delivery.rs`、`envelope.rs`、`membership_keys` 相关测试、`crypto_backend`；旧 DCGKA/p2panda 群棘轮 | Rust + old crypto | `VEC-aead`；`SM-crypto-state`；`E2E#10` | `REWRITE` | ADR-0006 决定 S1 两方 ratchet，旧测试锁的是群棘轮实现；只保留“中继不可读、篡改必拒”的性质，按新方案重写向量。 | PARTIAL | |
| `RYGFPP529TDA9R6X` | 不与未建立关系的对端交换消息 | `core/tests/admission.rs` 10；`inbound_gate.rs` 10；`delivery.rs` 的准入分支 | Rust | `SM-admission`；`E2E#9`（错误邀请）；无关对端 E2E 记 `GAP` | `PORT` | 无关系、已删除、未确认三类拒绝都保留；S1 不做拉黑。旧层没有无关对端的 UI/传输 E2E，需补或接受核心层覆盖。 | PARTIAL | |
| `P7X1QGZCYA41GAQT` | 内容与参与者对中转设施不可读 | 旧 `yochat-net` 125 个 test 属性（iroh/QUIC/relay 回退）、`relay_receive.qnt`、`tools/check_relay_receive.py` | Rust + net model + tool | `relay-sees-ciphertext-only` 集成测试（relay 进程侧收到帧断言）；E2E 只验用户路径 | `REWRITE` | 旧实现是 p2p 优先、relay 回退，测试绑定 iroh 端点与地址；S1 是 relay-only。只保留“中继只见密文与路由信息”的断言。 | PARTIAL | |
| `MXHYRG96W0VKB031` | 对未获授权的请求不产生响应 | `inbound_gate.rs` 10；`admission.rs` 10；`inbound_discard.rs` 10；`authenticity.qnt` | Rust + Quint | `SM-inbound-gate`（断言无响应效果）；E2E 捕获测试 | `PORT` | 旧层没有端到端“无响应”断言，E2E 记 `GAP`；核心层断言保留。 | PARTIAL | |

### D. 排序、去重、缺失、时间、持久化

| ID | 需求 | 旧测试/场景 | 层 | 新测试计划 | 状态 | 理由与省略 | 清点 | 人签字 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `YACX40YKDV0GV3G6` | 消息缺失必须可被检测 | `core/tests/ordered_apply.rs` 8（proptest：已决集合始终连续）；`cursor.qnt` 4 配置（`everyGapIsWaited`、`noAppliedBeyondHole`）；`e2e/resend` 5（其后那条被扣住且本机知道在等） | Rust prop + Quint + E2E | `Q2/Q3`；`SM-order`；`E2E#6/#14` | `PORT` | 检测按日志位置，不按消息自称时间；旧 history 占位分支 OMIT（S2）。 | FULL | |
| `537BK4VME23Y2GTW` | 严格按序应用，不得跳过 | `ordered_apply.rs` proptest 连续区间；`cursor.qnt`；`order_content.qnt` 11、`order_frontier.qnt` 2（后者主要服务跨作者全序）；`presentation_order.rs` 3 | Rust prop + Quint | `Q2`；`SM-order`；`E2E#7` | `PORT` | S1 只承诺按作者顺序；`REQ_20261103_GBNVF8ZY42F1DZX4` 的跨作者最终全序属 S2，旧 `order_content`/`order_frontier` 的全序部分 OMIT。 | FULL | |
| `BXC7RNH3YCQZ9BQY` | 等待只因乱序出现并自行结束 | `ordered_apply.rs` 超时事件；`cursor.qnt waitEventuallyEnds`；`delivery.rs` 等待状态 | Rust prop + Quint | `Q2/Q3`；`SM-order` | `PORT` | 旧模型活性带 `weakFair` 前提；新模型必须显式写出公平性前提与安全对偶。是否把“自行结束”解释为“公平调度下超时结束”需人裁决。 | FULL | |
| `S02F3SXHZY2DHA87` | 重复到达的消息只呈现一次 | `ordered_apply.rs::任意重复投递不改变最终结果`；`protocol/tests/ordering.rs` 13（重复投递幂等）；`delivery.rs` 19（重复重放不改变状态） | Rust prop | `Q2`（去重不变量）；`E2E#7` | `PORT` | 重复按内容身份/日志位置幂等，不按到达次数累加。 | FULL | |
| `XMX889MKAJ4QK8JX` | 已发送消息永久保留原文 | `protocol/tests/delivery.rs`（交付后正文保留）；`resend.rs` 52（原件重取）；`core/src/store/content_storage` 相关测试 | Rust | `SM-original-retention`；`E2E#14` | `PORT` | 删除/墓碑属 S3，OMIT；S1 只验发送后原件不被重加密或覆盖。 | FULL | |
| `C2TDVVB9MGCY30KM` | 消息默认永久保存 | `state_roundtrip.rs::状态里不存在保留期或自动删除一类的字段`；`delivery.rs` 19 | Rust | `UT-no-expiry`；`E2E#4/#5` | `PORT` | 负向断言：状态与表里没有自动过期/保留期字段。 | FULL | |
| `DK8P5E0K5GES1T0C` | 操作作用于消息所属的聊天会话 | `delivery.rs::别的会话的操作不落进本实例`；`protocol/tests/search.rs` 等会话范围测试 | Rust | `UT-conversation-scope` | `PORT` | S1 单会话也要防跨会话写入；搜索/删除的跨会话场景属 S3，OMIT。 | FULL | |
| `R5JVF7M78YZQKN8H` | 存储失败必须报告为失败 | `journal_faults.rs` 3（事务回滚、读失败不返回空、写失败不多不少）；`delivery.rs` SQL 失败分支；`send_retry.rs` 写故障 | Rust | `SM-store-failure`（真实 SQLite 故障注入）；`E2E` 无 | `PORT` | 错误不得翻译成空/未找到；旧断言直接保留，S1 必须用真实 SQLite。 | FULL | |
| `T6HDW9X9ZSAMECDT` | 消息自称的时间不作为判定依据 | `core/tests/claimed_time.rs` 5（未来/过去/容差/溢出/展示原值）；`delivery.rs` 自称时间不改变位置 | Rust | `SM-claimed-time` | `PORT` | 排序按作者与日志位置；自称时间只用于展示提示。 | FULL | |

### E. 重发、重建、状态、离线

| ID | 需求 | 旧测试/场景 | 层 | 新测试计划 | 状态 | 理由与省略 | 清点 | 人签字 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `2MTCJFK2TX0E9ACY` | 无法解开的消息触发自动通道重建 | `rebuild.qnt` 5 配置（重建限频、重传未确认、不静默接受分叉）；`protocol/tests/rebuild.rs` 29；`e2e/rebuild` 7；`inbound_gate.rs` 10 | Quint + Rust + E2E | `Q5`；`SM-rebuild`；`E2E#14` | `REWRITE` | 旧实现按 DCGKA 群 epoch 重建；S1 是两方 ratchet。保留“解密失败→重建→重传未确认→不静默丢”的场景，内部断言按新状态机重写。 | FULL | |
| `VBRZ8N5AC8QR0N3B` | 接收方可主动请求重发 | `resend.qnt` 2；`protocol/tests/resend.rs` 52；`e2e/resend` 5；`ui/tests/chat-flow` 中的等待/重发状态 | Quint + Rust + E2E | `Q3`；`SM-resend`；`E2E#6` | `REWRITE` | 旧实现有 native dependency、history scope、批准队列、256 位置窗口；S1 只保留单会话小范围重发。旧大范围批准属 S3，OMIT。 | FULL | |
| `W5CABH0H2PAJ2TAG` | 发送方在长时间未获确认时兜底重发 | `send_retry.qnt` 3；`send_retry.rs` 13；`core/tests/fallback_resend.rs` 10；`e2e/manual-maintenance` 1 | Quint + Rust + E2E | `Q3`；`SM-send-retry`；`E2E#6` | `REWRITE` | 旧重试 job 绑定 native copy queue、历史传输与吊销；S1 只保留固定截止、有界重试、重启后仍待发。 | FULL | |
| `Y7C40JC6A0EWEDT5` | 无法完成重建时才告知用户 | `rebuild.qnt`（`userEventuallyInformed`、`noSelfInducedRebuild`）；`e2e/rebuild` 7（超过上限/暂停才提示） | Quint + E2E | `SM-rebuild`；`E2E#14` | `PORT` | 状态展示不是错误弹窗；重建失败才告知。 | FULL | |
| `DEM5TY7V4XDCPWGJ` | 通道重建的频率须有上限 | `rebuild.qnt` 5（`rebuildsBoundedPerWindow`、`quotaBounded`、`fixedDeadline`）；`rebuild.rs` 29；`e2e/rebuild` 7（窗内次数不超限） | Quint + Rust + E2E | `Q5`；`SM-rebuild-bound`；`E2E` 可选 | `PORT` | 单窗上限与固定截止保留；旧共享定时器/多 issuer 细节属群架构，OMIT。 | FULL | |
| `N2K56BGH73GX0YCB` | 已发送消息的状态可见，只到已接收为止 | `delivery.rs`（只两种状态、已收到只前进不后退、别人的确认不算数） | Rust | `SM-message-state`；E2E 状态断言 | `PORT` | 旧 E2E 没有逐状态 UI 断言，UI 层记 `GAP`；核心状态机断言保留。 | FULL | |
| `Y1C61561WBX7BTNE` | 对方离线不导致消息丢弃 | `delivery.rs`（全部离线停在已写入本地、七天后上线按序到达）；`e2e/late-arrival`、`manual-maintenance` 的离线路径；`resend.qnt` | Rust + E2E | `SM-store-retry`；`E2E#6` | `PORT` | S1 的离线窗口从旧七天改为固定有界上限；断言是“已接纳消息不因离线丢弃”。 | FULL | |

### F. Relay、发现与隐私

| ID | 需求 | 旧测试/场景 | 层 | 新测试计划 | 状态 | 理由与省略 | 清点 | 人签字 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `QZGW3CN8BHBB0F6N` | 发现信息不发布直接地址 | 旧 `yochat-net` 125 个 test 属性（PKARR、mapped address、locator、budget）；`discovery_locator`、`mapped_address`、`pkarr_budget` 等模型；`e2e/diagnostics` 的直连/中转展示 | Rust + Quint + E2E | `UT-payload-no-address`；`E2E-relay-only-discovery` | `REWRITE` | S1 relay-only，没有旧 p2p locator/地址发布。旧 PKARR/映射地址测试 OMIT；新断言是邀请/联系人载荷不含直接地址，且界面不宣称直连。 | NOT YET | |

### G. 跨切面验收场景（额外行，不是新的 S1 需求）

| 场景 | 旧测试/场景 | 层 | 新测试计划 | 状态 | 理由与省略 | 清点 | 人签字 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 重启后消息仍在（契约 #4/#5） | `e2e/backend-restart` 1（两阶段真实进程重启）；`backend-restart-checkpoint` 1（PID/身份快照）；`identity-restart` 4（前端重启到解锁屏）；`journal_crash.rs` 2；`journal_faults.rs` 3；`state_roundtrip.rs` 5 | E2E + Rust | `E2E#4/#5`；`SM-crash-restart` | `PORT` | 旧 identity-restart 的恢复短语断言属 S3，OMIT；S1 只取“重新启动后仍锁定、解锁后旧消息仍在”。 | FULL | |
| 断开后重连，待发消息到达（契约 #6） | 无 S1 形状的专用场景；旧证据分散在 `late-arrival` 1（发送方离线）、`manual-maintenance` 1（离线 outbox）、`blocking-recovery` 2（提供方重连）、`admission-pressure` 1（重连延迟） | E2E | `Q4`；`E2E#6` | `GAP` | 旧测试覆盖迟到/重试/重连片段，但没有“S1 两台设备断网再重连，未确认消息继续”的完整场景。新场景不是等价移植，需签字。 | PARTIAL | |
| 篡改密文/签名/重放被拒（契约 #10/#11），单元/模型层 | `reply.rs` 2 个篡改 proptest；`envelope.rs` 12 + proptest；`message_order/tests.rs` 7；`live_authentication.rs` 3；`inbound_gate.rs` 10；19 个 Kani harness；`authenticity.qnt`、`shared_prekeys.qnt`、`buffered_decryption.qnt` | Rust prop + Quint + Kani | `VEC-tamper`；`SM-crypto-reject`；`E2E#10/#11` | `PORT` | 属性与向量层可移植；Kani 层无 Scala 等价，记 `GAP` 并用属性测试补足。 | FULL | |
| 篡改密文/签名/重放被拒（契约 #10/#11），E2E 层 | 无旧 E2E 场景 | E2E | `E2E#10/#11` | `GAP` | 旧项目没有从真实 UI/传输注入篡改密文或签名的端到端测试；必须新增。 | FULL | |
| 100 条有序无重复；1,000 条吞吐（契约 #7/#8） | 旧 `e2e/scale` 6（100,000 条历史）、`history-reading` 等；旧性能门不是 S1 拓扑 | E2E + perf | `E2E#7`；`E2E#8` + 性能回执 | `GAP` | 100k 属 S3；旧 scale 不是 S1 的两进程、1,000 条拓扑。T1 不把它当等价移植，性能门按 ADR-0004 单独出数值回执。 | PARTIAL | |
| 联系人删除/拉黑（原契约 #12） | `e2e/contact-removal` 3、`blocking` 3、`blocking-recovery` 2；S3 需求 `REQ_20260918_ZWMFKB381M0AKCYN`、`REQ_20260918_F7ZBKSBAC1N4KM2K`、`REQ_20260918_SD1XKGXS4DBG5ZTE` | E2E | 无 S1 计划 | `OMIT` | **不在 40 条 S1 需求里**。ADR-0003 已把删除/拉黑列为 S3；`verification.md` 已把契约 #12 改为日志/诊断内容边界。此 OMIT 待签字。 | FULL | |

## 3. 不可移植与 S1 范围外

### 3.1 不可移植或不能等价移植

| 对象 | 位置/数量 | 处理 |
| --- | --- | --- |
| Kani 有界证明 | 19 个 harness：`core/src/devices.rs` 6、`envelope/mod.rs` 4、`invite/mod.rs` 3、`proofs.rs` 4、`protocol/src/wire.rs` 2 | 没有 Scala/Kani 等价层。逐条登记为 `GAP`；用 Scala 属性测试覆盖可覆盖的输入域，剩余边界由人裁决。不得把属性测试写成“证明”。 |
| iroh/QUIC/PKARR 传输 | `yochat-net` 125 个 test 属性、45 个 `.rs`；`address_budget`、`mapped_address`、`network_deadline`、`relay_receive` 等模型 | S1 relay-only。旧 p2p 地址发布、打洞、QUIC 信用、映射地址测试 `OMIT`；只重写重连、有界队列、帧边界、relay 不可读等性质。 |
| 旧群棘轮/DCGKA 与 group/welcome | `membership_keys.qnt`、`shared_prekeys.qnt`、`exchange.qnt` 的 bundle/welcome 配置、`protocol/tests/crypto_commit.rs`、`dcgka_membership.rs` 等 | S1 用 ADR-0006 的两方 ratchet。旧实现内部断言不可移植；只保留一次性密钥、不可重放、篡改必拒、前向保密等性质。 |
| 旧 Tauri/Svelte 原生与 UI harness | `e2e/wdio.*`、`ui/tests` 32 个文件；macOS 通知点击、IME 手工观察 | 新 Electron/Laminar 下 harness 重写。场景与 DOM 断言意图可移植，组件 API、Tauri invoke、WDIO 服务不可复用。真实通知点击与 IME 仍是人工项，不是自动通过。 |
| 旧 trace 导出/回放 | 61 个 `export_*_traces.sh`；`journal_parity.rs` 等 | 导出格式绑旧 Rust 状态名与 JSON 字段。新回放器按 Scala `step` 重写；旧脚本不直接复用。 |
| 旧 `checks.json` 注册表 | 334 条配置、170 个模型 | 不是测试等价单位。S1 只对 5 个新模型登记安全/时序性质与源码指纹；旧注册表只作为性质来源。 |

### 3.2 S1 范围外（T1 不映射）

- S2（20 条）：多设备设备列表、新设备、历史同步、吊销传播、本人同步、常在线。旧 E2E 代表：`multi-device` 7、`self-sync` 14、`history-sync` 4、`user-key` 4、`own-enrollment` 1、`device-alias` 1、`device-alerts` 1、`always-on` 3、`contact-cold-boundary` 1、`contact-bootstrap` 1、`contact-takeover` 1。旧 contact-bootstrap 家族模型（13 个文件）与 `user_key_sync.qnt` 不移植。
- S3（35 条）：恢复短语、删除、搜索、引用、通知、拉黑、解除拉黑、隐私告知、十万条、恢复、多实例、陌生人预算等。旧 E2E 代表：`phrase-enrollment` 1、`deletion` 5、`search-and-delete` 7、`quote` 6、`notifications` 5、`notifications-system` 4、`blocking` 3、`contact-removal` 3、`private-contacts` 5、`profile` 2、`instances` 3、`scale` 6。恢复短语虽然出现在旧 `identity`/`identity-restart` 场景中，S1 断言必须删掉它。
- D（23 条）：群聊 8 + 文件传输 15；旧测试与模型不移植。
- 旧 `scale`（100,000 条）与 `history-reading` 不是 S1 性能门；S1 只测 1,000 条 `<5s`。

## 4. 先移植什么、人要决定什么

### 4.1 第一批（按依赖顺序）

1. **准入与身份前置**：`ZTSE`、`B9Y4`、`YQW2`、`4DYMP`、`R1J6`、`B9Y4` 的锁边界；对应 `ST-store-at-rest`、`SM-app-lock`、`UT-keyfile`、`E2E#13`。没有本地锁与静态加密，后面的邀请和消息都不成立。
2. **邀请/应答全组**：`1VXJ`、`2276`、`37F0`、`GXF8`、`YP7H`、`6J1E`、`HYREM`、`KWW8`、`A1MZ`、`FXAW`、`WEXA`；对应 `Q1`、`VEC-reply-*`、`SM-relationship`、`E2E#1/#2/#9/#11`。这是关系与消息准入的唯一入口。
3. **消息路径**：`JW13`、`RYGF`、`MXHY`、`DCNS`，然后是 `YACX`、`537B`、`BXC7`、`S02F`、`T6HD`、`XMX8`、`C2TD`、`DK8P`、`R5JV`；对应 `Q2`、`SM-order`、`ST-store-failure`、`E2E#3/#7/#10`。先落盘、再发送、按作者顺序、去重。
4. **生命周期**：`2MTC`、`VBRZ`、`W5CA`、`Y7C4`、`DEM5`、`N2K5`、`Y1C6`；对应 `Q3/Q5`、`SM-resend`、`SM-rebuild`、`E2E#6/#14`。重启/重连、等待/重发、重建限频放在同一批，因为共用持久待发表与截止。
5. **最后补跨切面**：`E2E#4/#5` 真实进程重启、`E2E#6` 断网重连、`E2E#10/#11` 篡改/重放、`E2E#8` 1,000 条性能回执。

不建议先做 UI 全量或旧 E2E 全量；那会把旧 Svelte/Tauri 结构焊到新栈上。

### 4.2 必须由人裁决

1. **Quint 层缺口**：设计只冻结 5 个协议模型；旧 `identity_creation.qnt`（2 配置）与 `idle_autolock.qnt`（1 配置）属于 KEEP 功能但没有模型槽位。是接受“Scala 属性等价”并签字，还是把模型预算加到 7 个？
2. **REWRITE 的等价标准**：`DCNS`、`2MTC`、`VBRZ`、`W5CA`、`P7X1`、`QZGW` 的旧测试绑定旧群棘轮/p2p/重试 job。需要逐条确认“性质等价、实现不同”可接受，还是要求更强的场景等价。
3. **契约 #12 与 S1 范围冲突**：已按 40 条 S1 清单把联系人删除/拉黑移出 S1，`verification.md` 的 #12 改为日志/诊断内容边界；`test-parity.md` 对应行改为 `OMIT`。此范围裁决待签字。
4. **Kani 缺口**：19 个 harness 无 Scala 等价。逐条决定用哪类属性测试补足，哪些边界接受未证明；不得静默写成“已覆盖”。
5. **等待活性口径**：`BXC7` 旧模型 `waitEventuallyEnds` 依赖 `weakFair`；新验证规则要求显式公平性前提并配安全对偶。确认“自行结束”是公平调度下的超时结束，还是要求无条件结束。
6. **逐测试登记方式**：本文按 40 条需求映射，`FULL` 行已到文件/场景级，`PARTIAL`/`NOT YET` 行尚未逐测试登记。确认实现期按 ADR-0004 追加逐测试行，并在合入前做到无未签字的 `OMIT`/`REWRITE`。
7. **恢复短语与多设备场景的删除**：旧 `identity`、`identity-restart`、`user-key`、`backend-restart` 里混有 S3 恢复短语与 S2 多设备断言。确认 S1 E2E 删除这些断言且不把它们算作回归。
8. **计数基线**：旧计划回执 1119/251/430 与当前静态扫描（Core 809、Protocol 778、E2E 193、UI 313）不是同一口径。确认 T1 以当前检出静态清单为映射基线，实际运行数以回执为准。


## T1 per-test packet (2026-11-24)

- 逐测试清单：`test-parity-tests.csv`（2774 行静态声明；字段含 requirement、proposal、新证据、decision、signed_by、signed_at）。
- 批量签字：`test-parity-batches.csv`（58 批，覆盖 629 条 S1 候选；不必逐行手工签）。
- 签字说明：`test-parity-signoff.md`。
- 生成器：`tools/t1_inventory_gen.py`（默认读 `~/projects/yochat-reference`，可用 `YOCHAT_REFERENCE` 覆盖）。
- 校验器：`tools/t1_signoff_check.py`；未完成签字时列出 629 个 S1 候选中的缺失 decision 并退出 1。
- 清点：S1 候选 629（Rust 418、UI 159、E2E 52）；全表另有 19 个 Kani harness（`GAP_CANDIDATE`）和 2126 个 `OMIT_CANDIDATE`。
- 未关闭：40 条需求中 5 个 `PARTIAL`（JW13、DCNS、RYGF、P7X1、MXHY）与 1 个 `NOT YET`（QZGW）；这些需求行必须先升级或明确改判，不能只签逐测试 CSV。
- Quint 的 170 行映射仍以 `test-parity-quint.md` 为准；本 CSV 不重复 Quint 模型。
- 新测试（E2E#8/#17、PERF#10K、UI smoke、桌面门）不是旧测试等价物；它们在签字材料中作为 GAP/新证据单独说明，不冒充旧断言移植。
