# Plan 0009 implementation

- Status: desktop gates implemented
- Date: 2026-11-23
- Branch: `feat/plan-0008-s1-perf-e2e`
- Base: `5317022`

## Changes

- `shell/main.js`: disabled-by-default `YOCHAT_START_METRIC` hook.
- `shell/perf-start.js`: warmup + five cold-start samples + idle process-tree RSS receipt.
- `check.sh`: syntax-checks the new script.
- `shell/package.json`: `perf:start` command.

## Receipt

- `COLD_START p50_ms=1003.815 p95_ms=1034.079 idle_mb=719.3 verdict=PASS`
- Receipt: `tmp/checks/desktop-1795441004134.json` (ignored by git).
- Measured on the same machine with the existing user-data database; first-run creation was excluded by a warmup launch.
- `did-finish-load` is the ready boundary; this is not a first-paint or Electron-first-screen measurement.
- Idle sample is the Electron main/helper processes plus the spawned JVM, excluding the relay.
