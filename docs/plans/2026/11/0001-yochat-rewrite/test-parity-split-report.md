# T1 自动拆分结果

- 状态：AI 初审，未签字。
- 输入：`test-parity-batches.csv` 的 58 批。
- 输出：81 个测试子批，归并为 **26 个签字组**（25 个 S1 组 + 1 个非 S1 OMIT 组）。
- 覆盖：629 条 S1 候选全部有且只有一个 split 成员关系。
- 目的：人工签字从 629 行降到 **26 组 + 6 个需求裁决**；不是 629 次。

## 拆分统计

| 类别 | split 子批 | 测试数 | 签字组 |
| --- | ---: | ---: | ---: |
| clean S1 | 19 | 98 | 并入 S1 组 |
| mixed S1 | 24 | 297 | 并入 S1 组 |
| 非 S1/OMIT 候选 | 38 | 234 | 1 |
| **合计** | **81** | **629** | **26** |

## 签字组

| Group | 类型 | 测试数 | 建议 | 需求 |
| --- | --- | ---: | --- | --- |
| `T1G-001` | NON_S1_OMIT | 234 | ACCEPT_OMIT | `` |
| `T1G-002` | S1 | 20 | ACCEPT_PORT | `1VXJQ68HXHM0JR0Q` |
| `T1G-003` | S1 | 42 | ACCEPT_PORT | `1VXJQ68HXHM0JR0Q,YP7HYFDC5BSKK1BP` |
| `T1G-004` | S1 | 9 | ACCEPT_PORT | `2276A5JCK1KGNS1A` |
| `T1G-005` | S1 | 12 | ACCEPT_PORT | `2276A5JCK1KGNS1A,37F049FH3B007X48` |
| `T1G-006` | S1 | 19 | ACCEPT_PORT | `2276A5JCK1KGNS1A,6J1EMT8Y9HZ68STG` |
| `T1G-007` | S1 | 7 | ACCEPT_REWRITE | `2MTCJFK2TX0E9ACY` |
| `T1G-008` | S1 | 23 | ACCEPT_REWRITE | `2MTCJFK2TX0E9ACY,Y7C40JC6A0EWEDT5` |
| `T1G-009` | S1 | 10 | ACCEPT_REWRITE | `4DYMP7NVJDKNPTKR` |
| `T1G-010` | S1 | 9 | ACCEPT_REWRITE | `4DYMP7NVJDKNPTKR,B9Y4E73VMQ8RS99X` |
| `T1G-011` | S1 | 8 | ACCEPT_PORT | `6J1EMT8Y9HZ68STG` |
| `T1G-012` | S1 | 2 | ACCEPT_PORT | `865PCSHV46Q4X2XF` |
| `T1G-013` | S1 | 7 | ACCEPT_REWRITE | `B9Y4E73VMQ8RS99X,YQW27DXZJSHAR0Z0` |
| `T1G-014` | S1 | 6 | ACCEPT_PORT | `BXC7RNH3YCQZ9BQY` |
| `T1G-015` | S1 | 9 | ACCEPT_PORT | `BXC7RNH3YCQZ9BQY,S02F3SXHZY2DHA87` |
| `T1G-016` | S1 | 10 | ACCEPT_PORT | `C2TDVVB9MGCY30KM` |
| `T1G-017` | S1 | 1 | ACCEPT_PORT | `DVFN7QEAWP32QD9G` |
| `T1G-018` | S1 | 9 | ACCEPT_PORT | `FXAWEFJ6Q0S22E5S` |
| `T1G-019` | S1 | 14 | ACCEPT_PORT | `GXF8WYYMWT40CFSM,37F049FH3B007X48` |
| `T1G-020` | S1 | 17 | ACCEPT_REWRITE | `JW13382752N6T1XR,DCNS38550F4AY1K5,N2K56BGH73GX0YCB` |
| `T1G-021` | S1 | 71 | ACCEPT_PORT | `JW13382752N6T1XR,N2K56BGH73GX0YCB` |
| `T1G-022` | S1 | 4 | ACCEPT_PORT | `MXHYRG96W0VKB031` |
| `T1G-023` | S1 | 16 | ACCEPT_PORT | `RYGFPP529TDA9R6X,MXHYRG96W0VKB031` |
| `T1G-024` | S1 | 54 | ACCEPT_REWRITE | `VBRZ8N5AC8QR0N3B` |
| `T1G-025` | S1 | 13 | ACCEPT_REWRITE | `YQW27DXZJSHAR0Z0` |
| `T1G-026` | S1 | 3 | ACCEPT_REWRITE | `ZTSEXMZQ2VHS21R6` |

## 文件

- `test-parity-split.csv`：81 个 split 子批，含 row IDs 与 SHA-256。
- `test-parity-split-membership.csv`：629 条候选的 row -> split 子批映射。
- `test-parity-split-groups.csv`：26 个签字组；人工填写 `decision/signed_by/signed_at`。
- `tools/t1_apply_split.py`：校验组哈希、把组决定展开到 split 与逐测试行；未签时退出 1。

## 使用

```sh
python3 tools/t1_apply_split.py          # 未签时列出 26 个 pending groups
python3 tools/t1_apply_split.py --apply  # 26 组都签完后写入逐测试行
python3 tools/t1_signoff_check.py
```

## 自动初判结果

- 26 组中 21 组 `human_required=no`，AI 建议 `ACCEPT_PORT/REWRITE`。
- 5 组需要人工：`T1G-001`、`T1G-020`、`T1G-021`、`T1G-022`、`T1G-023`。
- 另加 6 个需求级裁决；完整清单见 `test-parity-human-decisions.md`。

## 仍需人工重点看

- `T1G-001` 非 S1 OMIT 组：234 条/38 个子批，按关键词自动分类；必须审阅 `test-parity-split.csv` 中 `NON_S1_CANDIDATE` 的样例行，接受后才能整体 OMIT。
- 5 个 `PARTIAL` + 1 个 `NOT YET` 需求行仍未裁决。
- S1 组内的 19 个 clean 候选和 24 个 mixed 子批仍要求人签；AI 没有代签。
- 自动关键词可能把 S1 测试误分到 OMIT；任何 `REJECT` 都应拆回 S1 组。
