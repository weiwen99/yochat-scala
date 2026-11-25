# Plan 0010 implementation

- Status: web UI path implemented
- Date: 2026-11-23
- Branch: `feat/plan-0010-s1-ui-automation`
- Base: `fe9e126`

## Changes

- `shell/ui-smoke.js`: two real browser contexts, two server JVMs, one relay; directed invitation, safety gate before send, and 100 messages in each direction through the rendered UI.
- `shell/electron-smoke.js`: two real Electron windows, 100 messages each way, both windows closed/reopened on the same user-data directories, and post-restart messages delivered.
- `shell/cross-host-smoke.js`: m1 and w7 each run one server/database; browser A runs on m1, browser B on w7 through a Chromium CDP SSH tunnel; 100 messages each way, relay outage/reconnect, and B/A server restarts.
- `shell/package.json`: Playwright dev dependency, `ui:smoke`, and `ui:electron`.
- `check.sh`: builds both assemblies, runs the web UI smoke, then the Electron-window smoke.

## Verification

- `UI_SMOKE PASS delivered=100 received=100 reciprocal=1 duration_ms=15884`
- `ELECTRON_UI PASS delivered=100 received=100 restarted=B,A duration_ms=26699`
- Receipts under `tmp/checks/` (ignored by git).
- The web test uses installed Chrome via Playwright; the Electron test launches the real shell.
