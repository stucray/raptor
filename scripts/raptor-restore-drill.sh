#!/usr/bin/env bash
#
# Prove the backup can actually be restored. Slice 2 of #181.
#
# WHY THIS EXISTS SEPARATELY FROM THE BACKUP. `raptor-backup.sh` verifies that
# the archive decompresses — every data block is read and the bytes are intact.
# That is NOT the same claim as "this can be restored into a working database",
# and the gap between the two is the single most common way a backup strategy
# fails: nobody discovers the restore is broken until the moment they need it.
# A backup nobody has ever restored is a hypothesis, not a copy.
#
# WHAT IT DOES. Restores the newest dump into a THROWAWAY Postgres container —
# its own name, its own volume, no published port — compares what came back
# against the live database table by table, and destroys the container either
# way. It never connects to the live database container except to read counts, and it
# never writes to it at all.
#
# DRIFT, AND WHY THE COMPARISON IS SPLIT. Live capture keeps appending, so a
# naive "restored == live" would fail for any month still being written. But
# `raw.stream_message` is monthly RANGE(pt) partitioned and a SEALED month never
# changes again, so:
#   - sealed partitions (any month before the current one) must match EXACTLY;
#     a mismatch there is a real fault, and the bulk of the corpus lives here.
#   - the current partition must be non-empty and no larger than live, which is
#     the only honest statement when rows are arriving between the dump and now.
# That is drift-free without needing to know the dump's exact snapshot instant.
#
# AND THE SAME DRIFT HAPPENS TO THE TABLE SET, WHICH IS WHY THE DUMP'S INSTANT
# IS READ AFTER ALL (#279). A live table absent from the restore is normally the
# one fault the row loop cannot see, so it aborts — but #272 shipped a daily job
# whose entire purpose is creating partitions, and a partition created after the
# dump was taken is absent from it correctly. That is drift of exactly the kind
# the row comparison already tolerates, and treating it as a fault cost a week of
# red: `finish FAILED` exits, so the run that alerted never compared a single
# sealed partition. The split is by the partition's own bound, read from the live
# catalogue: a partition whose range begins at or after the dump instant (or the
# DEFAULT partition) may be missing, and only if it is EMPTY in live, so no row
# can hide behind the tolerance. Anything else missing is still a hard stop.
#
# WHEN. Weekly, in the same UTC quiet window as the backup and shortly after it
# — after the previous card has finished and before scope opens for the next.
# It is bootstrapped DAILY and skips its own run unless it is the weekly day or
# the last verdict was not OK: a check that can only re-answer in seven days is
# one whose red state stops carrying information long before it clears (#279).
# The skip costs a process start. See the schedule note in the plist.
#
# DEPLOY: `bin/deploy-agents`. The launchd job runs a copy OUTSIDE the repo, in
# the state directory (RAPTOR_STATE_DIR), so a branch checkout cannot rewrite or delete it mid-run — that
# part was always right; doing the copy by hand was not (#187). The deploy is
# idempotent, and `bin/deploy-agents --check` (which `bin/up` runs) reports a
# deployed copy that has drifted from this one. A stale copy does not fail: it
# reports OK from old code, which is indistinguishable from working.
#
# Configuration: ops.env (raptor-ops-env.sh) — RAPTOR_BACKUP_DIR,
#                  RAPTOR_BACKUP_PREFIX, RAPTOR_SECRETS, RAPTOR_DB_*.
# Overridable env: POSTGRES_CONTAINER,
#                  POSTGRES_IMAGE, DRILL_CONTAINER, MIN_FREE_GB,
#                  NTFY_ENABLED, NTFY_TOPIC, NTFY_BASE_URL, DRILL_PATH_PREFIX.

# Configuration first, before any default below reads a setting: ops.env
# can only supply a value the script has not already defaulted.
# shellcheck source=raptor-ops-env.sh
source "$(dirname "$0")/raptor-ops-env.sh"

set -uo pipefail
export PATH="${DRILL_PATH_PREFIX:+$DRILL_PATH_PREFIX:}/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export SOPS_AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-$HOME/Library/Application Support/sops/age/keys.txt}"

