#!/usr/bin/env bash
# desktop-gate.sh — run an Electron desktop gate inside the logged-in GUI session.
#
# macOS: Electron's safeStorage reaches the login keychain only from the Aqua
# session. Over ssh, `launchctl managername` reports "Background" and creating a
# keychain item fails with errSecInteractionNotAllowed: keychain gates fail there
# for a session-context reason, not because the screen is locked. This runner
# joins the Aqua session when it has to.
#
# Linux: there is no cross-session injection. A gate runs directly and needs an X
# display: YOCHAT_GATE_DISPLAY, else DISPLAY, else the first live X socket. With
# none, the gate cannot be judged and the run is INCOMPLETE.
#
# Both: the display is kept awake, the run is bounded, and the environment is
# recorded in a receipt beside the gate's own receipt.
#
# It decides *how* to run a gate. check.sh still decides *which* gates run.
#
# Usage: ./desktop-gate.sh <command> [args...]
# Env:
#   YOCHAT_GATE_TIMEOUT  seconds before the gate is judged INCOMPLETE (default 1800)
#   YOCHAT_GATE_INJECT   auto (default) | asuser | direct (macOS only)
#   YOCHAT_GATE_DISPLAY  X display for Linux gates (default: discover)
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

case "$(uname -s)" in
  Darwin) platform="macos" ;;
  Linux) platform="linux" ;;
  *)
    echo "desktop-gate: unsupported platform $(uname -s); gates are macOS or Linux" >&2
    exit 2
    ;;
esac

user="$(id -un)"
uid="$(id -u)"

if [[ "$platform" == "macos" ]]; then
  console_user="$(/usr/bin/stat -f %Su /dev/console)"
  if [[ "$console_user" != "$user" ]]; then
    echo "desktop-gate: console is owned by '$console_user', not '$user'; no GUI session to join" >&2
    exit 2
  fi
fi

# The same resolver the gates use. A Linux host may legitimately have no npm
# package (it cannot run there anyway) while YOCHAT_ELECTRON or nixpkgs provide one.
if ! node -e "require('$root/shell/electron-runtime').resolveElectron()" >/dev/null 2>&1; then
  echo "desktop-gate: no Electron binary; run npm ci in shell, or set YOCHAT_ELECTRON" >&2
  node -e "require('$root/shell/electron-runtime').resolveElectron()" 2>&1 | tail -2 >&2 || true
  exit 2
fi

# The last display on/off event is the only display-power signal available on
# Apple Silicon without IOPowerManagement on IODisplayWrangler.
macos_display_state() {
  local last
  last="$(pmset -g log 2>/dev/null | grep -E 'Display is turned (on|off)' | tail -1 || true)"
  case "$last" in
    *"turned off"*) printf 'off' ;;
    *"turned on"*) printf 'on' ;;
    *) printf 'unknown' ;;
  esac
}

macos_lock_state() {
  local raw
  raw="$(ioreg -n Root -d1 -a 2>/dev/null | plutil -p - 2>/dev/null | grep -m1 IOConsoleLocked || true)"
  case "$raw" in
    *true*) printf 'locked' ;;
    *false*) printf 'unlocked' ;;
    *) printf 'unknown' ;;
  esac
}

# DRM reports dpms per connector. Only connected ones count: an unused port also
# reads "Off" and would otherwise be mistaken for a blanked screen.
linux_display_state() {
  local status dpms seen=""
  for status in /sys/class/drm/card*-*/status; do
    [[ -r "$status" ]] || continue
    [[ "$(cat "$status" 2>/dev/null || true)" == "connected" ]] || continue
    dpms="$(cat "${status%status}dpms" 2>/dev/null || true)"
    case "$dpms" in
      Off) printf 'off'; return ;;
      On) seen="on" ;;
    esac
  done
  if [[ "$seen" == "on" ]]; then printf 'on'; else printf 'unknown'; fi
}

# Best effort, recorded but never used to judge a gate: logind may not expose the
# attribute for an Xwayland session started by a display manager.
linux_session_id() {
  local id _uid _user seat _leader class _rest
  while read -r id _uid _user seat _leader class _rest; do
    [[ "$_uid" == "$uid" ]] || continue
    # A seat means a graphical session. Without this filter the first row can be
    # the display manager's `manager` session, whose LockedHint has nothing to do
    # with the screen.
    [[ "$class" == "user" ]] || continue
    [[ -n "$seat" && "$seat" != "-" ]] || continue
    printf '%s' "$id"
    return
  done < <(loginctl list-sessions --no-legend 2>/dev/null || true)
  printf ''
}

