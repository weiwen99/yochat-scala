#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

./mill spike.fix
./mill spike.reformat
