#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

./mill core.fix
./mill core.reformat
./mill spike.fix
./mill spike.reformat
