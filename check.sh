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
./models/check.sh
./mill spike.checkFormat
./mill spike.fix --check
./mill spike.compile
./mill spike.run crypto
./mill spike.run storage
./mill spike.run doobie
./mill spike.run demo --messages 100 --reconnect
