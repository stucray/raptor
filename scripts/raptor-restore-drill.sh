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
# DRIFT. Live capture keeps appending, so a naive "restored == live" would fail
# for every table still being written. So every raw table with a known key must
# match live counted only up to the highest key the RESTORE holds (#24):
#   - `raw.stream_message` by its surrogate `id` (#33). It is the corpus, and a
#     drill that compared none of it has verified nothing, so a restore in which
#     it holds no rows fails outright.
#   - the other ordinary tables (sessions, gaps, scope, file ledgers) likewise.
#     They were once compared exactly, which held only while the drill ran
#     minutes after the backup it drilled; on 2026-09-27 the backup ran long, the
#     drill found only the previous day's dump, and went red about a backup that
#     was fine.
# The key is the identity `id`, or `first_seen_at` for market_scope, which has
# none; both are read from the restored server, so no clock is trusted — the
# dump's filename is the host's, and the VM's can wake an hour behind. A table
# with no known key is held only to restored <= live, and named.
#
# The table SET must match too, with no exceptions. Until #33 a partition
# created after the dump was legitimately absent from it (#279) and was
# tolerated; nothing creates tables on a timer any more, so a live table the
# restore lacks is a fault again, always.
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
# Which dump is being drilled, and why, when it is not the one a reader would
# assume (#24). A .part newer than the chosen dump means tonight's backup had
# not finished, so this drill is answering about yesterday's — worth saying,
# because it was exactly that overlap that first exposed the ordinary tables.
dump_note=""
if [[ -n "$(find "$BACKUP_DIR" -name "$BACKUP_PREFIX-*.dump.part*" -newer "$dump" 2>/dev/null)" ]]; then
  dump_note=" (a newer backup was still being written, so this is the previous one)"
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

restored=$(tally "$DRILL_CONTAINER")
live=$(tally "$POSTGRES_CONTAINER")
if [[ -z "$restored" ]]; then
  finish FAILED "restored database reported no tables in schema raw"
fi

# The ordinary tables' cutoffs (#24), read from the RESTORED server: for each
# raw table that is not a partition, its key column and the highest value the
# dump holds -> "relname<TAB>key<TAB>max as text". The key is `id` wherever
# there is one; market_scope has none and is keyed by `first_seen_at`, which
# the database stamps with its own now(). A table with neither gets no row and
# is compared loosely below. An empty table gets an empty max.
restore_keys() {
  docker exec -i "$DRILL_CONTAINER" psql -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" -At -F$'\t' <<'SQL'
-- restore keys
select c.relname, a.attname,
       (xpath('/row/m/text()',
              query_to_xml(format('select max(%I)::text as m from raw.%I', a.attname, c.relname),
                           false, true, '')))[1]::text
from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  join pg_attribute a on a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped
where n.nspname = 'raw' and c.relkind = 'r' and not c.relispartition
  and a.attname = case when c.relname = 'market_scope' then 'first_seen_at' else 'id' end
order by 1;
SQL
}

# Live's count of each keyed table up to the restore's cutoff -> "relname<TAB>n".
# The cutoff is interpolated, because it is a different table and column per
# row; everything interpolated came out of the restored catalogue and is checked
# against a strict shape first, so a value that looks like anything else is
# dropped and that table falls through to the loose comparison — named, not
# silent. The literal is left untyped so it takes the column's own type, and a
# timestamptz rendered as text carries its offset, so live's session zone
# cannot move it.
live_as_of() { # $1 = restore_keys output
  local sql="" tbl key max
  while IFS=$'\t' read -r tbl key max; do
    [[ "$tbl" =~ ^[a-z_][a-z0-9_]*$ && "$key" =~ ^[a-z_][a-z0-9_]*$ ]] || continue
    if [[ -z "$max" ]]; then
      sql="$sql${sql:+ union all }select '$tbl', 0::bigint"
    elif [[ "$max" =~ ^[0-9][0-9\ :.+-]*$ ]]; then
      sql="$sql${sql:+ union all }select '$tbl', count(*) from raw.$tbl where $key <= '$max'"
    fi
  done <<< "$1"
  [[ -z "$sql" ]] && return 0
  printf -- '-- as of the dump\n%s;\n' "$sql" \
    | docker exec -i "$POSTGRES_CONTAINER" psql -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" -At -F$'\t'
}

