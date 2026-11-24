# T1 逐测试清单与签字材料

- 状态：**待人工签字**；本文和 CSV 不是测试通过回执。
- 日期：2026-11-24
- 新实现基线：`f7df53d`
- 旧实现基线：`/Users/weiwen/projects/yochat-reference`（只读；静态扫描）
- 主文件：`test-parity-tests.csv`（逐测试）
- 生成器：`tools/t1_inventory_gen.py`
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

## 2. CSV 字段与签字规则

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

## 3. 需要先裁决的 40 条需求行

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

## 4. 已知范围裁决

- 联系人删除/拉黑、group/welcome、多设备/历史/吊销、p2p/iroh/QUIC、旧恢复短语、100k history/scale 属于 S2/S3/D；对应旧测试只能 `ACCEPT_OMIT`，且逐行签字。
- ADR-0004 要求：旧测试的断言/场景等价，不要求旧框架或旧组件 API。
- Kani 无 Scala 等价；只可 `ACCEPT_GAP` 并由现有属性测试补足，不能声称证明了实现。
- Quint 的 170 行映射另见 `test-parity-quint.md`（已有用户签字）；本 CSV 不重复 Quint 模型行。
- 当前 E2E/UI/性能回执见 `docs/plans/2026/11/0010-s1-ui-automation/acceptance.md`；T1 是测试等价签字，不是这些回执的替代品。

## 5. 签字块

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

## 6. 机械校验

签字完成后运行：

```sh
python3 tools/t1_signoff_check.py
```

未完成时该命令会列出缺失/非法/未签行并退出 1；完成后输出 `T1_SIGNOFF PASS`。
