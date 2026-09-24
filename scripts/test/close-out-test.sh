#!/bin/bash
# Tests for scripts/raptor-close-out.sh — the launchd trigger for the nightly
# close-out (#334).
#
# WHY THESE EXIST. The script's one judgement is WHEN TO TRY AGAIN. On wake the
# Docker VM may not be listening yet, and a single refused connection would cost
# the night; but a retry after the app has answered would be a second close-out,
# not a second attempt at the first. Collapsing those two is the mistake worth
# testing for, in both directions.
#
# HOW. A stub `curl` on CLOSE_OUT_PATH_PREFIX replays a scripted sequence of
# outcomes, one line per call ("<curl exit> <http code> <body>"), and counts the
# calls — so every case asserts how many requests were made, not only the exit.
set -uo pipefail
# No operator config may leak into a test: every setting comes from the case.
# That means the ENVIRONMENT as well as ops.env — the environment beats the file
# by design, so a setting exported by whatever launched this suite (the deploy
# gate, a shell with a shadow's settings) would otherwise steer the cases.
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^(RAPTOR_|RESTART_|NTFY_|HEALTH_URL$|CAPTURE_URL$)')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
SCRIPT="$PWD/scripts/raptor-close-out.sh"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }

STUB="$(mktemp -d)"
trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/curl" <<'STUBEOF'
#!/bin/bash
# Replays line N of $STUB_DIR/responses on call N; the last line repeats.
dir="$STUB_DIR"
n=$(( $(cat "$dir/calls" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$dir/calls"
line=$(sed -n "${n}p" "$dir/responses"); [[ -z "$line" ]] && line=$(tail -1 "$dir/responses")
rc=${line%% *}; rest=${line#* }; code=${rest%% *}; body=${rest#* }
out=""
while (( $# )); do [[ "$1" == "-o" ]] && { out="$2"; shift; }; shift; done
[[ -n "$out" ]] && printf '%s' "$body" > "$out"
(( rc == 0 )) && printf '%s' "$code"
exit "$rc"
STUBEOF
chmod +x "$STUB/curl"

run() { # $@ = response lines; sets $out, $rc, $calls
  rm -f "$STUB/calls"
  printf '%s\n' "$@" > "$STUB/responses"
  out="$(STUB_DIR="$STUB" CLOSE_OUT_PATH_PREFIX="$STUB" CLOSE_OUT_ATTEMPTS=3 CLOSE_OUT_RETRY_S=0 \
         "$SCRIPT" 2>&1)"
  rc=$?
  calls=$(cat "$STUB/calls" 2>/dev/null || echo 0)
}

expect() { # $1 = rc  $2 = calls  $3 = substring  $4 = case
  [[ "$rc" == "$1" ]] && pass || fail "$4: exit $rc, expected $1 ($out)"
  [[ "$calls" == "$2" ]] && pass || fail "$4: $calls request(s), expected $2"
  [[ "$out" == *"$3"* ]] && pass || fail "$4: expected '$3' in: $out"
}

echo "close-out:"

run '0 200 {"status":"COMPLETED","archiveFiles":3}'
expect 0 1 'close-out ran (attempt 1): {"status":"COMPLETED"' "a normal night"

# A failed sweep is still a run: the app recorded it, the heartbeat pushes it.
run '0 200 {"status":"FAILED","archiveFiles":0}'
expect 0 1 '"status":"FAILED"' "a failed sweep is not retried"

run '0 409 {"status":"ALREADY_RUNNING"}'
expect 0 1 "already running" "another run holds the lock"

# THE CASE THIS SCRIPT EXISTS FOR: fired on wake before the VM is listening.
run '7 000 ' '7 000 ' '0 200 {"status":"COMPLETED"}'
expect 0 3 "close-out ran (attempt 3)" "not listening yet on wake"

run '7 000 '
expect 1 3 "not answering after 3 attempt(s); no close-out ran" "never listening"

# Reached the app: retrying would be a SECOND close-out.
run '0 500 {"error":"boom"}'
expect 1 1 "HTTP 500" "a server error is not retried"

run '0 404 {"error":"Not Found"}'
expect 1 1 "HTTP 404" "an image without the endpoint says so"

run '28 000 '
expect 1 1 "curl exit 28" "a timeout mid-run is not retried"

echo "  $passed passed, $failed failed"
(( failed == 0 ))
