#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

./mill core.checkFormat
./mill core.fix --check
./mill core.test
# core.test includes TenKReplaySpec: 10,000-message protocol replay <50s.
if grep -RInE '\b(Future|ExecutionContext|Await|runBlocking|unsafeRun|unsafeToFuture)\b|SecureRandom|KeyPairGenerator|Instant\.now|System\.currentTimeMillis' core/src --include='*.scala'; then
  echo "core must remain pure: no Future/effect execution, random, or wall clock" >&2
  exit 1
fi
./mill store.checkFormat
./mill store.fix --check
./mill store.test
./mill transport.checkFormat
./mill transport.fix --check
./mill transport.test
./mill server.checkFormat
./mill server.fix --check
./mill server.test
./mill ui.checkFormat
./mill ui.fix --check
./mill ui.fastLinkJS
node --check shell/main.js
node --check shell/preload.js
node --check shell/keychain-smoke.js
node --check shell/perf-start.js
node --check shell/ui-smoke.js
node --check shell/contrast-smoke.js
node --check shell/electron-smoke.js
node --check shell/cross-host-smoke.js
node --check shell/passphrase-smoke.js
node --check shell/electron-runtime.js
node --check shell/browser-runtime.js
bash -n desktop-gate.sh
./mill server.e2e
./mill server.s2e2e
./mill server.s2perf
./mill server.s2bperf
./mill server.assembly
./mill transport.assembly
if ! node -e "require('./shell/electron-runtime').resolveElectron()" 2>/dev/null; then
  echo "desktop gate needs an Electron binary; run npm ci in shell, or set YOCHAT_ELECTRON" >&2
  node -e "require('./shell/electron-runtime').resolveElectron()" 2>&1 | tail -2 >&2 || true
  exit 1
fi
# keychain-smoke is an Electron main script; electron-runtime picks the binary
# (npm on macOS, YOCHAT_ELECTRON / PATH / nixpkgs on Linux).
./desktop-gate.sh node shell/electron-runtime.js shell/keychain-smoke.js
./desktop-gate.sh node shell/passphrase-smoke.js
YOCHAT_KEYCHAIN_UNAVAILABLE=1 ./desktop-gate.sh node shell/passphrase-smoke.js
node shell/contrast-smoke.js
./desktop-gate.sh node shell/perf-start.js
./desktop-gate.sh node shell/ui-smoke.js
./desktop-gate.sh node shell/electron-smoke.js
if [ "${YOCHAT_CROSS_HOST:-0}" = "1" ]; then
  ./desktop-gate.sh node shell/cross-host-smoke.js
fi
./models/check.sh
./mill spike.checkFormat
./mill spike.fix --check
./mill spike.compile
./mill spike.run crypto
./mill spike.run storage
./mill spike.run doobie
./mill spike.run demo --messages 100 --reconnect
