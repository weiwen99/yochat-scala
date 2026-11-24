# T1 逐测试清单与签字材料

- 状态：**已总签**（weiwen99，2026-11-24）；T1 决策已写入逐测试 CSV。
- 日期：2026-11-24
- 新实现基线：`f7df53d`
- 旧实现基线：`/Users/weiwen/projects/yochat-reference`（只读；静态扫描）
- 逐测试文件：`test-parity-tests.csv`（2,774 行；629 条 S1 候选）
- 批量签字文件：`test-parity-split-groups.csv`（**26 个签字组**；不需要逐条签 629 次）
- AI 初审：`test-parity-batches-reviewed.csv`（19 个 clean 候选、39 个异常待裁决）
- 异常清单：`test-parity-exceptions.md`（先审这一份，不要直接签原始 58 批）
- 自动拆分结果：`test-parity-split-report.md`（81 个 split 子批 → 26 个签字组）
- 拆分组签字器：`tools/t1_apply_split.py`
- 生成器：`tools/t1_inventory_gen.py`
- 拆分组应用器：`tools/t1_apply_split.py`
- 自动初判：`test-parity-split-groups-reviewed.csv`
- 仅需人工决定：`test-parity-human-decisions.md`（5 组 + 6 需求；用户已接受）
- 总签材料：`test-parity-final-signoff.md`、`test-parity-final-manifest.json`（root `ef2bad18eb6161970ceaf76361b9728fdf48a960c8d267f359a5c41e56ac621a`）
- 校验器：`tools/t1_signoff_check.py`

## 1. 清点口径

本次从旧检出的 test 声明静态提取，未运行旧测试：

| 层 | 声明数 |
| --- | ---: |
| Rust test/proptest | 2214 |
| Kani harness | 19 |
| UI test | 313 |
| E2E test | 193 |
| NativeCase | 35 |
| **合计** | **2774** |

与旧需求映射关联的 S1 候选行 **629** 条：

| 层 | S1 候选 |
| --- | ---: |
| Rust | 418 |
| UI | 159 |
| E2E | 52 |
| **合计** | **629** |

S1 候选的初始裁决建议：

| 建议 | 行数 |
| --- | ---: |
| `PORT_CANDIDATE` | 422 |
| `REWRITE_CANDIDATE` | 207 |
| `GAP_CANDIDATE` | 0 |

其余 **2145** 条是 `OMIT_CANDIDATE` 或独立的 Kani 缺口：文件/测试位于旧 S2/S3/D、p2p/history/group/revocation，或尚未被 S1 需求映射命中。这个数字不是已批准的 OMIT 数；必须逐行填写 `decision`。

全表建议分布：`{'OMIT_CANDIDATE': 2126, 'PORT_CANDIDATE': 422, 'REWRITE_CANDIDATE': 207, 'GAP_CANDIDATE': 19}`。

## 2. AI 初审结果与顺序

AI 初审把 58 批分成 19 个 `CLEAN_CANDIDATE` 和 39 个 `EXCEPTION`。异常批主要来自三类：

- 错误映射到 S1：旧 S2/S3 的 instances、profile_sync、order_proof、bootstrap、protocol ordering、net admission。
- 混合文件：同一文件同时包含 S1 与多设备/历史/删除/拉黑/恢复短语/旧 native 路径。
- 旧架构大批次：resend/rebuild/send_retry/UI chat-flow 等，需拆分后签。

先按 `test-parity-exceptions.md` 解决 39 个异常批次和 6 个需求行；不要把异常批直接填成 `ACCEPT_OMIT` 或 `ACCEPT_REWRITE`。19 个 clean 候选仍需人工批次签字。

## 3. 批量签字（推荐）

629 条 S1 候选先拆为 81 个测试子批，再归并为 **26 个签字组**：

| 文件 | 组数 | 覆盖 S1 行 |
| --- | ---: | ---: |
| `test-parity-split-groups.csv` | 26（25 S1 + 1 OMIT） | 629 |
| `test-parity-split.csv` | 81 个 split 子批 | 629 |
| `test-parity-split-membership.csv` | 629 | 629 |

每组记录 `row_ids_sha256`，固定该组包含的子批及测试行；组内测试不可能在不改哈希的情况下被静默增删。审阅时先看 `test-parity-split-report.md` 和异常清单，填写组的 `decision, signed_by, signed_at`；然后运行：

```sh
python3 tools/t1_apply_split.py --bulk-sign "姓名" "2026-11-24" --apply
python3 tools/t1_signoff_check.py
```

