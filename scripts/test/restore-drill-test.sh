#!/bin/bash
# Tests for raptor-restore-drill.sh — the second shell tests in this repo, and
# they exist for the same reason as the first (scripts/test/heartbeat-test.sh):
# this is a component whose failures are invisible by construction. It runs from
# launchd against a backup nobody is watching, and the only thing standing
# between 40M rows of unreplayable stream and a restore that silently stopped
# working is whether THIS script's assertions are the ones it claims to make.
#
# #279 is the proof. The drill reported FAILED, alerted, and had verified
# nothing at all — the set check it aborted on sits before the row comparison,
# so a week of red never once compared a sealed partition. The bug was not in
# the backup. It was here, in a comparison that handled row drift and not
# table-set drift, against a job (#272) whose entire purpose is creating tables.
#
# HOW. `DRILL_PATH_PREFIX` has been in the script since it was written and was
# never used by anything; it prepends to PATH, so a directory of stubs takes
# over `docker` and `curl` while the real `awk`, `comm`, `sort` and `date` keep
# working — the comparison logic under test is made of those, and stubbing them
# would test the stub. HOME is redirected too, so the state file lands in the
# case's own temp directory and one case cannot see another's de-dupe.
#
# WHAT THE STUB SERVES. Three fixtures, which are the three questions the script
# asks a database: the restored tally, the live tally, and which partitions the
# live catalogue says begin at or after the dump instant. The psql calls are
# told apart by their SQL, which is how a real one differs too.
set -uo pipefail
# No operator config may leak into a test: every setting comes from the case.
# That means the ENVIRONMENT as well as ops.env — the environment beats the file
# by design, so a setting exported by whatever launched this suite (the deploy
# gate, a shell with a shadow's settings) would otherwise steer the cases.
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^(RAPTOR_|RESTART_|NTFY_|HEALTH_URL$|CAPTURE_URL$)')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
# Overridable so the suite can be pointed at the DEPLOYED copy in the state directory,
# and so a fix can be shown to fail against the version before it.
DRILL="${DRILL:-$PWD/scripts/raptor-restore-drill.sh}"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }

# Months, derived rather than written down: a fixture with a hardcoded month
# becomes a test about the calendar the day the current partition catches up.
cur="stream_message_$(date -u +%Y_%m)"
m1="stream_message_$(date -u -v-1m +%Y_%m)"
m2="stream_message_$(date -u -v-2m +%Y_%m)"
future="stream_message_$(date -u -v+11m +%Y_%m)"

# --- the harness ------------------------------------------------------------
setup() {
  work=$(mktemp -d)
  mkdir -p "$work/bin" "$work/home/.raptor" "$work/backups"
  # Named for the moment the backup STARTED, which is what the script parses.
  dump="$work/backups/raptor-raw-$(date -u +%Y%m%dT%H%M%S)Z.dump"
  printf 'not really a dump\n' > "$dump"
  : > "$work/sent.log"

  cat > "$work/bin/curl" <<'STUB'
#!/bin/bash
data=""
while (( $# )); do case "$1" in -d) data="$2"; shift 2 ;; *) shift ;; esac; done
printf '%s\n' "$data" >> "$WORK/sent.log"
exit 0
STUB

  # docker: every call the drill makes, answered from the fixtures. The two
  # psql queries are distinguished the way they differ for real — one reads
  # pg_class.relpartbound, the other counts rows.
  cat > "$work/bin/docker" <<'STUB'
#!/bin/bash
case "${1:-}" in
  exec) shift ;;
  *)    exit 0 ;;    # run / rm / volume rm all succeed
esac
container=""; prog=""
while (( $# )); do
  case "$1" in
    -i|-t|-it) shift ;;
    *) if [[ -z "$container" ]]; then container="$1"; else prog="$1"; break; fi; shift ;;
  esac
done
case "$prog" in
  pg_isready) exit 0 ;;
  pg_restore)
    cat > /dev/null
    if [[ -f "$WORK/restore-fails" ]]; then
      echo "pg_restore: error: could not execute query" >&2; exit 1
    fi
    exit 0 ;;
  psql)
    sql=$(cat)
    if [[ "$sql" == *relpartbound* ]]; then cat "$WORK/late.list"
    elif [[ "$container" == "drill" ]]; then cat "$WORK/restored.tally"
    else cat "$WORK/live.tally"
    fi
    exit 0 ;;
