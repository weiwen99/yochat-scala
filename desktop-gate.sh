#!/usr/bin/env bash
# desktop-gate.sh — run an Electron desktop gate inside the logged-in GUI session.
#
# Electron's safeStorage reaches the login keychain only from the Aqua session.
# Over ssh, `launchctl managername` reports "Background" and safeStorage reports
# encryption unavailable: keychain gates fail there for a session-context reason,
# not because the screen is locked. This runner joins the Aqua session when it
# has to, keeps the display awake, bounds the run, and records the environment in
# a receipt beside the gate's own receipt.
#
# It decides *how* to run a gate. check.sh still decides *which* gates run.
#
# Usage: ./desktop-gate.sh <command> [args...]
# Env:   YOCHAT_GATE_TIMEOUT  seconds before the gate is judged INCOMPLETE (default 1800)

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <command> [args...]" >&2
  exit 2
fi

timeout_s="${YOCHAT_GATE_TIMEOUT:-1800}"
if [[ ! "$timeout_s" =~ ^[0-9]+$ ]] || (( timeout_s < 1 )); then
  echo "desktop-gate: YOCHAT_GATE_TIMEOUT must be a positive integer" >&2
  exit 2
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "desktop-gate: desktop gates are macOS-only" >&2
  exit 2
fi

user="$(id -un)"
uid="$(id -u)"

console_user="$(/usr/bin/stat -f %Su /dev/console)"
if [[ "$console_user" != "$user" ]]; then
  echo "desktop-gate: console is owned by '$console_user', not '$user'; no GUI session to join" >&2
  exit 2
fi

if [[ ! -x "$root/shell/node_modules/.bin/electron" ]]; then
  echo "desktop-gate: shell/node_modules is missing; run npm ci in shell" >&2
  exit 2
fi

# The last display on/off event is the only display-power signal available on
# Apple Silicon without IOPowerManagement on IODisplayWrangler.
display_state() {
  local last
  last="$(pmset -g log 2>/dev/null | grep -E 'Display is turned (on|off)' | tail -1 || true)"
  case "$last" in
    *"turned off"*) printf 'off' ;;
    *"turned on"*) printf 'on' ;;
    *) printf 'unknown' ;;
  esac
}

lock_state() {
  local raw
  raw="$(ioreg -n Root -d1 -a 2>/dev/null | plutil -p - 2>/dev/null | grep -m1 IOConsoleLocked || true)"
  case "$raw" in
    *true*) printf 'locked' ;;
    *false*) printf 'unlocked' ;;
    *) printf 'unknown' ;;
  esac
}

manager="$(launchctl managername 2>/dev/null || true)"
if [[ "$manager" == "Aqua" ]]; then
  mode="direct"
elif sudo -n true 2>/dev/null; then
  mode="asuser"
else
  mode="degraded"
  echo "desktop-gate: DEGRADED — outside Aqua and passwordless sudo is unavailable; keychain gates will report unavailable (exit 2)" >&2
fi

if [[ "$mode" == "asuser" ]]; then
  cmd=(sudo -n launchctl asuser "$uid" sudo -n -u "$user" \
       env "HOME=$HOME" "PATH=$PATH" "USER=$user" caffeinate -dims "$@")
else
  cmd=(caffeinate -dims "$@")
fi

epoch_ms() {
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'
  else
    printf '%s000\n' "$(date +%s)"
  fi
}

timeout_bin=""
if command -v timeout >/dev/null 2>&1; then
  timeout_bin="$(command -v timeout)"
fi

start_epoch="$(date +%s)"
start_ms="$(epoch_ms)"
start_display="$(display_state)"
start_lock="$(lock_state)"
mkdir -p tmp/checks

status=0
set +e
if [[ -n "$timeout_bin" ]]; then
  "$timeout_bin" --signal=TERM --kill-after=30 "$timeout_s" "${cmd[@]}"
  status=$?
else
  "${cmd[@]}" &
  child=$!
  ( sleep "$timeout_s"; kill -TERM "$child" 2>/dev/null ) &
  watchdog=$!
  wait "$child"
  status=$?
  kill "$watchdog" 2>/dev/null
  wait "$watchdog" 2>/dev/null
fi
set -e

case "$status" in
  0) verdict="PASS" ;;
  124|137) verdict="INCOMPLETE" ;;
  *) verdict="FAIL" ;;
esac

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

cmd_text=""
for arg in "$@"; do
  cmd_text+="$(printf '%q' "$arg") "
done

duration_ms=$(( $(epoch_ms) - start_ms ))
receipt="tmp/checks/desktop-gate-${start_epoch}.json"
cat > "$receipt" <<JSON
{
  "command": "$(json_escape "$cmd_text")",
  "cwd": "$(json_escape "$root")",
  "user": "$(json_escape "$user")",
  "host": "$(json_escape "$(scutil --get LocalHostName 2>/dev/null || hostname)")",
  "mode": "$mode",
  "manager": "$(json_escape "$manager")",
  "timeout_s": $timeout_s,
  "caffeinate": true,
  "display_before": "$start_display",
  "display_after": "$(display_state)",
  "lock_before": "$start_lock",
  "lock_after": "$(lock_state)",
  "start_epoch": $start_epoch,
  "duration_ms": $duration_ms,
  "exit_code": $status,
  "verdict": "$verdict"
}
JSON

echo "DESKTOP_GATE verdict=$verdict exit=$status duration_ms=$duration_ms mode=$mode display=$start_display receipt=$receipt"
exit "$status"
