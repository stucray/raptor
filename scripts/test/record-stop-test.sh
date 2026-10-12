#!/bin/bash
# Tests for scripts/record-stop.sh — how the old backend stopped, per deploy (#75).
#
# WHY THESE EXIST. The record is only worth having on the deploy that goes
# wrong, which is the one nobody is watching. So the cases are the verdicts it
# must tell apart (a graceful exit, a SIGKILL, no restart at all, nothing
# running beforehand), and that it never leaves its readers running.
#
# HOW. A stub `docker` on PATH replays canned events and log lines. No Docker,
# no network, nothing of the operator's.
set -uo pipefail
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^RAPTOR_')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
ROOT="$PWD"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }
check() { (( $1 == 0 )) && pass || fail "$2 ($out)"; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
export RAPTOR_STATE_DIR="$work/state"

# The stub: `inspect` answers $STUB_RUNNING; `events` and `logs -f` print their
# canned lines and then block, as the real streams do, until killed.
cat > "$work/bin/docker" <<'STUB'
#!/bin/bash
case "$1" in
  inspect) [[ -n "${STUB_RUNNING:-}" ]] && echo "$STUB_RUNNING"; [[ -n "${STUB_RUNNING:-}" ]] ;;
  events)  printf '%s' "${STUB_EVENTS:-}"; exec sleep 300 ;;
  logs)    printf '%s' "${STUB_LOGS:-}"; exec sleep 300 ;;
  *)       exit 1 ;;
esac
STUB
chmod +x "$work/bin/docker"
export PATH="$work/bin:$PATH"

deploy() {
  local dir="$work/run-$1"
  "$ROOT/scripts/record-stop.sh" start "$dir"
  # What compose does between the two calls, compressed: the stub has already
  # printed its events, so only the stream needs a moment to be written.
  sleep 0.3
  out="$("$ROOT/scripts/record-stop.sh" finish "$dir" 2>&1)"; rc=$?
  pids="$(cat "$dir/pids" 2>/dev/null)"
}
last() { tail -n 1 "$RAPTOR_STATE_DIR/stops.log"; }
alive() { local p; for p in $pids; do kill -0 "$p" 2>/dev/null && return 0; done; return 1; }

echo "record-stop:"

# A graceful stop: SIGTERM, then exit 143 0.48s later, with the old logs kept.
export STUB_RUNNING=true
export STUB_EVENTS=$'1000000000000000000 kill  15\n1000000000480000000 die 143 \n'
export STUB_LOGS=$'... capture lease released\n... Graceful shutdown complete\n'
deploy graceful
check $(( rc != 0 )) "finish exits 0"
[[ "$(last)" == *"exit=143 graceful sigterm_to_exit=0.48s"* ]]; check $? "a 143 is recorded as graceful, with its timing"
logs_file="$(last | sed -E 's/.* old_logs=//')"
grep -q 'Graceful shutdown complete' "$logs_file" 2>/dev/null; check $? "the old container's log tail is kept"
[[ "$out" != *WARNING* ]]; check $? "a graceful stop raises no warning"
! alive; check $? "both readers are stopped after a graceful deploy"

# A SIGKILL after the grace period: 137, timed from the SIGTERM, not the SIGKILL.
export STUB_EVENTS=$'1000000000000000000 kill  15\n1000000010000000000 kill  9\n1000000010010000000 die 137 \n'
deploy killed
[[ "$(last)" == *"exit=137 SIGKILLED sigterm_to_exit=10.01s"* ]]; check $? "a 137 is recorded as SIGKILLED, timed from the SIGTERM"
[[ "$out" == *WARNING* ]]; check $? "a SIGKILL raises a warning"
! alive; check $? "both readers are stopped after a killed deploy"

# Running, but compose left it alone: no die, so no restart to measure.
export STUB_EVENTS=""
deploy unchanged
[[ "$(last)" == *"backend was not restarted (no die event)"* ]]; check $? "no die event is recorded as no restart"

# Nothing running before the deploy: nothing to measure, and no readers started.
export STUB_RUNNING=""
deploy first
[[ "$(last)" == *"no running backend before the deploy"* ]]; check $? "a first start is recorded as nothing to measure"
[[ -z "$pids" ]]; check $? "no readers are started when nothing is running"

(( $(wc -l < "$RAPTOR_STATE_DIR/stops.log") == 4 )); check $? "one line per deploy"

echo "  $passed passed, $failed failed"
(( failed == 0 ))
