#!/bin/bash
# Tests for bin/deploy-agents' RENDERING — the half of the deploy that turned
# committed plists into templates when the tooling went public (#322).
#
# WHY THESE EXIST. A plist loaded with a placeholder still in it fails silently
# in launchd, and StartCalendarInterval is LOCAL time, which has already put a
# backup in the middle of a Saturday card once (see the backup template). So the
# cases render every agent against a known config and assert what launchd would
# be handed: the labels, the paths, and the local hour for each UTC time — in
# two timezones, since a conversion that is right only at UTC+0 is the bug.
#
# HOW. RAPTOR_RENDER_ONLY makes the deploy render into a directory and stop
# before anything touches launchd, the scope check or the test suites.
set -uo pipefail
# No operator config may leak into a test: every setting comes from the case.
# That means the ENVIRONMENT as well as ops.env — the environment beats the file
# by design, so a setting exported by whatever launched this suite (the deploy
# gate, a shell with a shadow's settings) would otherwise steer the cases.
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^(RAPTOR_|RESTART_|NTFY_|HEALTH_URL$|CAPTURE_URL$)')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
DEPLOY="$PWD/bin/deploy-agents"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

render_all() { # $1 = TZ  $2 = out dir  [$3… = extra env]
  local tz="$1" out="$2"; shift 2
  mkdir -p "$out"
  env TZ="$tz" HOME="$work/home" RAPTOR_RENDER_ONLY="$out" \
      RAPTOR_STATE_DIR="$work/state" RAPTOR_LABEL_PREFIX="com.example.raptor" "$@" \
      "$DEPLOY" >"$out.log" 2>&1
}

value_after() { # $1 = plist  $2 = key  → the integer after <key>$2</key>
  /usr/libexec/PlistBuddy -c "Print :StartCalendarInterval:$2" "$1" 2>/dev/null
}

echo "deploy-agents rendering:"

render_all Asia/Bangkok "$work/bkk"
rc=$?
(( rc == 0 )) && pass || fail "rendering failed: $(cat "$work/bkk.log")"

for agent in heartbeat keep-awake backup restore-drill close-out; do
  f="$work/bkk/com.example.raptor.$agent.plist"
  [[ -f "$f" ]] && pass || { fail "$agent was not rendered"; continue; }
  grep -qE '@[A-Z_]+@' "$f" && fail "$agent kept a placeholder" || pass
  plutil -lint -s "$f" && pass || fail "$agent is not a valid plist"
  [[ "$(/usr/libexec/PlistBuddy -c 'Print :Label' "$f")" == "com.example.raptor.$agent" ]] \
    && pass || fail "$agent has the wrong label"
  grep -q "$work/state/" "$f" && pass || fail "$agent does not run from the state directory"
done

# THE LOCAL-TIME CONVERSION, in the zone this was written in (UTC+7, no DST)…
check_time() { # $1 = dir  $2 = agent  $3 = hour  $4 = minute  $5 = case
  local f="$1/com.example.raptor.$2.plist"
  [[ "$(value_after "$f" Hour) $(value_after "$f" Minute)" == "$3 $4" ]] && pass \
    || fail "$5: got $(value_after "$f" Hour):$(value_after "$f" Minute), expected $3:$4"
}
check_time "$work/bkk" backup 12 47 "05:47Z at UTC+7"
check_time "$work/bkk" restore-drill 13 11 "06:11Z at UTC+7"
check_time "$work/bkk" close-out 6 30 "23:30Z at UTC+7 is the NEXT local morning"

# …and at UTC, where a conversion that did nothing would also pass.
render_all UTC "$work/utc" || fail "rendering at UTC failed"
check_time "$work/utc" backup 5 47 "05:47Z at UTC"
check_time "$work/utc" close-out 23 30 "23:30Z at UTC"

# The times are configuration.
render_all UTC "$work/conf" RAPTOR_BACKUP_UTC=04:05 || fail "rendering a configured time failed"
check_time "$work/conf" backup 4 5 "a configured backup time"

# Refusals.
render_all UTC "$work/bad" RAPTOR_BACKUP_UTC=5:47pm
[[ $? -ne 0 ]] && pass || fail "a malformed UTC time was accepted"
render_all UTC "$work/unknown" RAPTOR_AGENTS="heartbeat typo"
[[ $? -ne 0 ]] && pass || fail "an unknown agent in RAPTOR_AGENTS was accepted"

# The shadow configuration renders the heartbeat and nothing else.
render_all UTC "$work/shadow" RAPTOR_AGENTS=heartbeat || fail "rendering the shadow failed"
[[ "$(ls "$work/shadow" | wc -l | tr -d ' ')" == "1" ]] && pass \
  || fail "RAPTOR_AGENTS=heartbeat rendered $(ls "$work/shadow" | tr '\n' ' ')"

echo "  $passed passed, $failed failed"
(( failed == 0 ))