esac
exit 0
STUB
  chmod +x "$work/bin/"*
}

run() { # $@ = arguments to the drill (e.g. --scheduled)
  WORK="$work" HOME="$work/home" DRILL_PATH_PREFIX="$work/bin" \
    NTFY_ENABLED=true NTFY_TOPIC=test-topic \
    RAPTOR_BACKUP_DIR="$work/backups" \
    POSTGRES_CONTAINER=live DRILL_CONTAINER=drill \
    MIN_FREE_GB=0 DRILL_WEEKLY_DAY="${WEEKLY_DAY:-$(date +%u)}" \
    bash "$DRILL" "$@" 2>&1
}

teardown() { rm -rf "$work"; }

expect_status() { # $1 = output  $2 = expected status word
  printf '%s' "$1" | grep -q "status=$2" \
    || fail "expected status=$2, got: $(printf '%s' "$1" | tail -1)"
  printf '%s' "$1" | grep -q "status=$2" && pass
}
expect_says() { # $1 = output  $2 = substring  $3 = label
  if ! printf '%s' "$1" | grep -qF "$2"; then
    fail "$3: expected the verdict to mention '$2', got: $(printf '%s' "$1" | tail -1)"
  else pass; fi
}

echo "restore drill tests"

# --- 1: a clean restore passes, and says what it compared -------------------
# The baseline every other case is a variation on: two sealed months, the
# current one still being written and legitimately behind live.
setup
printf '%s\t100\n%s\t200\n%s\t30\ncapture_file\t7\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\ncapture_file\t7\n' "$m2" "$m1" "$cur" > "$work/live.tally"
: > "$work/late.list"
out=$(run)
expect_status "$out" OK
expect_says "$out" "2 sealed partition(s) match live" "baseline"
teardown

# --- 2: #279 — partitions created after the dump are drift, not fault -------
# The regression. Live has three tables the dump cannot have: two monthly
# partitions the extender made and the DEFAULT partition V28 added. All empty.
# Before the fix this aborted with "restore is missing table(s)" having compared
# nothing; the assertion that matters is not just OK but that the sealed
# partitions were reached.
setup
printf '%s\t100\n%s\t200\n%s\t30\ncapture_file\t7\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\ncapture_file\t7\n%s\t0\nstream_message_default\t0\n' \
  "$m2" "$m1" "$cur" "$future" > "$work/live.tally"
printf '%s\nstream_message_default\n' "$future" > "$work/late.list"
out=$(run)
expect_status "$out" OK
expect_says "$out" "2 sealed partition(s) match live" "post-dump partitions"
expect_says "$out" "tolerated: $future stream_message_default" "post-dump partitions"
if [[ -s "$work/sent.log" ]]; then fail "tolerated drift alerted: $(cat "$work/sent.log")"; else pass; fi
teardown

# --- 3: a missing table that is NOT a post-dump partition still aborts ------
# The witness argument the original comment makes, kept intact: the row loop
# iterates over what came back, so this is the one fault it cannot see.
setup
printf '%s\t100\n%s\t200\n%s\t30\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\ncapture_file\t7\n' "$m2" "$m1" "$cur" > "$work/live.tally"
: > "$work/late.list"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "missing table(s)" "missing non-partition"
expect_says "$out" "capture_file(live=7)" "missing non-partition"
teardown

# --- 4: a post-dump partition with ROWS in live is a fault ------------------
# The tolerance is "empty in live" precisely so that no row can hide behind it.
# A partition the dump missed that has since been written to means the dump is
# older than the script believes, and that is worth stopping for.
setup
printf '%s\t100\n%s\t200\n%s\t30\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\n%s\t9\n' "$m2" "$m1" "$cur" "$future" > "$work/live.tally"
printf '%s\n' "$future" > "$work/late.list"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "$future(live=9)" "non-empty post-dump partition"
teardown

