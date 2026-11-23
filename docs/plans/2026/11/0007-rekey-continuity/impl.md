# Plan 0007 implementation

- Status: implemented
- Date: 2026-11-23
- Scope: 7A rebind, 7B failure visibility, 7C local rate limit
- Does not claim #14 as product-signed; E2E #14/#17 are automated receipts.

## Changes

- 7A: `AppEffect.RebindPending` rebinds unconfirmed outgoing messages onto the active new session in order,
  preserving business `seq`; `PersistRebound` updates the session row and reseals the message body under the new
  session key/AAD. The old session is retired after its last outgoing message is rebound.
- 7A recovery: `AppEvent.RecoverRebinds` is queued after unlock/reconnect and re-emits rebind work for a read-only
  session that still owns outgoing messages.
- 7A duplicate first data: a target new session authenticates before accepting a duplicate business sequence, then
  persists the new ratchet and old-session retirement without appending a second body.
- 7B: `lastNotification` is exposed as `notification` in `/api/state` only while unlocked; it is process-local.
- 7C: local explicit rekey starts are counted in a 60s window, maximum 3, reset on a new window.

## Verification

- `./mill core.test`: 145 tests pass.
- `./mill server.test`: 399 tests pass.
- `./mill server.e2e`: 15/15 behavior contracts pass, including E2E#17 pending-message rebind.
- `./check.sh`: see final command output for the repository gate.
