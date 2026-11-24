# T1 异常清单（AI 初审）

- 状态：待人工裁决；不是签字，也不是 OMIT 批准。
- 基线：`51ac59b`
- 对象：58 个批次中的 39 个异常批次；其余 19 批见 `test-parity-batches-reviewed.csv` 的 `CLEAN_CANDIDATE`。
- 目标：先只审异常，不逐条看 629 行。

## 异常批次

| # | Batch | 旧文件 | 测试数 | 需求 | 为什么异常 / 建议 |
| --- | --- | --- | ---: | --- | --- |
| E01 | `T1B-001` | `crates/yochat-app/src/exchange.rs` | 11 | `1VXJQ68HXHM0JR0Q,YP7HYFDC5BSKK1BP` | app/exchange 混入 device/blocking/revocation；需拆 S1 关系用例。 |
| E02 | `T1B-002` | `crates/yochat-app/src/instances/tests.rs` | 13 | `S02F3SXHZY2DHA87` | S2 多实例/接管；被错误映射到去重需求，应移出 S1 候选。 |
| E03 | `T1B-003` | `crates/yochat-app/src/profile_sync/tests.rs` | 8 | `S02F3SXHZY2DHA87` | S2 profile/history 同步；与去重需求无关。 |
| E04 | `T1B-004` | `crates/yochat-app/src/resend.rs` | 1 | `VBRZ8N5AC8QR0N3B` | app/resend 历史 fixture/配额；旧历史与 native 路径。 |
| E05 | `T1B-005` | `crates/yochat-core/src/message_order/tests.rs` | 7 | `S02F3SXHZY2DHA87` | message_order/order_proof 是 S2 跨作者全序；当前映射到去重需求不成立。 |
| E06 | `T1B-006` | `crates/yochat-core/src/order_proof/tests.rs` | 11 | `S02F3SXHZY2DHA87` | order_proof 签名/证书全序；S2 架构。 |
| E07 | `T1B-007` | `crates/yochat-core/src/store/message_order/tests.rs` | 9 | `S02F3SXHZY2DHA87` | store/message_order 前沿/证明持久化；S2 架构。 |
| E08 | `T1B-008` | `crates/yochat-core/src/store/order_proof/tests.rs` | 37 | `S02F3SXHZY2DHA87` | store/order_proof 37 条；S2 全序证明，不是 S1 去重。 |
| E09 | `T1B-009` | `crates/yochat-core/src/store/retransmit_dispatch.rs` | 2 | `VBRZ8N5AC8QR0N3B` | 旧 retransmit dispatch 与 native/历史批准路径；不是 S1 单会话重发。 |
| E10 | `T1B-010` | `crates/yochat-core/tests/admission.rs` | 10 | `RYGFPP529TDA9R6X,MXHYRG96W0VKB031` | admission 混入拉黑/删除记录。 |
| E11 | `T1B-013` | `crates/yochat-core/tests/exchange.rs` | 18 | `1VXJQ68HXHM0JR0Q,YP7HYFDC5BSKK1BP` | core/exchange 混入不定向邀请/撤销设备。 |
| E12 | `T1B-015` | `crates/yochat-core/tests/inbound_discard.rs` | 10 | `MXHYRG96W0VKB031` | inbound_discard 混入设备列表/撤销。 |
| E13 | `T1B-018` | `crates/yochat-core/tests/keyfile.rs` | 7 | `4DYMP7NVJDKNPTKR` | keyfile 混入 24 词恢复短语 S3。 |
| E14 | `T1B-020` | `crates/yochat-core/tests/relationship.rs` | 10 | `1VXJQ68HXHM0JR0Q` | relationship 混入删除/拉黑。 |
| E15 | `T1B-021` | `crates/yochat-core/tests/reply.rs` | 16 | `GXF8WYYMWT40CFSM,37F049FH3B007X48` | reply 混入拉黑后拒绝。 |
| E16 | `T1B-022` | `crates/yochat-core/tests/retransmit_dispatch.rs` | 5 | `VBRZ8N5AC8QR0N3B` | store/retransmit_dispatch 持久化批次/native 审批；S3 范围。 |
| E17 | `T1B-024` | `crates/yochat-core/tests/state_roundtrip.rs` | 6 | `C2TDVVB9MGCY30KM` | state_roundtrip 混入 resend bookkeeping/配额/容差，不能整批当 S1。 |
| E18 | `T1B-026` | `crates/yochat-net/src/admission.rs` | 7 | `RYGFPP529TDA9R6X,MXHYRG96W0VKB031` | 旧 net/admission 是 p2p 速率/握手预算；S3 传输范围。 |
| E19 | `T1B-027` | `crates/yochat-protocol/src/bootstrap/tests.rs` | 12 | `S02F3SXHZY2DHA87` | 旧 bootstrap/tests 是联系人发现/接管；S2/S3。 |
| E20 | `T1B-028` | `crates/yochat-protocol/src/ordering.rs` | 2 | `S02F3SXHZY2DHA87` | 旧 protocol/ordering 是群控制依赖；S1 单聊不适用。 |
| E21 | `T1B-029` | `crates/yochat-protocol/tests/delivery.rs` | 19 | `JW13382752N6T1XR,DCNS38550F4AY1K5,N2K56BGH73GX0YCB` | delivery 19 条混合多设备/离线/群语义。 |
| E22 | `T1B-030` | `crates/yochat-protocol/tests/inbound_gate.rs` | 10 | `RYGFPP529TDA9R6X,MXHYRG96W0VKB031` | inbound_gate 混入 native dependency/私密联系人。 |
| E23 | `T1B-033` | `crates/yochat-protocol/tests/journal_parity.rs` | 1 | `C2TDVVB9MGCY30KM` | journal parity 对旧存储/内存参考模型；需确认不是 S2 全序夹具。 |
| E24 | `T1B-034` | `crates/yochat-protocol/tests/ordering.rs` | 13 | `S02F3SXHZY2DHA87` | 旧 protocol/ordering 建组/成员变更规则；S2。 |
| E25 | `T1B-035` | `crates/yochat-protocol/tests/presentation_order.rs` | 3 | `JW13382752N6T1XR` | presentation_order 因果范围/删除/历史坐标；S2。 |
| E26 | `T1B-036` | `crates/yochat-protocol/tests/rebuild.rs` | 29 | `2MTCJFK2TX0E9ACY,Y7C40JC6A0EWEDT5` | protocol/rebuild 29 条混合自动重建与旧群/暂停路径；需拆。 |
| E27 | `T1B-037` | `crates/yochat-protocol/tests/resend.rs` | 52 | `VBRZ8N5AC8QR0N3B` | protocol/resend 52 条混合 native dependency/history/gone；需拆。 |
| E28 | `T1B-038` | `crates/yochat-protocol/tests/send_retry.rs` | 13 | `VBRZ8N5AC8QR0N3B` | protocol/send_retry 13 条混合旧 SQL/native/撤销路径；需拆。 |
| E29 | `T1B-042` | `e2e/specs/diagnostics.e2e.ts` | 4 | `DVFN7QEAWP32QD9G` | diagnostics 4 条混合直连/中转展示 S3。 |
| E30 | `T1B-043` | `e2e/specs/exchange.e2e.ts` | 12 | `1VXJQ68HXHM0JR0Q` | exchange.e2e 12 条混合隐私/渠道复用 S3。 |
| E31 | `T1B-044` | `e2e/specs/identity-restart.e2e.ts` | 4 | `4DYMP7NVJDKNPTKR` | identity-restart 混合 24 词恢复短语 S3。 |
| E32 | `T1B-045` | `e2e/specs/identity.e2e.ts` | 6 | `4DYMP7NVJDKNPTKR` | identity 混合恢复短语 S3。 |
| E33 | `T1B-047` | `e2e/specs/invite.e2e.ts` | 7 | `2276A5JCK1KGNS1A` | invite 混合渠道身份复用 S3。 |
| E34 | `T1B-051` | `e2e/specs/rebuild.e2e.ts` | 7 | `2MTCJFK2TX0E9ACY` | rebuild.e2e 混合暂停/人工维护 S3。 |
| E35 | `T1B-052` | `e2e/specs/resend.e2e.ts` | 5 | `VBRZ8N5AC8QR0N3B` | resend.e2e 混合 native 依赖/内容已删 S2/S3。 |
| E36 | `T1B-053` | `ui/tests/chat-flow.test.ts` | 89 | `JW13382752N6T1XR,N2K56BGH73GX0YCB` | chat-flow 89 条混合搜索/历史/删除/重连等 S2/S3 UI 用例。 |
| E37 | `T1B-054` | `ui/tests/exchange-flow.test.ts` | 20 | `1VXJQ68HXHM0JR0Q,YP7HYFDC5BSKK1BP` | exchange-flow 20 条混合删除/拉黑/重交换等 S3 用例。 |
| E38 | `T1B-055` | `ui/tests/identity-flow.test.ts` | 22 | `4DYMP7NVJDKNPTKR,B9Y4E73VMQ8RS99X` | identity-flow 22 条混合恢复短语 S3 用例。 |
| E39 | `T1B-058` | `ui/tests/invite-flow.test.ts` | 13 | `2276A5JCK1KGNS1A,37F049FH3B007X48` | invite-flow 13 条混合渠道身份保存等 S3 用例。 |

