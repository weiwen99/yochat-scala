#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

./mill core.checkFormat
./mill core.fix --check
./mill core.test
if grep -RInE '\b(Future|ExecutionContext|Await|runBlocking|unsafeRun|unsafeToFuture)\b|SecureRandom|KeyPairGenerator|Instant\.now|System\.currentTimeMillis' core/src --include='*.scala'; then
  echo "core must remain pure: no Future/effect execution, random, or wall clock" >&2
  exit 1
fi
./mill spike.checkFormat
./mill spike.fix --check
./mill spike.compile
./mill spike.run crypto
./mill spike.run demo --messages 100 --reconnect
