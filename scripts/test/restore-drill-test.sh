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
# WHAT THE STUB SERVES. Four fixtures, which are the four questions the script
# asks a database: the restored tally, the live tally, the restored server's
# highest key per table, and live's count up to it. The psql calls are told
# apart by their SQL, which is how a real one differs too.
#
# SINCE #34 there are no monthly partitions to derive: `raw.stream_message` is a
# plain table compared by `id` like any other (#33). The only partitions left
# are those of the set-aside `stream_message_partitioned`, until #35.
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

# Two partitions of the set-aside table, as a restore before #35 still holds them.
p1="stream_message_2026_08"
p2="stream_message_default"

# --- the harness ------------------------------------------------------------
setup() {
  work=$(mktemp -d)
  mkdir -p "$work/bin" "$work/home/.raptor" "$work/backups"
  # Named for the moment the backup STARTED, which is what the script parses.
  dump="$work/backups/raptor-raw-$(date -u +%Y%m%dT%H%M%S)Z.dump"
  printf 'not really a dump\n' > "$dump"
  : > "$work/sent.log"
  # The corpus as every case starts: raw.stream_message restored up to id 300,
  # live 45 rows further on, and 300 of live's rows at or below that id. A case
  # about something else overwrites only what it is about.
  printf 'stream_message\t300\ncapture_file\t7\n' > "$work/restored.tally"
  printf 'stream_message\t345\ncapture_file\t7\n' > "$work/live.tally"
  printf 'stream_message\tid\t300\n' > "$work/restored.keys"
  printf 'stream_message\t300\n' > "$work/live.asof"

  cat > "$work/bin/curl" <<'STUB'
#!/bin/bash
data=""
while (( $# )); do case "$1" in -d) data="$2"; shift 2 ;; *) shift ;; esac; done
printf '%s\n' "$data" >> "$WORK/sent.log"
exit 0
STUB

  # docker: every call the drill makes, answered from the fixtures, told apart
  # by their SQL the way they differ for real.
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
    # #24: the restored server's highest key per ordinary table, and live's count
    # up to it. The live query is kept so a case can assert the cutoff it was
    # given came from the restore, not from somewhere the stub made up.
    if [[ "$sql" == *"restore keys"* ]]; then cat "$WORK/restored.keys" 2>/dev/null
    elif [[ "$sql" == *"as of the dump"* ]]; then
      printf '%s\n' "$sql" > "$WORK/asof.sql"; cat "$WORK/live.asof" 2>/dev/null
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

# --- 1: a clean restore of the plain table passes, and says what it compared
# The baseline: raw.stream_message behind live by a day's capture, no partition
# anywhere, which is what every drill after #35 looks like. It must be OK, not
# "no sealed partition" — that verdict was the drill before #34.
setup
out=$(run)
expect_status "$out" OK
expect_says "$out" "raw.stream_message's 300 row(s)" "plain table"
expect_says "$(cat "$work/asof.sql" 2>/dev/null)" "count(*) from raw.stream_message where id <= '300'" "cutoff from restore"
if printf '%s' "$out" | grep -qi "partition"; then fail "a restore with no partitions mentions them: $out"; else pass; fi
teardown

# --- 2: a message live has BEHIND the dump's last id is a lost message ------
setup
printf 'stream_message\t301\n' > "$work/live.asof"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "stream_message(restored=300 live=301 up to the dump's last row)" "lost message"
teardown

# --- 3: a restore that holds no messages has verified nothing ---------------
# Every other assertion passes here — the sets match and no keyed count differs
# — and the backup still holds none of the corpus.
setup
printf 'stream_message\t0\ncapture_file\t7\n' > "$work/restored.tally"
printf 'spill_file\tid\t1\n' > "$work/restored.keys"
printf 'spill_file\t1\n' > "$work/live.asof"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "compared no rows of raw.stream_message" "empty corpus"
teardown

# --- 4: a missing table aborts ----------------------------------------------
# The row loop iterates over what came back, so this is the one fault it
# cannot see.
setup
printf 'stream_message\t300\n' > "$work/restored.tally"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "missing table(s)" "missing table"
expect_says "$out" "capture_file(live=7)" "missing table"
teardown

# --- 5: ...even an EMPTY one, now that nothing creates tables on a timer ------
# Until #34 an empty partition created after the dump was tolerated (#279).
# Nothing creates one any more, so the tolerance went with the extender.
setup
printf 'stream_message\t345\ncapture_file\t7\nstream_message_2027_01\t0\n' > "$work/live.tally"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "stream_message_2027_01(live=0)" "empty table missing"
teardown

# --- 6: the set-aside table's partitions must match exactly (until #35) -----
setup
printf 'stream_message\t300\ncapture_file\t7\n%s\t100\n%s\t0\n' "$p1" "$p2" > "$work/restored.tally"
printf 'stream_message\t345\ncapture_file\t7\n%s\t100\n%s\t0\n' "$p1" "$p2" > "$work/live.tally"
out=$(run)
expect_status "$out" OK
expect_says "$out" "2 partition(s) of the set-aside table match live exactly" "set-aside"
teardown

setup
printf 'stream_message\t300\ncapture_file\t7\n%s\t99\n' "$p1" > "$work/restored.tally"
printf 'stream_message\t345\ncapture_file\t7\n%s\t100\n' "$p1" > "$work/live.tally"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "$p1(restored=99 live=100)" "set-aside mismatch"
teardown

# --- 7: --scheduled on another day, last verdict OK, does nothing -----------
setup
printf 'OK' > "$work/home/.raptor/restore-drill.state"
out=$(WEEKLY_DAY=$(( $(date +%u) % 7 + 1 )) run --scheduled)
expect_status "$out" SKIP
if [[ -s "$work/sent.log" ]]; then fail "a skipped run alerted"; else pass; fi
teardown

# --- 8: --scheduled on another day RETRIES while the last verdict is red ----
# A red drill re-answers tomorrow (#279). And because it recovers, it says so.
setup
printf 'FAILED' > "$work/home/.raptor/restore-drill.state"
out=$(WEEKLY_DAY=$(( $(date +%u) % 7 + 1 )) run --scheduled)
expect_status "$out" OK
grep -q "recovered" "$work/sent.log" || fail "recovery was not announced"
grep -q "recovered" "$work/sent.log" && pass
teardown

# --- 9: a hand-run is never skipped -----------------------------------------
setup
printf 'OK' > "$work/home/.raptor/restore-drill.state"
out=$(WEEKLY_DAY=$(( $(date +%u) % 7 + 1 )) run)
expect_status "$out" OK
teardown

# --- 10: a failed pg_restore is still a failed drill ------------------------
setup
: > "$work/restore-fails"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "pg_restore of" "failed restore"
teardown

# --- 11: #24 — rows written to ordinary tables after the dump are drift -----
# Shaped like 2026-09-27: the backup ran long, the drill fired while it was
# still a .part, and drilled the day-old dump. Counted up to the dump's own
# highest key, live agrees.
setup
printf 'stream_message\t300\ncapture_session\t134\nmarket_scope\t2830\n' > "$work/restored.tally"
printf 'stream_message\t345\ncapture_session\t137\nmarket_scope\t2846\n' > "$work/live.tally"
printf 'stream_message\tid\t300\ncapture_session\tid\t134\nmarket_scope\tfirst_seen_at\t2026-01-02 03:04:05.678+00\n' \
  > "$work/restored.keys"
printf 'stream_message\t300\ncapture_session\t134\nmarket_scope\t2830\n' > "$work/live.asof"
touch -t 209912310000 "$work/backups/raptor-raw-20991231T000000Z.dump.part"
out=$(run)
expect_status "$out" OK
expect_says "$out" "2 other table(s) match live up to the dump's last row" "post-dump rows"
expect_says "$out" "a newer backup was still being written" "stale dump named"
expect_says "$(cat "$work/asof.sql" 2>/dev/null)" "id <= '134'" "cutoff from restore"
expect_says "$(cat "$work/asof.sql" 2>/dev/null)" "first_seen_at <= '2026-01-02 03:04:05.678+00'" "cutoff from restore"
teardown

# --- 12: a table with no key known is compared loosely, and says so ---------
setup
printf 'stream_message\t300\nsome_new_table\t5\n' > "$work/restored.tally"
printf 'stream_message\t345\nsome_new_table\t8\n' > "$work/live.tally"
out=$(run)
expect_status "$out" OK
expect_says "$out" "no key known, so only restored <= live: some_new_table" "unkeyed table"
teardown

# --- 13: ...but even loosely, a restore that holds MORE than live is a fault
setup
printf 'stream_message\t300\nsome_new_table\t9\n' > "$work/restored.tally"
printf 'stream_message\t345\nsome_new_table\t8\n' > "$work/live.tally"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "some_new_table(restored=9 live=8)" "unkeyed table shrank"
teardown

# --- 14: a failed live count does not demote every table to loose -----------
setup
: > "$work/live.asof"
out=$(run)
expect_status "$out" FAILED
expect_says "$out" "could not count live's ordinary tables" "failed live count"
teardown

echo
echo "  $passed passed, $failed failed"
[[ "$failed" == "0" ]] || exit 1
