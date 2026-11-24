# T1 总签材料

- 状态：**决策已由用户接受；签名尚未落盘**。
- 基线：`595f2f5`
- 决策清单：11 项人工决定已按用户指示接受；21 个 FULL 组由 AI 初判建议。
- 总 manifest：`test-parity-final-manifest.json`
- Manifest root：`ef2bad18eb6161970ceaf76361b9728fdf48a960c8d267f359a5c41e56ac621a`
- 逐测试记录：`test-parity-tests.csv`（629 条 S1 候选）

## 已接受的决策

- 组级：26 组 decisions 已写入 `test-parity-split-groups.csv`。
  - `T1G-001`：`ACCEPT_OMIT`
  - `T1G-020`：`ACCEPT_REWRITE`
  - `T1G-021`、`T1G-022`、`T1G-023`：`ACCEPT_PORT`
  - 其余 21 个 FULL 组：按 AI 建议 `ACCEPT_PORT/REWRITE`
- 需求级：
  - `JW13`、`DCNS`、`RYGF`、`MXHY`：接受现有证据/重写方案。
  - `P7X1`：接受为 **GAP**；没有 participants-unreadability 的独立新测试，保留 S1 relay 只见密文的范围说明。
  - `QZGW`：接受为 **GAP**；S1 没有 discovery/address payload；不把“无字段”冒充测试。

## 仍需的动作

1. 在下方签名块填写 T1 负责人和产品/安全评审。
2. 用签名者姓名/日期执行一次批量签名：

```sh
python3 tools/t1_apply_split.py --bulk-sign "姓名" "2026-11-24" --apply
python3 tools/t1_signoff_check.py
```

`--bulk-sign` 只会在 26 个组 decision 已全部填好且组哈希匹配时工作；它会把同一签名者/日期展开到 81 个子批和 629 条逐测试行。产品/安全评审的签字仍以本文签名块为准。

## 签名块

T1 负责人：

- 姓名：________________
- 日期：________________
- 我已审阅 26 个组、11 项人工决定、`P7X1/QZGW` 接受为 GAP；manifest root：`ef2bad18eb6161970ceaf76361b9728fdf48a960c8d267f359a5c41e56ac621a`
- 签字：________________

产品/安全评审：

- 姓名：________________
- 日期：________________
- 我接受 S2/S3/legacy 的 OMIT 范围、旧架构 REWRITE 和上述 GAP；外部密码学评审仍独立。
- 签字：________________