SECRETS="$RAPTOR_SECRETS"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-$RAPTOR_POSTGRES_CONTAINER}"
# Must match the server that wrote the dump; pg_restore refuses an archive from
# a newer major version. Read from compose rather than hardcoded twice.
POSTGRES_IMAGE="${POSTGRES_IMAGE:-postgres:17}"
DRILL_CONTAINER="${DRILL_CONTAINER:-raptor-restore-drill}"
BACKUP_DIR="$RAPTOR_BACKUP_DIR"
# The dump file stem. paddock wrote `paddock-raw-<stamp>.dump`; a deployment
# taking over its backups directory sets RAPTOR_BACKUP_PREFIX=paddock-raw so the
# drill and the retention sweep still see them.
BACKUP_PREFIX="${RAPTOR_BACKUP_PREFIX:-raptor-raw}"
STATE_DIR="$RAPTOR_STATE_DIR"
STATE_FILE="$STATE_DIR/restore-drill.state"
MIN_FREE_GB="${MIN_FREE_GB:-30}"
mkdir -p "$STATE_DIR"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# Read before anything expensive: the retry gate below needs it, and so does
# every `finish` call after it.
prev=$(cat "$STATE_FILE" 2>/dev/null || echo "OK")

# RUN ON MY SLOT, OR ANY DAY WHILE RED (#279). launchd fires this daily; the
# weekly cadence is enforced here instead, so that a FAILED verdict re-answers
# tomorrow rather than in seven days. `--scheduled` is what the plist passes —
# a hand-run has no argument and always drills, which is what someone typing it
# means. Nothing is written and nothing is alerted on the skip path: a skipped
# run must not look like a verdict.
if [[ "${1:-}" == "--scheduled" ]]; then
  # %u is 1=Monday..7=Sunday in LOCAL time, matching launchd's local schedule.
  if [[ "$(date +%u)" != "${DRILL_WEEKLY_DAY:-7}" && "$prev" == "OK" ]]; then
    echo "$(ts) status=SKIP prev=$prev — not the weekly slot and the last verdict was OK"
    exit 0
  fi
fi

NTFY_ENABLED="${NTFY_ENABLED:-}"; NTFY_TOPIC="${NTFY_TOPIC:-}"
if [[ -z "$NTFY_ENABLED" && -z "$NTFY_TOPIC" ]] \
   && [[ -n "$SECRETS" ]] \
   && creds_json=$(sops decrypt --output-type json "$SECRETS" 2>/dev/null); then
  NTFY_ENABLED=$(printf '%s' "$creds_json" | jq -r '."ntfy-enabled" // empty')
  NTFY_TOPIC=$(printf '%s'   "$creds_json" | jq -r '."ntfy-topic"   // empty')
fi
unset creds_json
NTFY_BASE_URL="${NTFY_BASE_URL:-https://ntfy.sh}"

send() { # $1 = message
  if [[ "$NTFY_ENABLED" != "true" || -z "$NTFY_TOPIC" ]]; then
    echo "$(ts) [skip-alert: ntfy not enabled / no topic in sops] $1"
    return 0
  fi
  curl -fsS --max-time 10 -H "Title: raptor restore drill" -H "Priority: high" \
      -d "$1" "${NTFY_BASE_URL}/${NTFY_TOPIC}" -o /dev/null \
    && echo "$(ts) [alerted] $1" \
    || echo "$(ts) [ntfy-send-FAILED] $1"
}

# Torn down on EVERY exit path, including the failures below and a SIGTERM from
# launchd. A drill that leaves a 12 GB container and volume behind after a bad
# night would itself become the thing that fills the disk.
teardown() {
  docker rm -f "$DRILL_CONTAINER" >/dev/null 2>&1
  docker volume rm "${DRILL_CONTAINER}-data" >/dev/null 2>&1
}
trap teardown EXIT INT TERM

finish() { # $1 = OK|FAILED, $2 = message
  if [[ "$1" != "OK" && "$1" != "$prev" ]]; then
    send "⚠️ raptor restore drill: $2 ($(ts))"
  elif [[ "$1" == "OK" && "$prev" != "OK" ]]; then
    send "✅ raptor restore drill: recovered ($prev → OK) ($(ts))"
  fi
  printf '%s' "$1" > "$STATE_FILE"
  echo "$(ts) status=$1 prev=$prev — $2"
  [[ "$1" == "OK" ]] && exit 0 || exit 1
}

# --- preflight -------------------------------------------------------------
dump=$(ls -t "$BACKUP_DIR/$BACKUP_PREFIX"-*.dump 2>/dev/null | head -1)
if [[ -z "$dump" ]]; then
  finish FAILED "no dump found in $BACKUP_DIR — nothing to drill, and that is itself the finding"
fi
# A dump nobody has refreshed is a stale copy pretending to be a backup. Two
# days allows for one missed nightly run without crying wolf.
age_days=$(( ( $(date +%s) - $(stat -f%m "$dump") ) / 86400 ))
if (( age_days > 2 )); then
  finish FAILED "newest dump $(basename "$dump") is ${age_days} days old — the nightly backup has stopped"