# Either query failing must not quietly demote every ordinary table to the loose
# comparison — that would be a drill that still says OK having checked less,
# which is the failure shape #279 was.
keys=$(restore_keys)
if [[ -z "$keys" ]]; then
  finish FAILED "the restore reported no key for any ordinary raw table, so none could be compared"
fi
as_of=$(live_as_of "$keys")
if [[ -z "$as_of" ]]; then
  finish FAILED "could not count live's ordinary tables up to the dump's last row"
fi

# A table MISSING from the restore is the one fault the row-by-row loop below
# cannot see, because it iterates over what came back — fewer tables simply
# means fewer comparisons, all of which pass. Assert the sets match first, or
# the drill is silent about exactly the failure it exists to catch.
missing=$(comm -13 \
  <(printf '%s\n' "$restored" | cut -f1 | sort) \
  <(printf '%s\n' "$live"     | cut -f1 | sort))
if [[ -n "${missing//[[:space:]]/}" ]]; then
  faults=""
  while IFS= read -r tbl; do
    [[ -z "$tbl" ]] && continue
    live_n=$(printf '%s\n' "$live" | awk -F'\t' -v t="$tbl" '$1==t {print $2}')
    faults="$faults $tbl(live=${live_n:-0})"
  done <<< "$missing"
  finish FAILED "restore is missing table(s) present in live raw:$faults"
fi

mismatches=""; checked=0; rows=0; messages=0; ordinary=0; loose=""
while IFS=$'\t' read -r tbl n; do
  [[ -z "$tbl" ]] && continue
  live_n=$(printf '%s\n' "$live" | awk -F'\t' -v t="$tbl" '$1==t {print $2}')
  live_n=${live_n:-0}
  checked=$((checked + 1)); rows=$((rows + n))
  upto=$(printf '%s\n' "$as_of" | awk -F'\t' -v t="$tbl" '$1==t {print $2}')
  if [[ -n "$upto" ]]; then
    # Keyed (#24): exact, but only up to the dump's last row.
    if [[ "$tbl" == "stream_message" ]]; then
      messages=$n
    else
      ordinary=$((ordinary + 1))
    fi
    if (( n != upto )); then
      mismatches="$mismatches $tbl(restored=$n live=$upto up to the dump's last row)"
    fi
  else
    # No key known. raw is append-only, so a restore holding MORE than live
    # still means rows left the system of record; fewer is only drift.
    loose="$loose $tbl"
    if (( n > live_n )); then
      mismatches="$mismatches $tbl(restored=$n live=$live_n)"
    fi
  fi
done <<< "$restored"

if [[ -n "$mismatches" ]]; then
  finish FAILED "restore of $(basename "$dump")$dump_note does not match live:$mismatches"
fi
if (( rows == 0 )); then
  finish FAILED "restore produced $checked table(s) but zero rows in total"
fi
# The other half of the vacuous-loop guard above. The set check proves nothing
# is missing; this proves the corpus itself was compared — a restore whose
# raw.stream_message is empty, or had no key to compare by, would otherwise
# satisfy every assertion in this script.
if (( messages == 0 )); then
  finish FAILED "restore produced $checked table(s) but compared no rows of raw.stream_message, so nothing was actually verified"
fi

finish OK "restored $(basename "$dump")$dump_note into a throwaway server: $checked raw table(s), $rows rows; raw.stream_message's $messages row(s) and $ordinary other table(s) match live up to the dump's last row${loose:+; no key known, so only restored <= live:$loose}"