# --- 5: the real fault is found THROUGH the tolerated drift -----------------
# The whole point of #279: a sealed month that does not match must still be
# caught on a day when the extender has also just created a partition. This is
# the case the week of red could not have reported.
setup
printf '%s\t99\n%s\t200\n%s\t30\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\n%s\t0\n' "$m2" "$m1" "$cur" "$future" > "$work/live.tally"
printf '%s\n' "$future" > "$work/late.list"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "$m2(restored=99 live=100)" "sealed mismatch behind drift"
teardown

# --- 6: a restore with no sealed partition has verified nothing -------------
# The other half of the vacuous-loop guard. Every assertion in the script passes
# here — the sets match, no count differs, rows are non-zero — and the backup
# still holds none of the corpus.
setup
printf '%s\t30\ncapture_file\t7\n' "$cur" > "$work/restored.tally"
printf '%s\t45\ncapture_file\t7\n' "$cur" > "$work/live.tally"
: > "$work/late.list"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "no sealed partition" "vacuous restore"
teardown

# --- 7: a restore that shrank the current partition to empty is a fault -----
# Unchanged behaviour, asserted because case 1 depends on the current partition
# being allowed to differ and that must not become "allowed to be anything".
setup
printf '%s\t100\n%s\t200\n%s\t0\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\n' "$m2" "$m1" "$cur" > "$work/live.tally"
: > "$work/late.list"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "current partition" "empty current partition"
teardown

# --- 8: --scheduled on another day, last verdict OK, does nothing -----------
setup
printf '%s\t100\n%s\t200\n%s\t30\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\n' "$m2" "$m1" "$cur" > "$work/live.tally"
: > "$work/late.list"
printf 'OK' > "$work/home/.raptor/restore-drill.state"
out=$(WEEKLY_DAY=$(( $(date +%u) % 7 + 1 )) run --scheduled)
expect_status "$out" SKIP
if [[ -s "$work/sent.log" ]]; then fail "a skipped run alerted"; else pass; fi
teardown

# --- 9: --scheduled on another day RETRIES while the last verdict is red ----
# The half of #279 that is about the seven days, not the comparison: a red
# drill re-answers tomorrow. And because it recovers, it says so.
setup
printf '%s\t100\n%s\t200\n%s\t30\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\n' "$m2" "$m1" "$cur" > "$work/live.tally"
: > "$work/late.list"
printf 'FAILED' > "$work/home/.raptor/restore-drill.state"
out=$(WEEKLY_DAY=$(( $(date +%u) % 7 + 1 )) run --scheduled)
expect_status "$out" OK
grep -q "recovered" "$work/sent.log" || fail "recovery was not announced"
grep -q "recovered" "$work/sent.log" && pass
teardown

# --- 10: a hand-run is never skipped ----------------------------------------
# No argument means a person typed it, and a person typing it means run.
setup
printf '%s\t100\n%s\t200\n%s\t30\n' "$m2" "$m1" "$cur" > "$work/restored.tally"
printf '%s\t100\n%s\t200\n%s\t45\n' "$m2" "$m1" "$cur" > "$work/live.tally"
: > "$work/late.list"
printf 'OK' > "$work/home/.raptor/restore-drill.state"
out=$(WEEKLY_DAY=$(( $(date +%u) % 7 + 1 )) run)
expect_status "$out" OK
teardown

# --- 11: a failed pg_restore is still a failed drill ------------------------
setup
printf '%s\t100\n' "$m1" > "$work/restored.tally"
printf '%s\t100\n' "$m1" > "$work/live.tally"
: > "$work/late.list"
: > "$work/restore-fails"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "pg_restore of" "failed restore"
teardown

echo
echo "  $passed passed, $failed failed"
[[ "$failed" == "0" ]] || exit 1
