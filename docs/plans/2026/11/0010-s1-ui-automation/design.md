# Plan 0010 design: S1 UI acceptance

## Scope

- No protocol, storage, API, or UI behavior change.
- Playwright drives the installed Chrome web UI; each device has its own server JVM, data directory, browser context, and page.
- The relay is a real separate JVM.

## Scenario

1. Both pages create a new local identity with the same test passphrase.
2. Device A issues an invitation through the UI; device B reads and accepts it through the UI.
3. Both pages show the contact; both click “我已当面核对”.
4. Both open the chat and A sends 100 messages through the composer.
5. A shows 100 own `delivered` messages; B shows 100 peer messages.

## Gates

- `UI_SMOKE PASS delivered=100 received=100`.
- Receipt under `tmp/checks/ui-*.json`.
- `check.sh` runs the smoke after building the server and relay assemblies.
- Electron window interaction, process restart UI, and external cryptography review remain outside this automated web-UI receipt.