linux_lock_state() {
  local id raw
  id="$(linux_session_id)"
  if [[ -z "$id" ]]; then printf 'unknown'; return; fi
  raw="$(loginctl show-session "$id" -p LockedHint --value 2>/dev/null || true)"
  case "$raw" in
    yes|true) printf 'locked' ;;
    no|false) printf 'unlocked' ;;
    *) printf 'unknown' ;;
  esac
}

linux_session_type() {
  local id raw
  id="$(linux_session_id)"
  if [[ -n "$id" ]]; then
    raw="$(loginctl show-session "$id" -p Type --value 2>/dev/null || true)"
    if [[ -n "$raw" && "$raw" != "unspecified" ]]; then printf '%s' "$raw"; return; fi
  fi
  printf '%s' "${XDG_SESSION_TYPE:-unknown}"
}

display_state() {
  if [[ "$platform" == "macos" ]]; then macos_display_state; else linux_display_state; fi
}

lock_state() {
  if [[ "$platform" == "macos" ]]; then macos_lock_state; else linux_lock_state; fi
}

session_type() {
  if [[ "$platform" == "macos" ]]; then printf 'aqua'; else linux_session_type; fi
}

# X display for a Linux gate. Over ssh DISPLAY is unset even though Xwayland is
# running, so scan the sockets and keep the first one that answers.
linux_display() {
  if [[ -n "${YOCHAT_GATE_DISPLAY:-}" ]]; then printf '%s' "$YOCHAT_GATE_DISPLAY"; return; fi
  if [[ -n "${DISPLAY:-}" ]]; then printf '%s' "$DISPLAY"; return; fi
  local sock candidate
  for sock in /tmp/.X11-unix/X*; do
    [[ -e "$sock" ]] || continue
    candidate=":${sock##*X}"
    if command -v xdpyinfo >/dev/null 2>&1 && xdpyinfo -display "$candidate" >/dev/null 2>&1; then
      printf '%s' "$candidate"
      return
    fi
  done
  printf ''
}

epoch_ms() {
  if command -v perl >/dev/null 2>&1; then
    perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'
  else
    printf '%s000\n' "$(date +%s)"
  fi
}

inject="${YOCHAT_GATE_INJECT:-auto}"
gate_display=""
if [[ "$platform" == "macos" ]]; then
  manager="$(launchctl managername 2>/dev/null || true)"
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
    direct) mode="direct" ;;
    degraded)
      mode="degraded"
      degrade_reason="forced"
      ;;
    *)
      echo "desktop-gate: YOCHAT_GATE_INJECT must be auto, asuser, direct or degraded" >&2
      exit 2
      ;;
  esac
else
  # No cross-session injection exists on Linux; a gate either has a display or it
  # cannot be judged.
  manager="$(session_type)"
  case "$inject" in
    asuser)
      echo "desktop-gate: YOCHAT_GATE_INJECT=asuser is macOS-only; Linux gates run direct" >&2
      exit 2
      ;;
    auto|direct) mode="direct" ;;
    degraded)
      mode="degraded"
      degrade_reason="forced"
      ;;
    *)
      echo "desktop-gate: YOCHAT_GATE_INJECT must be auto, direct or degraded on Linux" >&2
      exit 2
      ;;
  esac
  gate_display="$(linux_display)"
  if [[ "$mode" == "direct" && -z "$gate_display" ]]; then
    mode="degraded"
  fi
fi

degrade_reason="${degrade_reason:-session}"
if [[ "$platform" == "linux" && -z "$gate_display" ]]; then
  degrade_reason="no-display"
fi

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
elif [[ "$platform" == "macos" ]]; then
  cmd=(caffeinate -dims "$@")
else
  cmd=("$@")
  export DISPLAY="$gate_display"
  # systemd-inhibit is the closest equivalent of caffeinate, but logind refuses it
  # from a session that cannot authenticate interactively (over ssh it does). Probe
  # once: an inhibitor we cannot take must not turn into a failed gate.
  if command -v systemd-inhibit >/dev/null 2>&1 \
     && systemd-inhibit --what=idle:sleep --who=desktop-gate --why=probe --mode=block true >/dev/null 2>&1; then
    keep_awake="true"
  else
    keep_awake="false"
    echo "desktop-gate: keep-awake unavailable (logind refused the inhibitor); the display may sleep mid-run" >&2
  fi
  if [[ "$keep_awake" == "true" ]]; then
    cmd=(systemd-inhibit --what=idle:sleep --who=desktop-gate --why="YoChat desktop gate" --mode=block "$@")
  fi
