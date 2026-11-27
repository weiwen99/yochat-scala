# Plan 0009 implementation

- Status: desktop gates implemented
- Date: 2026-11-23
- Branch: `feat/plan-0008-s1-perf-e2e`
- Base: `5317022`

## Changes

- `shell/main.js`: disabled-by-default `YOCHAT_START_METRIC` hook; waits for the loading veil and uses the parent spawn epoch.
- `shell/perf-start.js`: warmup + five cold-start samples on distinct ports, `--user-data-dir=<path>`, owned-JVM check, and 300s idle process-tree RSS receipt.
- `check.sh`: runs `server.e2e`, builds `server.assembly`, and executes `perf-start.js` as a release gate.
- `shell/package.json`: `perf:start` command.

## Receipt

- `COLD_START p50_ms=1215.000 p95_ms=1227.000 idle_mb=737.1 verdict=PASS`
- Receipt: `tmp/checks/desktop-1795443568497.json` (ignored by git).
- Measured on the same machine with the existing user-data database; first-run creation was excluded by a warmup launch.
- The ready boundary is `.loading-veil` removal after `did-finish-load`, not first paint.
- Idle sample is 300s after readiness; it is the Electron main/helper processes plus the owned JVM, excluding the relay.

## 后续：执行方式改为 desktop-gate.sh（2026-11-27）

`perf-start` 不再由裸 `node shell/perf-start.js` 执行，而是经 `./desktop-gate.sh`
注入 Aqua 会话运行：`caffeinate -dims`、`YOCHAT_GATE_TIMEOUT` 超时、进程组收尾、
并额外写一份 `tmp/checks/desktop-gate-<epoch>.json` 环境回执。冷启动与空闲内存的
口径、阈值、边界（`.loading-veil` 移除、300s 空闲采样）均未改变；门禁自身回执格式
不变。详见 `docs/plans/2026/11/0014-desktop-gate-session/`。
