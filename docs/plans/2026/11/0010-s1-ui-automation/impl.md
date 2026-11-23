# Plan 0010 implementation

- Status: web UI path implemented
- Date: 2026-11-23
- Branch: `feat/plan-0010-s1-ui-automation`
- Base: `fe9e126`

## Changes

- `shell/ui-smoke.js`: two real browser contexts, two server JVMs, one relay; directed invitation, safety gate before send, and 100 messages in each direction through the rendered UI.
- `shell/package.json`: Playwright dev dependency and `ui:smoke`.
- `check.sh`: builds both assemblies and runs the UI smoke.

## Verification

- `UI_SMOKE PASS delivered=100 received=100 reciprocal=1 duration_ms=15884`
- Receipt: `tmp/checks/ui-1795448744125.json` (ignored by git).
- The test uses installed Chrome via Playwright, not a mocked API.
