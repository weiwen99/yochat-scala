# Plan 0010 implementation

- Status: web UI path implemented
- Date: 2026-11-23
- Branch: `feat/plan-0010-s1-ui-automation`
- Base: `fe9e126`

## Changes

- `shell/ui-smoke.js`: two real browser contexts, two server JVMs, one relay; directed invitation, safety gate before send, and 100 messages in each direction through the rendered UI.
- `shell/electron-smoke.js`: two real Electron windows, 100 messages each way, both windows closed/reopened on the same user-data directories, and post-restart messages delivered.
- `shell/cross-host-smoke.js`: m1 and w7 each run one server/database; browser A runs on m1, browser B on w7 through a Chromium CDP SSH tunnel; 100 messages each way, relay outage/reconnect, and B/A server restarts. After the UI/restart phase it runs a bounded A-to-B `CROSS_HOST_RATE` phase (default 1,000 measured + 200 warmup, 128-byte bodies, 64 in flight, 128 backlog), using local SQLite for A `Delivered` and remote Python SQLite for B `Received`.
- `shell/ui-smoke.js`: exports the tracked child-process list so the cross-host script can stop local relay/server JVMs that were started through the shared helper.
- `shell/package.json`: Playwright dev dependency, `ui:smoke`, and `ui:electron`.
- `check.sh`: builds both assemblies, runs the web UI smoke, then the Electron-window smoke.

## Verification

- `UI_SMOKE PASS delivered=100 received=100 reciprocal=1 duration_ms=15884`
- `ELECTRON_UI PASS delivered=100 received=100 restarted=B,A duration_ms=26699`
- `CROSS_HOST_RATE` 1,000-message run: `ack_ms=23591 rate_msg_s=42.4 bCount=1302 aDelivered=1302` on m1/w7.
- `CROSS_HOST_RATE` 5,000-message run: `ack_ms=229590 rate_msg_s=21.8 bCount=5302 aDelivered=5302` on m1/w7.
- Receipts under `tmp/checks/` (ignored by git). Cross-host rate is observational and has no pass threshold; the 5k sample is the sustained figure under the then-current host load.
- The web test uses installed Chrome via Playwright; the Electron test launches the real shell.
