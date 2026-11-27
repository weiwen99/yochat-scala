# Plan 0014: 桌面门禁在 GUI 会话中运行

- Status: implemented, verified on m1
- Branch: `fix/s-desktop-gui-session`, base `c415a07`
- Live spec: `.scratch/desktop-gate/spec.md`
- Owner: single session; no ADR (no protocol, storage, or product behaviour change)

## 为什么单独记一份

这次改动换了 0009（desktop 性能门）和 0010（UI 自动化）所依赖的**执行方式**：
门禁不再是裸 `node shell/xxx.js`，而是经 `desktop-gate.sh` 注入 Aqua 会话、
带超时与回执。两份计划的 `impl.md` 只描述了门禁本身，没有描述它们怎么被执行；
按 `docs/agents/plan-docs.md`，这种跨计划的影响要留 durable 记录。

## 范围

`shell/main.js` 的钥匙串 IPC、`shell/keychain-smoke.js`、`shell/passphrase-smoke.js`、
`desktop-gate.sh`、`check.sh` 的桌面段落、`docs/agents/verification.md`、
`.scratch/desktop-gate/spec.md`。

## 规模

见 `.scratch/desktop-gate/spec.md` 的规模表；需求 5 → 产出 7（1.4×），组件 0 → 2 个
新文件（均有解释），配置项 2 → 3，工程师日 0.5 → 1.5（估算，含三轮评审修复）。

## 已知约束

- 真正锁屏的分支未验证：解锁需要用户口令。
- `passphrase-load` 不区分「文件不存在」与「存在但不可读」。
- `passphrase-save` 把所有失败并成 `false`。
- 门禁自行 setsid 出去的后代不在组杀范围内。
- 性能门要求 8791–8797 端口空闲。
- `DEGRADED`（非 Aqua 且无免密 sudo）不运行门禁，直接 INCOMPLETE。
- 打包版（`app.isPackaged`）忽略 `YOCHAT_KEYCHAIN_UNAVAILABLE`；该行为读代码得出，未在打包版实跑。

## 影响

- 0009 的 `perf-start` 与 0010 的各门：执行入口改为 `desktop-gate.sh`，回执多一份
  `tmp/checks/desktop-gate-<epoch>.json`；门禁自身的回执格式不变。
- `check.sh` 新增 keychain 与两条 passphrase 门；`contrast-smoke` 改回直跑。
