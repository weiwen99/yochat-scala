#!/usr/bin/env bash
# desktop-gate.sh — run an Electron desktop gate inside the logged-in GUI session.
#
# Electron's safeStorage reaches the login keychain only from the Aqua session.
# Over ssh, `launchctl managername` reports "Background" and creating a keychain
# item fails with errSecInteractionNotAllowed: keychain gates fail there for a
# session-context reason, not because the screen is locked. This runner joins the
# Aqua session when it has to, keeps the display awake, bounds the run, and
# records the environment in a receipt beside the gate's own receipt.
#
# It decides *how* to run a gate. check.sh still decides *which* gates run.
#
# Usage: ./desktop-gate.sh <command> [args...]
# Env:
#   YOCHAT_GATE_TIMEOUT  seconds before the gate is judged INCOMPLETE (default 1800)
#   YOCHAT_GATE_INJECT   auto (default) | asuser | direct
#   YOCHAT_*             forwarded to the gate unchanged (e.g. YOCHAT_SERVER_JAR)

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

epoch_ms() {
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'
  else
    printf '%s000\n' "$(date +%s)"
  fi
}

manager="$(launchctl managername 2>/dev/null || true)"
inject="${YOCHAT_GATE_INJECT:-auto}"
case "$inject" in
  auto)
    if [[ "$manager" == "Aqua" ]]; then
      mode="direct"
    elif sudo -n true 2>/dev/null; then
      mode="asuser"
    else
      mode="degraded"
    fi
    ;;
  asuser)
    if ! sudo -n true 2>/dev/null; then
      echo "desktop-gate: YOCHAT_GATE_INJECT=asuser needs passwordless sudo" >&2
      exit 2
    fi
    mode="asuser"
    ;;
  direct)
    mode="direct"
    ;;
  degraded)
    mode="degraded"
    ;;
  *)
    echo "desktop-gate: YOCHAT_GATE_INJECT must be auto, asuser, direct or degraded" >&2
    exit 2
    ;;
esac

# Gates read YOCHAT_* configuration (jar paths, relay endpoint, cross-host peers,
# rate caps). Session injection must not silently replace them with defaults, so
# forward every YOCHAT_* variable except this runner's own controls.
forward_env=()
forward_names=()
while IFS='=' read -r name value; do
  case "$name" in
    YOCHAT_GATE_INJECT|YOCHAT_GATE_TIMEOUT) ;;
    YOCHAT_*) forward_env+=("$name=$value"); forward_names+=("$name") ;;
  esac
done < <(env)

if [[ "$mode" == "asuser" ]]; then
  cmd=(sudo -n launchctl asuser "$uid" sudo -n -u "$user" \
       env "HOME=$HOME" "PATH=$PATH" "USER=$user" ${forward_env[@]+"${forward_env[@]}"} caffeinate -dims "$@")
else
  cmd=(caffeinate -dims "$@")
fi

start_epoch="$(date +%s)"
start_ms="$(epoch_ms)"
start_display="$(display_state)"
start_lock="$(lock_state)"
mkdir -p tmp/checks

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

head="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
dirty_files="$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
node_version="$(node --version 2>/dev/null || echo unknown)"
electron_version="$(node -p "require('$root/shell/node_modules/electron/package.json').version" 2>/dev/null || echo unknown)"

# Outside Aqua and without passwordless sudo there is no way to reach the login
# keychain, so an Electron gate cannot be judged here. Reporting FAIL would blame
# the commit for the machine's session setup.
if [[ "$mode" == "degraded" ]]; then
  echo "desktop-gate: DEGRADED — outside Aqua and passwordless sudo is unavailable; the gate was not run" >&2
  receipt="tmp/checks/desktop-gate-${start_epoch}.json"
  log="tmp/checks/desktop-gate-${start_epoch}.log"
  : > "$log"
  cat > "$receipt" <<JSON
{
  "command": "$(json_escape "$cmd_text")",
  "cwd": "$(json_escape "$root")",
  "user": "$(json_escape "$user")",
  "host": "$(json_escape "$(scutil --get LocalHostName 2>/dev/null || hostname)")",
  "head": "$(json_escape "$head")",
  "dirty_files": $dirty_files,
  "node": "$(json_escape "$node_version")",
  "electron": "$(json_escape "$electron_version")",
  "mode": "degraded",
  "caller_manager": "$(json_escape "$manager")",
  "session": "$(json_escape "$manager")",
  "forwarded_env": "",
  "log": "$(json_escape "$log")",
  "timeout_s": $timeout_s,
  "caffeinate": false,
  "display_before": "$start_display",
  "display_after": "$start_display",
  "lock_before": "$start_lock",
  "lock_after": "$start_lock",
  "start_epoch": $start_epoch,
  "duration_ms": 0,
  "exit_code": 2,
  "verdict": "INCOMPLETE"
}
JSON
  echo "DESKTOP_GATE verdict=INCOMPLETE exit=2 reason=degraded receipt=$receipt"
  exit 2