应用器只从已完整签名的组展开到逐测试行；不会覆盖已有的冲突行决策。这样人工签字约 **26 个组 + 6 个需求行裁决**，而不是 629 行。`test-parity-batches.csv` 是拆分前输入，不再直接作为签字单位。

## 4. CSV 字段与签字规则

CSV 字段：

`row_id, layer, source, test, requirement_ids, requirement_disposition, requirement_coverage, proposed_disposition, new_evidence, reason, decision, signed_by, signed_at`

签字值只允许：

| 值 | 含义 |
| --- | --- |
| `ACCEPT_PORT` | 旧断言按同语义移植到 `new_evidence` |
| `ACCEPT_REWRITE` | 需求保留，旧实现绑定被删除；按新架构重写 |
| `ACCEPT_GAP` | 旧层无等价测试，接受新增测试补足 |
| `ACCEPT_OMIT` | 确认该旧测试不属于 S1，或只测已删除实现细节 |
| `REJECT` | 拒绝该建议，必须在 `reason` 写原因 |

`decision`、`signed_by`、`signed_at` 三者必须同时填写。允许按 `source` 批量审阅，但批量签字必须逐行写入同一批 `signed_by/signed_at`，不得用“整文件默认”代替行记录。

## 5. 需要先裁决的 40 条需求行

当前 `test-parity.md` 中仍有以下 `PARTIAL`/`NOT YET`：

| 需求 ID | 需求 | 旧层到新层的缺口 |
| --- | --- | --- |
| `JW13382752N6T1XR` | 发送与接收一条消息 | 现为 `PARTIAL`；新测试计划：E2E#3`；`SM-message-lifecycle |
| `DCNS38550F4AY1K5` | 消息端到端加密 | 现为 `PARTIAL`；新测试计划：VEC-aead`；`SM-crypto-state`；`E2E#10 |
| `RYGFPP529TDA9R6X` | 不与未建立关系的对端交换消息 | 现为 `PARTIAL`；新测试计划：SM-admission`；`E2E#9`（错误邀请）；无关对端 E2E 记 `GAP |
| `P7X1QGZCYA41GAQT` | 内容与参与者对中转设施不可读 | 现为 `PARTIAL`；新测试计划：relay-sees-ciphertext-only` 集成测试（relay 进程侧收到帧断言）；E2E 只验用户路径 |
| `MXHYRG96W0VKB031` | 对未获授权的请求不产生响应 | 现为 `PARTIAL`；新测试计划：SM-inbound-gate`（断言无响应效果）；E2E 捕获测试 |
| `QZGW3CN8BHBB0F6N` | 发现信息不发布直接地址 | 现为 `NOT YET`；新测试计划：UT-payload-no-address`；`E2E-relay-only-discovery |

这些行必须在签字前升级为 `FULL` 或明确改判；逐测试 CSV 不替代需求级裁决。

## 6. 已知范围裁决

- 联系人删除/拉黑、group/welcome、多设备/历史/吊销、p2p/iroh/QUIC、旧恢复短语、100k history/scale 属于 S2/S3/D；对应旧测试只能 `ACCEPT_OMIT`，且逐行签字。
- ADR-0004 要求：旧测试的断言/场景等价，不要求旧框架或旧组件 API。
- Kani 无 Scala 等价；只可 `ACCEPT_GAP` 并由现有属性测试补足，不能声称证明了实现。
- Quint 的 170 行映射另见 `test-parity-quint.md`（已有用户签字）；本 CSV 不重复 Quint 模型行。
- 当前 E2E/UI/性能回执见 `docs/plans/2026/11/0010-s1-ui-automation/acceptance.md`；T1 是测试等价签字，不是这些回执的替代品。

## 7. 签字块

T1 负责人：

- 姓名：________________
- 日期：________________
- 确认：逐行 CSV 已审阅；没有未解释的 `REJECT`、未签 `ACCEPT_OMIT` 或未决 `PARTIAL/NOT YET`。
- 签字：________________

产品/安全评审：

- 姓名：________________
- 日期：________________
- 确认：S1 范围裁决（S2/S3/D、Kani gap、旧架构重写）可接受；发布仍受外部密码学评审约束。
- 签字：________________

## 8. 机械校验

批量签字后运行 `tools/t1_apply_batches.py --apply`，再运行：

```sh
python3 tools/t1_signoff_check.py
```

未完成时该命令会列出缺失/非法/未签行并退出 1；完成后输出 `T1_SIGNOFF PASS`。
