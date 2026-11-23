# Plan 0009 design: desktop performance gates

- `YOCHAT_START_METRIC` makes the real shell wait for `.loading-veil` removal, write elapsed time, and quit; it is inactive unless set.
- `shell/perf-start.js` performs one warmup and five measured launches on distinct ports with the same isolated user-data directory, then samples the process tree after 300s established idle.
- Cold-start limits: p50 ≤2000ms, p95 ≤4000ms.
- Idle-memory limit: Electron + JVM process-tree RSS ≤800MB, excluding the relay.
- The receipt records samples, p50/p95, idle MB, limits, and PASS/FAIL; the start epoch is captured by the parent before spawn.
- No protocol, database, API, or user-visible behavior changes.