fi
if [[ "$platform" == "macos" ]]; then
  keep_awake="true"
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
# `electron` above is the pinned npm version; these two describe what a gate will
# actually launch, which on Linux is usually not the npm copy at all.
electron_source="$(node -e "try{process.stdout.write(require('$root/shell/electron-runtime').resolveElectron().source)}catch(_){process.stdout.write('none')}" 2>/dev/null || printf 'none')"
electron_resolved="$(node -e "try{process.stdout.write(require('$root/shell/electron-runtime').resolveElectron().executablePath)}catch(_){process.stdout.write('')}" 2>/dev/null || true)"

# Outside Aqua and without passwordless sudo there is no way to reach the login
# keychain, so an Electron gate cannot be judged here. Reporting FAIL would blame
# the commit for the machine's session setup.
if [[ "$mode" == "degraded" ]]; then
  case "$degrade_reason" in
    no-display)
      echo "desktop-gate: DEGRADED — no X display (set YOCHAT_GATE_DISPLAY or start one); the gate was not run" >&2 ;;
    forced)
      echo "desktop-gate: DEGRADED — YOCHAT_GATE_INJECT=degraded asked for this; the gate was not run" >&2 ;;
    *)
      echo "desktop-gate: DEGRADED — outside Aqua and passwordless sudo is unavailable; the gate was not run" >&2 ;;
  esac
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
  "electron_env": "$(json_escape "${YOCHAT_ELECTRON:-}")",
  "electron_source": "$(json_escape "$electron_source")",
  "electron_resolved": "$(json_escape "$electron_resolved")",
  "mode": "degraded",
  "caller_manager": "$(json_escape "$manager")",
  "session": "$(json_escape "$manager")",
  "forwarded_env": "",
  "log": "$(json_escape "$log")",
  "timeout_s": $timeout_s,
  "keep_awake": false,
  "platform": "$platform",
  "session_type": "$(json_escape "$(session_type)")",
  "display_env": "$(json_escape "$gate_display")",
  "display_before": "$start_display",
  "display_after": "$start_display",
  "lock_before": "$start_lock",
  "lock_after": "$start_lock",
  "start_epoch": $start_epoch,
  "duration_ms": 0,
  "exit_code": 2,
  "reason": "degraded-$degrade_reason",
  "verdict": "INCOMPLETE"
}
JSON
  echo "DESKTOP_GATE verdict=INCOMPLETE exit=2 reason=degraded-$degrade_reason receipt=$receipt"
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
gate_status="$status"
case "$status" in
  0) verdict="PASS" ;;
  78)
    # "Not applicable here" (a capability the platform does not have). Only the
    # keychain gate may say that: any other 78 is an unexplained failure.
    if [[ "$cmd_text" == *keychain-smoke* ]]; then
      verdict="SKIP"
      status=0
    else
      verdict="FAIL"
    fi
    ;;
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
  "electron_env": "$(json_escape "${YOCHAT_ELECTRON:-}")",
  "electron_source": "$(json_escape "$electron_source")",
  "electron_resolved": "$(json_escape "$electron_resolved")",
  "mode": "$mode",
  "caller_manager": "$(json_escape "$manager")",
  "session": "$(json_escape "$session")",
  "forwarded_env": "$(json_escape "$forwarded")",
  "log": "$(json_escape "$log")",
  "timeout_s": $timeout_s,
  "keep_awake": $keep_awake,
  "platform": "$platform",
  "session_type": "$(json_escape "$(session_type)")",
  "display_env": "$(json_escape "${DISPLAY:-}")",
  "display_before": "$start_display",
  "display_after": "$(display_state)",
  "lock_before": "$start_lock",
  "lock_after": "$(lock_state)",
  "start_epoch": $start_epoch,
  "duration_ms": $duration_ms,
  "exit_code": $gate_status,
  "verdict": "$verdict"
}
JSON

echo "DESKTOP_GATE verdict=$verdict exit=$gate_status duration_ms=$duration_ms mode=$mode session=$session display=$start_display receipt=$receipt"
exit "$status"