fi

marker="${TMPDIR:-/tmp}/yochat-gate-timeout-$$"
rm -f "$marker"
log="tmp/checks/desktop-gate-${start_epoch}.log"

# Job control puts the gate in its own process group, so a timeout, a Ctrl-C, or
# a dropped ssh session can take down the whole tree. Without it, a killed gate
# leaves an Electron and its JVM behind holding the port for the next run.
child=""
watchdog=""
cleanup() {
  if [[ -n "$watchdog" ]]; then
    kill -TERM -"$watchdog" 2>/dev/null || true
  fi
  if [[ -n "$child" ]]; then
    kill -TERM -"$child" 2>/dev/null || true
    /bin/sleep 2 >/dev/null 2>&1 || true
    kill -KILL -"$child" 2>/dev/null || true
  fi
  rm -f "$marker" || true
  return 0
}
trap cleanup EXIT INT TERM HUP

status=0
set +e
set -m
"${cmd[@]}" > >(tee "$log") 2>&1 &
child=$!
# The watchdog gets its own process group and no inherited output: a plain child
# sleep would outlive the gate and keep the caller's pipe open, so a `| tail`
# would never see EOF.
(
  sleep "$timeout_s"
  : > "$marker"
  kill -TERM -"$child" 2>/dev/null
  sleep 30
  kill -KILL -"$child" 2>/dev/null
) >/dev/null 2>&1 &
watchdog=$!
set +m
wait "$child"
status=$?
kill -TERM -"$watchdog" 2>/dev/null
wait "$watchdog" 2>/dev/null
watchdog=""
sleep 1
set -e

timed_out=0
if [[ -f "$marker" ]]; then
  timed_out=1
fi
rm -f "$marker"

if [[ "$timed_out" -eq 1 ]]; then
  status=124
fi
case "$status" in
  0) verdict="PASS" ;;
  124|129|130|137|143) verdict="INCOMPLETE" ;;
  *) verdict="FAIL" ;;
esac

forwarded=""
for name in ${forward_names[@]+"${forward_names[@]}"}; do
  forwarded="${forwarded:+$forwarded,}$name"
done

session="$manager"
if [[ "$mode" == "asuser" ]]; then
  session="$(sudo -n launchctl asuser "$uid" sudo -n -u "$user" launchctl managername 2>/dev/null || true)"
fi

duration_ms=$(( $(epoch_ms) - start_ms ))
receipt="tmp/checks/desktop-gate-${start_epoch}.json"
cat > "$receipt" <<JSON
{
  "command": "$(json_escape "$cmd_text")",
  "cwd": "$(json_escape "$root")",
  "user": "$(json_escape "$user")",
  "host": "$(json_escape "$(scutil --get LocalHostName 2>/dev/null || hostname)")",
  "head": "$(json_escape "$head")",
  "dirty_files": $dirty_files,
  "node": "$(json_escape "$node_version")",
  "electron": "$(json_escape "$electron_version")",
  "mode": "$mode",
  "caller_manager": "$(json_escape "$manager")",
  "session": "$(json_escape "$session")",
  "forwarded_env": "$(json_escape "$forwarded")",
  "log": "$(json_escape "$log")",
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

echo "DESKTOP_GATE verdict=$verdict exit=$status duration_ms=$duration_ms mode=$mode session=$session display=$start_display receipt=$receipt"
exit "$status"