fi
# THE INSTANT THE DUMP IS A PICTURE OF, which is what tells a table created
# afterwards apart from a table that failed to restore (#279). `raptor-backup.sh`
# names the file for the moment it started, and pg_dump's snapshot is taken at
# that moment, so the filename is the honest reading and needs no second source.
# mtime is the fallback and is the moment it FINISHED — later, so it tolerates
# strictly less, which is the safe direction to be wrong in.
if [[ "$(basename "$dump")" =~ ^"$BACKUP_PREFIX"-([0-9]{8})T([0-9]{6})Z\.dump$ ]]; then
  d="${BASH_REMATCH[1]}"; t="${BASH_REMATCH[2]}"
  dump_instant="${d:0:4}-${d:4:2}-${d:6:2} ${t:0:2}:${t:2:2}:${t:4:2}+00"
else
  dump_instant=$(date -u -r "$(stat -f%m "$dump")" +"%Y-%m-%d %H:%M:%S+00")
fi

free_gb=$(df -g "$HOME" | awk 'NR==2 {print $4}')
if [[ -z "$free_gb" ]] || (( free_gb < MIN_FREE_GB )); then
  finish FAILED "only ${free_gb:-unknown} GB free (need ${MIN_FREE_GB}) — refusing to restore rather than fill the disk capture writes to"
fi
if ! docker exec "$POSTGRES_CONTAINER" pg_isready -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" >/dev/null 2>&1; then
  finish FAILED "live $POSTGRES_CONTAINER is not answering, so there is nothing to compare against"
fi

# --- stand up a throwaway server -------------------------------------------
teardown   # in case a previous run was killed before its trap fired
if ! docker run -d --name "$DRILL_CONTAINER" \
      -v "${DRILL_CONTAINER}-data:/var/lib/postgresql/data" \
      -e POSTGRES_USER="$RAPTOR_DB_USER" -e POSTGRES_PASSWORD=drill -e POSTGRES_DB="$RAPTOR_DB_NAME" \
      "$POSTGRES_IMAGE" >/dev/null 2>&1; then
  finish FAILED "could not start the throwaway $POSTGRES_IMAGE container"
fi
for _ in $(seq 1 60); do
  docker exec "$DRILL_CONTAINER" pg_isready -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" >/dev/null 2>&1 && break
  sleep 2
done
if ! docker exec "$DRILL_CONTAINER" pg_isready -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" >/dev/null 2>&1; then
  finish FAILED "throwaway container never became ready"
fi

# --- restore ---------------------------------------------------------------
# --no-owner/--no-privileges: the dump references roles (paddock_reader, and the
# grants that keep the read side off `raw`) that do not exist in a bare
# container. Their absence says nothing about whether the DATA restores, which
# is what this drill is asking, so they are skipped rather than recreated.
err=$(mktemp)
if ! docker exec -i "$DRILL_CONTAINER" \
      pg_restore -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" --no-owner --no-privileges \
      < "$dump" > /dev/null 2>"$err"; then
  msg=$(head -c 300 "$err"); rm -f "$err"
  finish FAILED "pg_restore of $(basename "$dump") failed: ${msg:-no stderr}"
fi
rm -f "$err"

# --- compare ---------------------------------------------------------------
# Real counts, not estimates: pg_class.reltuples is a planner statistic and can
# be stale or -1 on a table that has not been analysed, which would let a drill
# pass against a table that restored empty.
tally() { # $1 = container -> "relname<TAB>exact count"
  docker exec -i "$1" psql -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" -At -F$'\t' <<'SQL'
select relname, cnt from (
  select c.relname,
         (xpath('/row/c/text()',
                query_to_xml(format('select count(*) as c from raw.%I', c.relname),
                             false, true, '')))[1]::text::bigint as cnt
  from pg_class c join pg_namespace n on n.oid = c.relnamespace
  where n.nspname = 'raw' and c.relkind = 'r'
) t order by relname;
SQL
}

current_partition="stream_message_$(date -u +%Y_%m)"
restored=$(tally "$DRILL_CONTAINER")
live=$(tally "$POSTGRES_CONTAINER")
if [[ -z "$restored" ]]; then
  finish FAILED "restored database reported no tables in schema raw"
fi

