#!/usr/bin/env bash
#
# The second copy. Slice 1 of #181.
#
# WHAT THIS EXISTS FOR. `raw` is the system of record, it is append-only, and it
# is UNREPLAYABLE: Betfair's `clk` replay covers minutes, so a lost match never
# existed. Before this script every byte of live capture sat in exactly one
# place — the Docker volume holding the database on one internal SSD — with
# no dump, no replica, no WAL archive and (verified 2026-09-06)
# `tmutil destinationinfo` reporting no Time Machine destination either. The
# charter rule "never `docker compose down -v`" was the only thing standing
# between the corpus and an ordinary mistake, and a rule against one command is
# not a copy.
#
# WHY OUTSIDE THE APPLICATION. Same argument as the heartbeat: a backup that
# only runs when raptor is healthy is missing in exactly the case it is for.
# This is a launchd job on the host so that a wedged, stopped or crashlooping
# backend cannot stop it.
#
# WHY pg_dump AND NOT A COPY OF THE VOLUME. A file-level copy of a *running*
# data directory can be torn — it is a snapshot of files that Postgres is
# mutating, not of a transaction. pg_dump takes a consistent snapshot and is the
# only artefact here that is guaranteed restorable. Once this file exists,
# Time Machine (or an external drive, or another host) carries it up the ladder
# in #181 with no extra work; the two compose, they are not alternatives.
#
# WHY `raw` ONLY. `query` is rebuildable by charter and is not worth the bytes:
# all 1,290 `projectLiveMarketJob` runs ever executed took 378 seconds in total,
# so the whole live projection regenerates in about six minutes from raw.
# Backing it up would roughly double the dump for data that is a cache.
#
# GROWTH, AND THE UPGRADE PATH. Measured 2026-09-06: 12 GB of `raw` dumps to
# 596 MB in 53 s (jsonb text compresses ~20:1). At a full season of raw (~95 GB)
# expect ~5 GB and ~7 minutes, and RETAIN_DAYS copies of it. When that stops
# being comfortable, the upgrade is per-partition dumps — `raw.stream_message`
# is monthly RANGE(pt) partitioned and a sealed month never changes, so old
# partitions need dumping exactly once. Deliberately NOT done here: it needs
# state tracking, and the failure mode of this script is "silently stops
# working", which argues for the version with least logic to get wrong.
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
#                  RETAIN_DAYS, MIN_FREE_GB, NTFY_ENABLED, NTFY_TOPIC,
#                  NTFY_BASE_URL, BACKUP_PATH_PREFIX.

# Configuration first, before any default below reads a setting: ops.env
# can only supply a value the script has not already defaulted.
# shellcheck source=raptor-ops-env.sh
source "$(dirname "$0")/raptor-ops-env.sh"

set -uo pipefail
# launchd hands the job a near-empty environment, so PATH is set explicitly.
# BACKUP_PATH_PREFIX prepends to it, for stubbing docker/curl in a test.
export PATH="${BACKUP_PATH_PREFIX:+$BACKUP_PATH_PREFIX:}/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export SOPS_AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-$HOME/Library/Application Support/sops/age/keys.txt}"

SECRETS="$RAPTOR_SECRETS"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-$RAPTOR_POSTGRES_CONTAINER}"
# Default is outside the Docker volume, which is the whole point. Repoint this
# at an external drive to climb to rung 2 of #181 — nothing else has to change.
BACKUP_DIR="$RAPTOR_BACKUP_DIR"
# The dump file stem. paddock wrote `paddock-raw-<stamp>.dump`; a deployment
# taking over its backups directory sets RAPTOR_BACKUP_PREFIX=paddock-raw so the
# drill and the retention sweep still see them.
BACKUP_PREFIX="${RAPTOR_BACKUP_PREFIX:-raptor-raw}"
STATE_DIR="$RAPTOR_STATE_DIR"
STATE_FILE="$STATE_DIR/backup.state"
RETAIN_DAYS="${RETAIN_DAYS:-7}"
# A backup that fills the disk takes capture down, which would make this script
# a cause of data loss rather than a defence against it. It refuses rather than
# risk that, and says so loudly — a refusal that nobody hears is the same
# failure as no backup at all.
MIN_FREE_GB="${MIN_FREE_GB:-20}"
mkdir -p "$BACKUP_DIR" "$STATE_DIR"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# --- ntfy config: decrypt straight into vars, no echo, no temp file ---
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
  curl -fsS --max-time 10 -H "Title: raptor backup" -H "Priority: high" \
      -d "$1" "${NTFY_BASE_URL}/${NTFY_TOPIC}" -o /dev/null \
    && echo "$(ts) [alerted] $1" \
    || echo "$(ts) [ntfy-send-FAILED] $1"
}

