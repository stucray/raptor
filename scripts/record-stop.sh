#!/usr/bin/env bash
# Record how the old backend container stopped, around a deploy (#75).
#
#   scripts/record-stop.sh start <dir>    before `docker compose up`
#   scripts/record-stop.sh finish <dir>   after it
#
# WHY. Two deploys left their open capture session ABANDONED (150, 159), and by
# the time anyone looked, the evidence had gone with the container: its logs
# are removed when compose recreates it, and Docker keeps no event history that
# far back. A clean restart in the suspected state (2026-10-11) took 0.48s and
# exited 143, so the cause is still unknown. This makes the next one arrive with
# its evidence: the exit code (143 is a graceful SIGTERM exit, 137 a SIGKILL),
# the time from SIGTERM to exit, and the old container's last log lines.
#
# OUTPUT. One line per deploy appended to $RAPTOR_STATE_DIR/stops.log, and the
# old container's log tail in $RAPTOR_STATE_DIR/stops/<stamp>.log. Never fatal:
# a deploy must not fail because its own instrumentation could not run.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/raptor-ops-env.sh

cmd="${1:-}"
dir="${2:-}"
[[ -n "$cmd" && -n "$dir" ]] || { echo "usage: $0 start|finish <dir>" >&2; exit 2; }
container="$RAPTOR_BACKEND_CONTAINER"

case "$cmd" in
  start)
    mkdir -p "$dir"
    : > "$dir/events"; : > "$dir/logs"; : > "$dir/pids"
    if ! docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null | grep -qx true; then
      echo "no running $container" > "$dir/none"
      exit 0
    fi
    docker events --filter "container=$container" --filter event=kill --filter event=die \
      --format '{{.TimeNano}} {{.Action}} {{index .Actor.Attributes "exitCode"}} {{index .Actor.Attributes "signal"}}' \
      > "$dir/events" 2>/dev/null &
    echo $! >> "$dir/pids"
    docker logs -f --since 1s "$container" > "$dir/logs" 2>&1 &
    echo $! >> "$dir/pids"
    ;;

  finish)
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    # A beat for the event stream to flush the die that compose has just waited on.
    sleep 1
    if [[ -r "$dir/pids" ]]; then
      while read -r pid; do kill "$pid" 2>/dev/null; done < "$dir/pids"
    fi
    mkdir -p "$RAPTOR_STATE_DIR/stops"
    if [[ -e "$dir/none" ]]; then
      line="$stamp no running backend before the deploy; nothing to measure"
    else
      line="$(awk -v stamp="$stamp" '
        # A kill line carries no exit code, so its signal is the last field.
        $2 == "kill" && $NF == "15" && term == "" { term = $1 }
        $2 == "die" { die = $1; code = $3 }
        END {
          if (die == "") { print stamp " backend was not restarted (no die event)"; exit }
          took = (term == "") ? "unknown" : sprintf("%.2fs", (die - term) / 1e9)
          verdict = (code == "143") ? "graceful" : (code == "137") ? "SIGKILLED" : "unexpected"
          print stamp " exit=" code " " verdict " sigterm_to_exit=" took
        }' "$dir/events")"
      if [[ -s "$dir/logs" ]]; then
        tail -n 200 "$dir/logs" > "$RAPTOR_STATE_DIR/stops/$stamp.log"
        line="$line old_logs=$RAPTOR_STATE_DIR/stops/$stamp.log"
      fi
    fi
    echo "$line" >> "$RAPTOR_STATE_DIR/stops.log"
    echo "backend stop: $line"
    [[ "$line" == *SIGKILLED* || "$line" == *unexpected* ]] \
      && echo "WARNING: the old backend did not stop gracefully; see #75" >&2
    exit 0
    ;;

  *)
    echo "usage: $0 start|finish <dir>" >&2; exit 2 ;;
esac