# Partitions of raw.stream_message that BEGIN at or after the dump instant, plus
# the DEFAULT partition — the ones whose absence from the restore is drift rather
# than fault. Read from the live catalogue rather than parsed out of the names:
# the bounds in this database sit at 17:00Z, not midnight (V3 rendered date
# bounds in a UTC+7 session), so a name says the month and not the range.
#
# The DEFAULT partition renders as the bare word `DEFAULT`, and `''::timestamptz`
# is an ERROR rather than a NULL — hence `case`, which provably guards the cast,
# where an `or` leaves the evaluation order to the planner.
late_partitions() {
  docker exec -i "$POSTGRES_CONTAINER" psql -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" -At \
      -v dump="$dump_instant" <<'SQL'
select c.relname
from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  join pg_inherits i  on i.inhrelid = c.oid
  join pg_class p     on p.oid = i.inhparent
where n.nspname = 'raw' and p.relname = 'stream_message'
  and case
        when pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT' then true
        else (substring(pg_get_expr(c.relpartbound, c.oid)
                        from 'FROM \(''([^'']+)''\)'))::timestamptz >= :'dump'::timestamptz
      end
order by 1;
SQL
}

# A table MISSING from the restore is the one fault the row-by-row loop below
# cannot see, because it iterates over what came back — fewer tables simply
# means fewer comparisons, all of which pass. Assert the sets match first, or
# the drill is silent about exactly the failure it exists to catch.
#
# The single exception is a partition that did not exist when the dump was taken
# (#279): #272's extender creates one most days, and the DEFAULT partition
# arrived with it. Tolerated only while EMPTY in live — a row cannot hide behind
# a tolerance that requires there to be no rows — and named in the final message
# either way, because a drill that quietly forgives things teaches nothing.
missing=$(comm -13 \
  <(printf '%s\n' "$restored" | cut -f1 | sort) \
  <(printf '%s\n' "$live"     | cut -f1 | sort))
tolerated=""
if [[ -n "${missing//[[:space:]]/}" ]]; then
  late=$(late_partitions)
  faults=""
  while IFS= read -r tbl; do
    [[ -z "$tbl" ]] && continue
    live_n=$(printf '%s\n' "$live" | awk -F'\t' -v t="$tbl" '$1==t {print $2}')
    live_n=${live_n:-0}
    if printf '%s\n' "$late" | grep -qxF "$tbl" && (( live_n == 0 )); then
      tolerated="$tolerated $tbl"
    else
      faults="$faults $tbl(live=$live_n)"
    fi
  done <<< "$missing"
  if [[ -n "$faults" ]]; then
    finish FAILED "restore is missing table(s) present in live raw:$faults"
  fi
fi

mismatches=""; checked=0; rows=0; sealed=0
while IFS=$'\t' read -r tbl n; do
  [[ -z "$tbl" ]] && continue
  live_n=$(printf '%s\n' "$live" | awk -F'\t' -v t="$tbl" '$1==t {print $2}')
  live_n=${live_n:-0}
  checked=$((checked + 1)); rows=$((rows + n))
  # Counted so the exact-match claim can be asserted rather than assumed: the
  # sealed months ARE the corpus, and a run that compared none of them has not
  # verified the backup whatever else it did.
  if [[ "$tbl" =~ ^stream_message_[0-9]{4}_[0-9]{2}$ && "$tbl" != "$current_partition" ]]; then
    sealed=$((sealed + 1))
  fi
  if [[ "$tbl" == "$current_partition" ]]; then
    # Still being written; only a shrink or an empty restore is a fault.
    if (( n == 0 )) || (( n > live_n )); then
      mismatches="$mismatches $tbl(restored=$n live=$live_n, current partition)"
    fi
  elif (( n != live_n )); then
    mismatches="$mismatches $tbl(restored=$n live=$live_n)"
  fi
done <<< "$restored"

if [[ -n "$mismatches" ]]; then
  finish FAILED "restore of $(basename "$dump") does not match live:$mismatches"
fi
if (( rows == 0 )); then
  finish FAILED "restore produced $checked table(s) but zero rows in total"
fi
# The other half of the vacuous-loop guard above. The set check proves nothing
# is missing; this proves there was something to compare — a restore of an empty
# partition set would otherwise satisfy every assertion in this script.
if (( sealed == 0 )); then
  finish FAILED "restore produced $checked table(s) but no sealed partition of raw.stream_message, so nothing was actually verified"
fi

finish OK "restored $(basename "$dump") into a throwaway server: $checked raw table(s), $rows rows, $sealed sealed partition(s) match live${tolerated:+; absent from the dump and empty in live, so tolerated:$tolerated}"