# De-duped like the heartbeat: alert on a CHANGE of state, and announce
# recovery. A backup that has been failing for a week should not send seven
# identical notifications, and one that starts working again is worth hearing.
prev=$(cat "$STATE_FILE" 2>/dev/null || echo "OK")
finish() { # $1 = OK|FAILED, $2 = message
  if [[ "$1" != "OK" && "$1" != "$prev" ]]; then
    send "⚠️ raptor backup: $2 ($(ts))"
  elif [[ "$1" == "OK" && "$prev" != "OK" ]]; then
    send "✅ raptor backup: recovered ($prev → OK) ($(ts))"
  fi
  printf '%s' "$1" > "$STATE_FILE"
  echo "$(ts) status=$1 prev=$prev — $2"
  [[ "$1" == "OK" ]] && exit 0 || exit 1
}

# --- preflight -------------------------------------------------------------
free_gb=$(df -g "$BACKUP_DIR" | awk 'NR==2 {print $4}')
if [[ -z "$free_gb" ]] || (( free_gb < MIN_FREE_GB )); then
  finish FAILED "only ${free_gb:-unknown} GB free at $BACKUP_DIR (need ${MIN_FREE_GB}); refusing to dump rather than risk filling the disk capture writes to"
fi
if ! docker exec "$POSTGRES_CONTAINER" pg_isready -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" >/dev/null 2>&1; then
  finish FAILED "$POSTGRES_CONTAINER is not accepting connections — nothing was dumped"
fi

# --- dump ------------------------------------------------------------------
# Written to .part and renamed only after it verifies, so a half-written file
# can never be mistaken for a backup. That mistake is worse than no file: it
# converts a known gap into an assumed-covered one.
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$BACKUP_DIR/$BACKUP_PREFIX-$stamp.dump"
partial="$target.part"

# No -i: this feeds nothing on stdin, it only captures stdout. -Z6 because the
# corpus is jsonb text and compresses about 20:1; the CPU is free at 4% for a
# minute and the bytes are not.
if ! docker exec "$POSTGRES_CONTAINER" \
      pg_dump -U "$RAPTOR_DB_USER" -d "$RAPTOR_DB_NAME" -n raw -Fc -Z6 > "$partial" 2>"$partial.err"; then
  err=$(head -c 300 "$partial.err" 2>/dev/null)
  rm -f "$partial" "$partial.err"
  finish FAILED "pg_dump failed: ${err:-no stderr}"
fi

# --- verify ----------------------------------------------------------------
# An unreadable dump is not a backup, and the only moment anyone would otherwise
# discover that is the moment they need it.
#
# NOT `pg_restore --list`, which is the obvious choice and is WRONG here.
# Custom-format archives carry the TOC at the front, so --list reads a header
# and stops: measured 2026-09-06, it exits 0 on a dump truncated to 90% of its
# length. It would have rubber-stamped a broken backup, which is worse than no
# check, because it converts a real gap into an assumed-covered one.
#
# `-f /dev/null` decompresses every data block and writes the SQL nowhere. It
# exits 1 on that same 90% file, and costs 9 seconds against the 53 the dump
# itself takes — there is no argument for the weaker check.
#
# Run in the running container rather than a pulled image: same server version
# as wrote the dump, and no network dependency in the one job that has to work
# when things are broken. -i is required — this feeds the archive on stdin, and
# a `docker exec` without it reads EOF immediately and passes trivially.
if ! docker exec -i "$POSTGRES_CONTAINER" pg_restore -f /dev/null \
      < "$partial" > /dev/null 2>"$partial.err"; then
  err=$(head -c 300 "$partial.err" 2>/dev/null)
  rm -f "$partial" "$partial.err"
  finish FAILED "dump did not verify (pg_restore full decompress): ${err:-no stderr}"
fi
rm -f "$partial.err"
mv "$partial" "$target"
size=$(du -h "$target" | cut -f1)

# --- prune -----------------------------------------------------------------
# Only whole, verified dumps are counted and pruned; a .part left by a killed
# run is removed here rather than aging into the retention window.
find "$BACKUP_DIR" -name "$BACKUP_PREFIX-*.dump.part*" -mtime +1 -delete 2>/dev/null
kept=$(find "$BACKUP_DIR" -name "$BACKUP_PREFIX-*.dump" -mtime "+$RETAIN_DAYS" -print -delete 2>/dev/null | wc -l | tr -d ' ')
total=$(find "$BACKUP_DIR" -name "$BACKUP_PREFIX-*.dump" | wc -l | tr -d ' ')

# One healthy copy is not a backup either: if pruning ever leaves nothing, that
# is the same "assumed covered" failure the .part rename guards against.
if (( total < 1 )); then
  finish FAILED "dump succeeded but no retained copies remain in $BACKUP_DIR"
fi

finish OK "wrote $target ($size), verified, pruned $kept expired, $total retained"