## 需求级异常（字前先裁决）

| 需求 | 现状 | 需要 |
| --- | --- | --- |
| `JW13382752N6T1XR` 发送与接收一条消息 | `PARTIAL` | 确认 `E2E#3`、message lifecycle、UI 双向证据是否覆盖旧断言的剩余分支 |
| `DCNS38550F4AY1K5` 消息端到端加密 | `PARTIAL` | 确认新向量/状态机是否足以替代旧 DCGKA 断言 |
| `RYGFPP529TDA9R6X` 不与未建立关系的对端交换消息 | `PARTIAL` | 确认核心 admission 与 E2E#9/GAP 是否接受 |
| `P7X1QGZCYA41GAQT` 内容与参与者对中转设施不可读 | `PARTIAL` | 确认 relay-side ciphertext 测试证据 |
| `MXHYRG96W0VKB031` 对未获授权请求不产生响应 | `PARTIAL` | 确认 `SM-inbound-gate` + E2E 捕获 |
| `QZGW3CN8BHBB0F6N` 发现信息不发布直接地址 | `NOT YET` | 仍需补新的 relay-only payload/address 断言 |

## 建议处理方式

1. **SCOPE/架构批**：先决定 `ACCEPT_OMIT` 或重新映射；不要在不改需求行的情况下直接签 `ACCEPT_OMIT`。
2. **MIXED 批**：按测试名拆成 S1 子批与非 S1 子批；拆完再逐子批签。不能按整文件 OMIT。
3. **CLEAN_CANDIDATE**：`test-parity-batches-reviewed.csv` 中的 19 批可由人工快速复核；AI 建议 `PORT/REWRITE` 已给出。
4. **PARTIAL/NOT YET**：先办 6 个需求级裁决，再签引用它们的批次。

## 签字/应用

异常批全部拆清并生成新批次后，才应在 `test-parity-batches.csv` 填 `batch_decision/signed_by/signed_at`，再运行：

```sh
python3 tools/t1_apply_batches.py --apply
python3 tools/t1_signoff_check.py
```
