#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

./mill core.fix
./mill core.reformat
./mill store.fix
./mill store.reformat
./mill server.fix
./mill server.reformat
./mill ui.fix
./mill ui.reformat
./mill spike.fix
./mill spike.reformat
