#!/bin/bash
# Tests for scripts/lid — the check that decides whether a laptop lid may close.
#
# WHY THESE EXIST. `lid` is read at the moment somebody is walking out of the
# door, which is the worst possible moment for it to be subtly wrong, and until
# #265 it lived only in ~/.paddock with no source in this repo and no test at
# all. Its whole job is a three-way distinction — safe / not safe / unknown —
# and the expensive mistake is collapsing the third into the first.
#
# HOW. `RAPTOR_HEALTH_URL` has been in the script since it was written; curl
# reads `file://` perfectly well, so each case writes a health payload to a temp
# file and points the script at it. No stubs, no PATH games: the real curl and
# the real python3 parse a real payload, which is the part worth testing.
#
# The fixtures are shaped from the actual /actuator/health/capture response,
# `scope.lookahead` included.
set -uo pipefail
# No operator config may leak into a test: every setting comes from the case.
# That means the ENVIRONMENT as well as ops.env — the environment beats the file
# by design, so a setting exported by whatever launched this suite (the deploy
# gate, a shell with a shadow's settings) would otherwise steer the cases.
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^(RAPTOR_|RESTART_|NTFY_|HEALTH_URL$|CAPTURE_URL$)')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
LID="$PWD/scripts/lid"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }

# --- fixtures ---------------------------------------------------------------
payload() { # $1 = marketsInScope  $2 = lookahead block (raw JSON, "" = absent)
  local scope="$1" look="${2:-}"
  local details="\"marketsInScope\":$scope,\"pending\":0,\"subscribed\":$scope,\"live\":0"
  [[ -n "$look" ]] && details="$details,\"lookahead\":$look"
  printf '{"status":"UP","components":{"scope":{"status":"UP","details":{%s}},"recorder":{"status":"UP","details":{"state":"IDLE"}}}}' "$details"
}

run() { # $1 = payload; sets $out and $rc
  local file; file="$(mktemp)"
  printf '%s' "$1" > "$file"
  out="$(RAPTOR_HEALTH_URL="file://$file" "$LID" 2>&1)"
  rc=$?
  rm -f "$file"
}

expect_rc() { # $1 = expected  $2 = case
  [[ "$rc" == "$1" ]] && pass || fail "$2: exit $rc, expected $1"
}

expect_says() { # $1 = substring  $2 = case
  [[ "$out" == *"$1"* ]] && pass || fail "$2: expected '$1' in: $out"
}

expect_silent_about() { # $1 = substring  $2 = case
  [[ "$out" != *"$1"* ]] && pass || fail "$2: did NOT expect '$1' in: $out"
}

echo "lid:"

# 1. Markets in scope is the one case that must refuse.
run "$(payload 12 '{"known":true,"stale":false,"windowSeconds":172800,"consecutiveFailures":0}')"
expect_rc 1 "markets in scope"
expect_says "DO NOT CLOSE" "markets in scope"

# 2. The whole of #265: an empty scope that can say how long it stays empty.
run "$(payload 0 '{"known":true,"stale":false,"windowSeconds":172800,"consecutiveFailures":0,"nextKickoff":"2026-09-16T19:00:00Z","secondsToNextKickoff":32400,"secondsUntilScopeOpens":18000}')"
expect_rc 0 "clear for five hours"
expect_says "SAFE" "clear for five hours"
expect_says "Nothing enters scope for 5h00m" "clear for five hours"

# 3. A genuinely empty window says what it covers.
run "$(payload 0 '{"known":true,"stale":false,"windowSeconds":172800,"consecutiveFailures":0}')"
expect_rc 0 "empty window"
expect_says "No kickoff at all in the next 48h00m" "empty window"

# 4. THE CASE THIS SCRIPT EXISTS TO GET RIGHT. A lookahead nobody could measure
#    must not read as a clear week — but must also not turn a quiet Tuesday into
#    "do not close", because the exit status is about NOW and now really is safe.
run "$(payload 0 '{"known":false,"stale":true,"windowSeconds":172800,"consecutiveFailures":3}')"
expect_rc 0 "no forward visibility"
expect_says "SAFE" "no forward visibility"
expect_says "Forward visibility UNAVAILABLE (3 failed poll(s))" "no forward visibility"
expect_silent_about "No kickoff at all" "no forward visibility"
expect_silent_about "Nothing enters scope" "no forward visibility"

# 5. An old figure is usable and must say that it is old.
run "$(payload 0 '{"known":true,"stale":true,"windowSeconds":172800,"consecutiveFailures":1,"nextKickoff":"2026-09-16T19:00:00Z","secondsUntilScopeOpens":18000}')"
expect_rc 0 "stale figure"
expect_says "Nothing enters scope for 5h00m" "stale figure"
expect_says "that figure is stale" "stale figure"

# 6. A payload with no lookahead block at all — an older build, or the field
#    renamed. Absence is not a clear week either.
run "$(payload 0)"
expect_rc 0 "no lookahead block"
expect_says "Forward visibility UNAVAILABLE" "no lookahead block"

# 7. Unreachable is UNKNOWN, never safe: raptor can be down WHILE markets are
#    in scope, which is the dangerous case rather than the safe one.
out="$(RAPTOR_HEALTH_URL="file:///nonexistent/raptor-lid-test" "$LID" 2>&1)"; rc=$?
expect_rc 2 "unreachable"
expect_says "UNKNOWN" "unreachable"

# 8. And a payload whose shape has moved on.
run '{"status":"UP","components":{}}'
expect_rc 2 "shape changed"
expect_says "UNKNOWN" "shape changed"

# 9. --quiet is for scripting: status only.
file="$(mktemp)"
printf '%s' "$(payload 4 '{"known":true,"stale":false,"windowSeconds":172800,"consecutiveFailures":0}')" > "$file"
out="$(RAPTOR_HEALTH_URL="file://$file" "$LID" --quiet 2>&1)"; rc=$?
rm -f "$file"
expect_rc 1 "quiet"
[[ -z "$out" ]] && pass || fail "quiet: expected no output, got: $out"

echo "  $passed passed, $failed failed"
[[ "$failed" -eq 0 ]]
