# T1 人工决定清单（自动初判后）

- 状态：只剩以下 **11 项**需要人工决定；其余 21 个 S1 组 AI 初判通过，仍需最终总签。
- 基线：`beefb6f`
- 自动初判文件：`test-parity-split-groups-reviewed.csv`
- 26 组中 `human_required=yes` 的只有 5 组；另外 6 项是需求级裁决。

## A. 组级决定（5）

| Group | 测试数 | 需求 | AI 建议 | 要你决定 |
| --- | ---: | --- | --- | --- |
| `T1G-001` | 234 | non-S1 | `ACCEPT_OMIT` 候选 | 是否接受 234 条旧 S2/S3/legacy 测试整体 OMIT？如不接受，指出要拆回 S1 的子批。 |
| `T1G-020` | 17 | JW13, DCNS, N2K | `ACCEPT_REWRITE` 候选 | JW13/DCNS 仍为 PARTIAL；是否接受 17 条按两方 ratchet 重写，并接受现有向量/E2E#10 作为证据？ |
| `T1G-021` | 71 | JW13, N2K | `ACCEPT_PORT` 候选 | JW13 仍为 PARTIAL；71 条 chat-flow 是否按 PORT 接受，还是要求补 UI/传输断言？ |
| `T1G-022` | 4 | MXHY | `ACCEPT_PORT` 候选 | MXHY 仍为 PARTIAL；4 条无响应测试是否按 PORT 接受？ |
| `T1G-023` | 16 | RYGF, MXHY | `ACCEPT_PORT` 候选 | RYGF/MXHY 仍为 PARTIAL；16 条 admission 测试是否按 PORT 接受？ |

## B. 需求级决定（6）

| 需求 | 现状 | 要你决定 |
| --- | --- | --- |
| `JW13382752N6T1XR` 发送与接收一条消息 | PARTIAL | 上述 T1G-021 是否足够，还是要求补测试？ |
| `DCNS38550F4AY1K5` 消息端到端加密 | PARTIAL | 上述 T1G-020 是否足够？ |
| `RYGFPP529TDA9R6X` 不与未建立关系的对端交换消息 | PARTIAL | 上述 T1G-023 是否足够？ |
| `P7X1QGZCYA41GAQT` 内容与参与者对中转设施不可读 | PARTIAL | 当前无 S1 split 组；是否接受 relay 只读密文测试/接受为 GAP？ |
| `MXHYRG96W0VKB031` 对未获授权请求不产生响应 | PARTIAL | 上述 T1G-022/023 是否足够？ |
| `QZGW3CN8BHBB0F6N` 发现信息不发布直接地址 | NOT YET | 仍需新 relay-only payload/address 断言；是否接受先补测试？ |

## 自动初判通过（21 组，不再逐项问你）

`T1G-002` 至 `T1G-019`、`T1G-024` 至 `T1G-026`：需求覆盖为 FULL，AI 建议 `ACCEPT_PORT/REWRITE`，详见 reviewed CSV。它们仍需最终总签，但不是待裁决项。

## 下一步

1. 你只回答 A 的 5 项和 B 的 6 项；可逐项写 `接受/拒绝`。
2. 我据此修改 5 个组和 6 个需求行；若接受，自动组与人工组一起进入总签 manifest。
3. 总签后再运行 `t1_apply_split.py --apply` 和 `t1_signoff_check.py`。
