#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

./mill spike.checkFormat
./mill spike.fix --check
./mill spike.compile
./mill spike.run crypto
./mill spike.run demo --messages 100 --reconnect
