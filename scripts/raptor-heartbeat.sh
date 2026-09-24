#!/bin/bash
# raptor capture heartbeat — external liveness watchdog.
#
# Runs from launchd (<prefix>.heartbeat) every 5 min, OUTSIDE the stack,
# because that is the only place a watcher can see the failure that matters.
#
# WHAT THIS EXISTS FOR. Everything raptor monitors, it monitors from inside
# itself: StreamWatchdog, the scope poll, the keep-alive, the daily sweep, and
# the four health contributors are all `@Scheduled` beans behind one
# `@EnableScheduling`. None of them can report that the process is not running,
# because a stopped process reports nothing at all — and "nothing at all" looks
# exactly like a closed laptop. That absence is the shape of every capture bug
# this project has had: #122 (health UP for a switched-off recorder), #141 (a
# daily sweep failing at INFO beside the successful ones), #144 (RECONNECTING
# that means idle). Compose now restarts the containers, which covers a crash
# and a reboot; it does not cover a stopped Docker daemon, an image that will
# not boot, or a recorder that is up and quietly capturing nothing.
#
# THREE PROBES, RUN INDEPENDENTLY AND THEN COMPOSED. Deliberately not an
# if/else chain: "is it there", "is it recording", and "is anything arriving"
# are separate questions, and the later ones are the ones a green first answer
# would otherwise suppress. Composed status joins with "+" (e.g.
# DEGRADED+NOT-CAPTURING) so the state name carries the diagnosis. Modelled on
# overround's scalp-heartbeat, whose #1083 fix was exactly this.
#
#   liveness      — unreachable → DOWN (the case nothing else covers), with the
#                   detail saying whether the container or the daemon is the one
#                   missing; reachable but not UP → DEGRADED, naming the status.
#   capturing     — `captureCoverage` is OUT_OF_SERVICE → NOT-CAPTURING, with the
#                   app's OWN sentence as the detail. This probe used to re-derive
#                   its own verdict from `marketsInScope` and the recorder state,
#                   which was a cruder copy of a judgement the app already makes
#                   better (#179): the in-app one also knows about consecutive
#                   failed attempts and consecutive failed catalogue polls, which
#                   nothing out here can see. Two copies of one rule drift, and
#                   only one of them had tests.
#
#                   The scope gate has NOT been lost — it moved inside, where the
#                   `captureCoverage` indicator applies it, so an idle Tuesday is
#                   still silent with no time window to maintain out here. The one
#                   verdict deliberately NOT gated on scope is a catalogue poll
#                   that keeps failing (#180), because the empty scope it would be
#                   gated on is the one that poll could not refresh.
#   frames        — RECORDING with markets subscribed and nothing framed for
#                   FRAME_STALE_S → NO-FRAMES. StreamWatchdog sees this at 10s
#                   from inside; this is the copy that survives the process.
#   runway        — the app reports `raw.stream_message` running out of monthly
#                   partitions → LOW-PARTITION-RUNWAY (#267). Not urgent and
#                   never at night: it asks for a schema change, and when the
#                   partitions do run out the writes fail AND the spill drain
#                   wedges. Ungated by scope on purpose — the deadline does not
#                   care whether there is a card on.
#   close-out    — no close-out FINISHED in STALE_CLOSE_OUT_S → STALE-CLOSE-OUT
#                   (#201, narrowed by #316). The nightly run only fetches the
#                   football archive into raw since #316 — projecting query is
#                   overround-analysis's, reported on its own health and never
#                   here — but it is still a `@Scheduled` timer, and a timer that
#                   stops says nothing. Keyed on the run finishing, whatever its
#                   verdict: a football-data outage is a night it fired.
#   wake report  — a gap that overlapped a market IN PLAY, reported once on the
#                   first pass after it ended (#291). Not a probe of anything
#                   current: see the section on it below.
#   staying awake — markets in scope and the machine is on battery → ON-BATTERY;
#                   markets in scope and NOTHING holds a sleep-preventing
#                   assertion → MAY-SLEEP. Host-only, and it has to be: raptor
#                   runs in a Linux container and cannot see host power, which is
#                   the whole reason `keep-awake` is a launchd agent (#107).
#
# AND ONE ACTION, NOT JUST A NOTIFICATION (#184). Capture normally runs
# unattended, so "what does the system do by itself?" is the primary question
# and "does he find out?" only the second. Everything that STOPS is already
# covered — containers are `restart: unless-stopped`, the recorder reconnects
# with backoff, the spill absorbs a database outage, scope self-clears. Nothing
# covered a backend that is UP and not capturing: `restart: unless-stopped`
# fires on process EXIT, and a wedged JVM, an exhausted pool or a supervisor
# thread that died without taking the process with it all leave a container
# Docker considers perfectly healthy. With the operator present that is a
# five-minute outage; unattended it is the whole card.
#
# So after RESTART_AFTER consecutive checks that find capture OUT_OF_SERVICE
# WITH MARKETS IN SCOPE, this restarts the backend container, says that it did,
# and refuses to do it again for RESTART_COOLDOWN_S. The gates are the whole
# design:
#
#   - OUT_OF_SERVICE *with markets in scope* already means it is not capturing,
#     so a restart cannot lose anything still being recorded — the cost of
#     acting is bounded by what is already being lost.
#   - MARKETS IN SCOPE is required, and OUT_OF_SERVICE alone is NOT enough.
#     Since #180 the verdict also turns over on consecutive catalogue-poll
#     failures, which fire with an EMPTY scope and mean Betfair is unreachable
#     or the app key is revoked — neither of which a restart can fix. Acting on
#     that would churn the recorder through an upstream outage.
#   - THREE CHECKS, NOT ONE: ~15 minutes of confirmed non-capture, by which time
#     an ordinary reconnect, a scope poll and the #176 backoff have all had
#     their chance.
#   - ONE ATTEMPT, THEN STAND DOWN. A restart loop would be worse than the
#     wedge — it would turn one lost match into a lost night.
#
# SLEEP IS THE LARGEST SINGLE CAUSE OF HOLES IN THE CORPUS, AND IT IS SILENT
# (#185, root-caused as #107). `keep-awake` holds `caffeinate -i -s`, and the
# man page says of -s: "This assertion is valid only when system is running on AC
# power." On battery macOS takes the assertion and ignores it — `pmset -g
# assertions` showed both PreventSystemSleep and PreventUserIdleSystemSleep held
# throughout three suspends on 2026-09-06. Agent running, assertion held, machine
# asleep: undiagnosable for two days, and a process check would have called it
# healthy.
#
# So this reads the EFFECT rather than any one cause — the same rule as asserting
# behaviour over configuration. Two findings, because they have different fixes:
# on battery (plug it in) and nothing asserting at all (the holder is dead, or
# was never started). The second is not hypothetical: measured 2026-09-07 with
# Amphetamine.app running but holding nothing — a session-based app with no
# session — while the machine suspended eight times in 84 minutes on a full
# battery. Reading the assertion passes for any remedy; naming a tool would not.
#
# AND ONE FINDING THAT CAN ONLY EVER BE RETROSPECTIVE: WHAT A SUSPEND COST
# (#291). While the lid is shut nothing runs — not launchd, not this script, not
# raptor — so there is no warning to be had during a suspend, only a report
# afterwards. On the first pass after the machine wakes, if a gap overlapped a
# market that was IN PLAY, this says so once: "slept 322s while 12 markets were
# in scope, 3 of them live". The overlap is computed in the app, beside the rule
# the capture screen already uses, and the block is simply absent when nothing
# was in play — so a quiet end-of-evening lid close is silent, and the silence
# stays worth something.
#
# It could not be built until #290. Before that a suspend was indistinguishable
# from a Betfair-side close, so this would have fired on every genuine upstream
# drop or on none; the cause is now decided from the wall-vs-monotonic clock
# rather than from whichever detector noticed first. Gap rows written before
# 2026-09-16 pre-date that and can still carry a DISCONNECT that was really a
# lid closure — they cannot be corrected, because the cause is fixed at insert.
#
# AND THE CLOSE-OUT'S OWN FAILURE (#201, #330). A push when the nightly archive
# sweep finishes FAILED, naming what went wrong. It is here rather than in the
# application for the reason everything else here is: a chain that has stopped
# firing sends nothing, and nothing is indistinguishable from success. The
# sender has to be the component that survives the chain not running.
#
# It used to be a morning SUMMARY, sent whatever the verdict, so that its
# absence could never be mistaken for a good night. #330 made it failure-only:
# since #316 the close-out is the archive fetch alone, and a nightly ✅ on a
# channel whose value is that every message is urgent teaches its reader to
# ignore the channel. Absence does not start carrying meaning again, because
# the two ways a night can go wrong without a ⚠️ are both covered elsewhere: a
# run that never finished is STALE-CLOSE-OUT (keyed on finishing, 26h), and a
# heartbeat that is not running is the thing launchd and `bin/deploy-agents
# --check` answer for. No ⚠️ and no STALE-CLOSE-OUT means the sweep succeeded.
#
# Keyed on the close-out's OWN finish timestamp, not on this script's clock, so
# a 5-minute cadence sends one notification per run rather than 288 a day. Its
# de-dupe file is separate from the status one below because they are different
# questions: the status file answers "has the finding changed", this one answers
# "has this run been announced".
#
# THE HALVES USED TO BE READ SEPARATELY (#201): the first live close-out
# (2026-09-07 23:30Z) projected all 44 of the night's markets and rolled up to
# FAILED because football-data.co.uk was 503, and alerting on the roll-up would
# have cried wolf. Since #316 there is one half, the archive, and staleness is
# keyed on the run finishing rather than on its verdict, which keeps the same
# property: an outage upstream never reads as a stopped timer.
#
# Alerts go to ntfy, topic read from sops at runtime, never written to disk —
# from the sops file named by RAPTOR_SECRETS (see ops.env.example); with none
# configured, every alert is logged instead of sent.
# De-duped through a state file: fires on a state CHANGE, plus a recovery notice.
#
# DEPLOY: `bin/deploy-agents`. The launchd job runs a copy OUTSIDE the repo, in
# the state directory (RAPTOR_STATE_DIR), so a branch checkout cannot rewrite or delete it mid-run — that
# part was always right; doing the copy by hand was not (#187). The deploy is
# idempotent, and `bin/deploy-agents --check` (which `bin/up` runs) reports a
# deployed copy that has drifted from this one. A stale copy does not fail: it
# reports OK from old code, which is indistinguishable from working.
#
# Configuration: ops.env (raptor-ops-env.sh) for paths, containers and secrets.
# Overridable env: HEALTH_URL, CAPTURE_URL, RAPTOR_SECRETS, NTFY_BASE_URL,
#                  NTFY_ENABLED, NTFY_TOPIC, FRAME_STALE_S, BACKEND_CONTAINER,
#                  RESTART_ENABLED, RESTART_AFTER, RESTART_COOLDOWN_S,
#                  STALE_CLOSE_OUT_S.
#
# Tunables:
# Configuration first, before any default below reads a setting: ops.env
# can only supply a value the script has not already defaulted.
# shellcheck source=raptor-ops-env.sh
source "$(dirname "$0")/raptor-ops-env.sh"

FRAME_STALE_S="${FRAME_STALE_S:-600}"   # silence with markets subscribed that counts
                                        # as a finding. Generous on purpose: observed
                                        # gaps with 60 markets subscribed pre-kickoff
                                        # are 0.2-5s, so 10 minutes is ~100x the
                                        # normal worst case and cannot false-alarm on
                                        # an ordinary lull.

RESTART_ENABLED="${RESTART_ENABLED:-true}"      # the kill switch. Off makes this
                                                # script observe-only again.
RESTART_AFTER="${RESTART_AFTER:-3}"             # consecutive qualifying checks, so
                                                # ~15 minutes at the 5-minute cadence.
RESTART_COOLDOWN_S="${RESTART_COOLDOWN_S:-21600}"  # 6h: longer than a card. One
                                                # attempt, then it is a human's.

STALE_CLOSE_OUT_S="${STALE_CLOSE_OUT_S:-93600}" # 26h: a day plus slack, so one late
                                                # or slow run does not fire it, and a
                                                # missed night does. The close-out is
                                                # daily, so anything under 24h would
                                                # alert every day just before it ran.

set -uo pipefail
# launchd hands the job a near-empty environment, so PATH is set explicitly.
# HEARTBEAT_PATH_PREFIX prepends to it, for stubbing curl/docker in a test.
export PATH="${HEARTBEAT_PATH_PREFIX:+$HEARTBEAT_PATH_PREFIX:}/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export SOPS_AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-$HOME/Library/Application Support/sops/age/keys.txt}"

SECRETS="$RAPTOR_SECRETS"
HEALTH_URL="${HEALTH_URL:-$RAPTOR_BACKEND_URL/actuator/health}"
CAPTURE_URL="${CAPTURE_URL:-$RAPTOR_BACKEND_URL/actuator/health/capture}"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-$RAPTOR_BACKEND_CONTAINER}"
STATE_DIR="$RAPTOR_STATE_DIR"
STATE_FILE="$STATE_DIR/heartbeat.state"
RESTART_FILE="$STATE_DIR/heartbeat.restart"     # "<consecutive> <last-restart-epoch>"
CLOSE_OUT_FILE="$STATE_DIR/heartbeat.closeout"  # finishedAt of the last run announced
GAP_FILE="$STATE_DIR/heartbeat.gap"             # endedAt of the last gap reported
mkdir -p "$STATE_DIR"

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

# Returns 0 only when the notification is known to have been delivered (or when
# alerting is deliberately off — nothing to deliver is not a failure). The
# caller relies on this to decide whether to commit state.
send() { # $1 = message
  if [[ "$NTFY_ENABLED" != "true" || -z "$NTFY_TOPIC" ]]; then
    echo "$(ts) [skip-alert: ntfy not enabled / no topic in sops] $1"
    return 0
  fi
  if curl -fsS --max-time 10 \
        -H "Title: raptor capture" \
        -H "Priority: high" \
        -d "$1" "${NTFY_BASE_URL}/${NTFY_TOPIC}" -o /dev/null; then
    echo "$(ts) [alerted] $1"
    return 0
  fi
  echo "$(ts) [ntfy-send-FAILED] $1"
  return 1
}

status_parts=""; detail_parts=""; wedged=""
add_status() { status_parts="${status_parts:+$status_parts+}$1"; }
add_detail() { detail_parts="${detail_parts:+$detail_parts; }$1"; }

# --- probe 1: liveness ------------------------------------------------------
# Deliberately NO `curl -f`. A non-UP root health answers 503 and -f discards the
# body, which would collapse "reachable but degraded" into "unreachable" — two
# incidents that want different reactions. captureCoverage answers 503 when a
# DISABLED or NO_SOURCE recorder has markets in scope (#131), which is precisely
# a case where the process is fine and the capture is not.
live=""
health_raw=$(curl -sS --max-time 5 -w '\n%{http_code}' "$HEALTH_URL" 2>/dev/null)
curl_rc=$?
http_code=$(printf '%s' "$health_raw" | tail -1)
health_body=$(printf '%s' "$health_raw" | sed '$d')

if (( curl_rc != 0 )) || [[ -z "$http_code" || "$http_code" == "000" ]]; then
  live="DOWN"
  # WHICH ABSENCE IT IS decides what to do about it, and at 3am that is the
  # whole value of the alert: a stopped container is `bin/up`, a dead daemon is
  # Docker Desktop, and a running container that will not answer is a log dive.
  if ! docker info >/dev/null 2>&1; then
    add_detail "docker daemon unreachable — the whole stack is absent, not just raptor"
  elif [[ -z "$(docker ps -q -f "name=^${BACKEND_CONTAINER}$" 2>/dev/null)" ]]; then
    add_detail "container $BACKEND_CONTAINER is not running — nothing is capturing"
  else
    add_detail "container $BACKEND_CONTAINER is running but $HEALTH_URL does not answer"
  fi
  add_status "DOWN"
elif ! printf '%s' "$health_body" | grep -q '"status":"UP"'; then
  health_word=$(printf '%s' "$health_body" | sed -n 's/.*"status":"\([A-Z_]*\)".*/\1/p')
  live="DEGRADED"
  add_status "DEGRADED"
  add_detail "reachable but health ${health_word:-HTTP$http_code}"
else
  live="UP"
fi

# --- probes 2 and 3: capturing, and frames ------------------------------------
# Run whatever probe 1 found, as long as the endpoint answers. A DEGRADED
# backend is exactly when "and is it still capturing?" needs asking, and an
# if/else here is the bug the overround heartbeat had to fix.
capture_body=$(curl -sS --max-time 5 "$CAPTURE_URL" 2>/dev/null)
if [[ -n "$capture_body" ]] && printf '%s' "$capture_body" | jq -e . >/dev/null 2>&1; then
  in_scope=$(printf '%s' "$capture_body" | jq -r '.components.scope.details.marketsInScope // empty')
  rec_state=$(printf '%s' "$capture_body" | jq -r '.components.recorder.details.state // empty')
  since_frame=$(printf '%s' "$capture_body" | jq -r '.components.recorder.details.secondsSinceLastFrame // empty')
  cover_status=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.status // empty')
  cover_reason=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.reason // empty')

  # The last finished close-out, for the failure push below. Absent until one
  # has finished, which is why every use is guarded rather than defaulted: a push
  # composed from empty fields would announce a night that did not happen.
  close_out_at=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.closeOut.finishedAt // empty')
  close_out_archive=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.closeOut.archive // empty')
  close_out_files=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.closeOut.archiveFiles // empty')
  close_out_detail=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.closeOut.detail // empty')

  # The last gap that overlapped play, for the wake report below (#291). Absent
  # on every ordinary pass, which is the point: the app publishes this block only
  # when a gap actually cost something, so an end-of-evening lid close with
  # nothing in scope leaves nothing here to announce.
  gap_at=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.gapInPlay.endedAt // empty')
  gap_cause=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.gapInPlay.cause // empty')
  gap_seconds=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.gapInPlay.seconds // empty')
  gap_markets=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.gapInPlay.markets // empty')
  gap_live=$(printf '%s' "$capture_body" | jq -r '.components.captureCoverage.details.gapInPlay.live // empty')

  # READ the verdict, do not re-derive it. `.details.reason` is a sentence the
  # app writes for exactly this reader, and it is the half that was missing: a
  # #171-shape crashloop cycles through RECORDING every few seconds, so a probe
  # sampling `recorder.state` once every 300s degraded to a bare
  # "reachable but health HTTP503" about half the time it fired.
  if [[ "$cover_status" == "OUT_OF_SERVICE" ]]; then
    add_status "NOT-CAPTURING"
    add_detail "${cover_reason:-captureCoverage is OUT_OF_SERVICE and gave no reason}"
  fi

  # The condition the restart acts on, and it is NARROWER than the alert above.
  # `marketsInScope > 0` is what makes acting safe and useful at once: it means
  # something is being lost right now (so a restart costs nothing that is not
  # already gone) and it excludes the #180 poll-failure red, which fires on an
  # EMPTY scope and means the fault is upstream, where a restart is useless.
  if [[ "$cover_status" == "OUT_OF_SERVICE" && -n "$in_scope" && "$in_scope" -gt 0 ]]; then
    wedged=1
  fi

  # Only meaningful while it claims to be recording something.
  if [[ "$rec_state" == "RECORDING" && -n "$in_scope" && "$in_scope" -gt 0 && -n "$since_frame" ]] \
     && awk -v s="$since_frame" -v t="$FRAME_STALE_S" 'BEGIN { exit !(s > t) }'; then
    add_status "NO-FRAMES"
    add_detail "RECORDING with $in_scope market(s) subscribed but nothing framed for ${since_frame}s"
  fi

  # --- probe 5: is the nightly close-out still firing? (#201) -----------------
  # NOT gated on scope, unlike the capture probes, and the difference is the
  # point: an idle week is exactly when a stopped close-out is least visible and
  # most costly, because nothing else about the system looks wrong. What the
  # gate would buy elsewhere — silence on a quiet Tuesday — is here the failure.
  #
  # READ, do not re-derive: `lastCloseOutSecondsAgo` is the app's own answer,
  # keyed on the last run FINISHING (#316). Absent means the field said "never".
  since_close_out=$(printf '%s' "$capture_body" \
      | jq -r '.components.captureCoverage.details.lastCloseOutSecondsAgo // empty')
  if [[ -z "$since_close_out" ]]; then
    # "never" is a finding, not a fresh start to be forgiven: the only states
    # that produce it are a chain that has never worked and a database that has
    # lost the ledger, and both want a human.
    add_status "STALE-CLOSE-OUT"
    add_detail "no nightly close-out has ever finished — the archive fetch is not running"
  elif (( since_close_out > STALE_CLOSE_OUT_S )); then
    add_status "STALE-CLOSE-OUT"
    add_detail "last close-out finished $(( since_close_out / 3600 ))h ago (threshold $(( STALE_CLOSE_OUT_S / 3600 ))h) — the nightly timer has stopped firing"
  fi

  # NO ANALYSIS PROBE, since slice 10 (#255), and its absence is the point.
  # There used to be one here (#212): it read `.components.analysis.details.stale`
  # out of this same payload and raised STALE-ANALYSIS. The derivation moved to
  # overround-analysis, which reports its own staleness on its own
  # `/actuator/health/derivation`, and paddock dropped `analysis` from the capture
  # group in the same change — so the field this parsed is no longer on the wire
  # and the probe would have been silent whatever happened.
  #
  # It is not being re-pointed at the other application, and that is a decision
  # rather than an omission. This heartbeat exists to say "you are losing data
  # you can never get back"; a derivation that can simply be run again does not
  # belong on a channel whose whole value is that every message on it is urgent
  # (PRD #245's second complaint about the conflation). overround-analysis gets
  # no ntfy at all, by the same PRD. A missed night is visible on its own health
  # endpoint and costs a re-run.
  #
  # `CaptureHealthGroupTest` asserts `analysis` is absent from the group, which
  # is what stops the field this used to read from quietly reappearing.

  # THE PARTITION RUNWAY (#267). `raw.stream_message` is range-partitioned by
  # month and its partitions were created once, by a migration that computed
  # twelve months from the date it ran. When they end, every write to the system
  # of record fails — and the failure wedges the spill drain, because a rejected
  # row spills and then fails identically on every replay, forever.
  #
  # Read as a FLAG the app already computed, never re-derived here: the threshold
  # lives in `raptor.raw-store.partition-runway-warning` and a second copy of
  # the rule out here is the #179 drift. Absent means silence, deliberately: a
  # backend older than this publishes no such contributor, and alerting through a
  # deploy skew is how a channel stops being read.
  #
  # Its own word, because the response is unlike every other status here. Nothing
  # about it is urgent tonight and nothing can be done at 22:00 on a Saturday; it
  # asks for a schema change on a weekday. A word that read like a capture failure
  # would teach the operator to read a capture failure as this.
  runway_low=$(printf '%s' "$capture_body" \
      | jq -r '.components.partitionRunway.details.low // empty')
  # NOT `// empty`, which collapses a legitimate 0 the way it collapses absent —
  # jq's alternative operator treats `false` and `0`-as-null alike. Zero days is
  # exactly when this message must not read "? day(s) left".
  runway_days=$(printf '%s' "$capture_body" \
      | jq -r '.components.partitionRunway.details.daysRemaining
               | if . == null then empty else tostring end')
  if [[ "$runway_low" == "true" ]]; then
    add_status "LOW-PARTITION-RUNWAY"
    add_detail "raw.stream_message has ${runway_days:-?} day(s) of partitions left; \
writes fail when they run out (#267) - this needs a schema change, not a restart"
  fi
elif [[ "$live" == "UP" ]]; then
  # Root health UP while the capture group is unreadable is its own finding: the
  # group is what the recorder reports through, so losing it loses the signal
  # without losing the app. Not folded into DEGRADED, which means the opposite.
  add_status "NO-CAPTURE-HEALTH"
  add_detail "$CAPTURE_URL returned nothing parseable while root health is UP"
fi

# --- probe 4: will this machine still be awake in an hour? (#185) ------------
# Gated on scope like everything else: on battery at 04:00 on a Tuesday is
# correct and boring, and an alert that fires when nothing is at risk is how a
# real one stops being read.
#
# NOT wired to the restart below, and deliberately: restarting a container
# cannot plug in a charger. Power is not a raptor health contributor for the
# same reason it needs checking out here — the app is in a Linux container and
# cannot see host power — so this can only ever alert. If power is ever made a
# contributor, re-read the restart gate before doing it.
if [[ -n "$in_scope" && "$in_scope" -gt 0 ]] && command -v pmset >/dev/null; then
  # Absence of pmset is not a finding — it means this is not the deployed shape.
  if pmset -g batt 2>/dev/null | grep -q "Battery Power"; then
    add_status "ON-BATTERY"
    add_detail "on battery with $in_scope market(s) in scope — caffeinate -s is void off AC, so keep-awake cannot hold this machine; plug it in"
  fi

  # The system-wide counters, not "is keep-awake running". #107 was
  # undiagnosable for two days precisely because the agent WAS running and
  # correct; a process check would have agreed with it. Only the first match of
  # each is read — the same words reappear in the per-process listing below,
  # where they are prefixed by `pid`, so `$1 ==` cannot pick them up.
  awake=$(pmset -g assertions 2>/dev/null | awk '
    $1 == "PreventUserIdleSystemSleep" && idle == "" { idle = $2 }
    $1 == "PreventSystemSleep"         && sys  == "" { sys  = $2 }
    END { printf "%d", idle + sys }')
  if [[ "${awake:-0}" -eq 0 ]]; then
    add_status "MAY-SLEEP"
    add_detail "nothing holds a sleep-preventing assertion with $in_scope market(s) in scope — keep-awake is not holding, and any menu-bar app is not asserting either"
  fi
fi

# --- the action: restart a backend that is up and not capturing (#184) -------
# Deliberately BEFORE the de-dupe below, so the restart notice rides on the same
# pass that decided to act rather than waiting five minutes for a state change
# that may never come — a wedge holds one status indefinitely, which is exactly
# what the dedupe is built to suppress.
consecutive=0; last_restart=0
if [[ -r "$RESTART_FILE" ]]; then
  read -r consecutive last_restart < "$RESTART_FILE" || true
  consecutive="${consecutive:-0}"; last_restart="${last_restart:-0}"
fi

if [[ -n "$wedged" ]]; then
  consecutive=$(( consecutive + 1 ))
else
  consecutive=0
fi

now_epoch=$(date -u +%s)
if [[ -n "$wedged" && "$RESTART_ENABLED" == "true" ]] \
   && (( consecutive >= RESTART_AFTER )); then
  since_last=$(( now_epoch - last_restart ))
  if (( since_last < RESTART_COOLDOWN_S )); then
    # Said every pass while it holds, because the state word is unchanged and
    # the dedupe would otherwise make the ONE case a human must act on the
    # quietest thing this script does.
    add_detail "still wedged $consecutive check(s) after a restart $((since_last / 60))m ago — standing down, this needs a human"
  elif docker restart "$BACKEND_CONTAINER" >/dev/null 2>&1; then
    last_restart="$now_epoch"
    consecutive=0
    add_status "RESTARTED"
    add_detail "restarted $BACKEND_CONTAINER after $RESTART_AFTER checks not capturing with $in_scope market(s) in scope"
    send "🔁 raptor capture: restarted $BACKEND_CONTAINER — not capturing for $RESTART_AFTER consecutive checks with $in_scope market(s) in scope ($(ts))" || true
  else
    add_status "RESTART-FAILED"
    add_detail "docker restart $BACKEND_CONTAINER failed; nothing automatic is left to try"
  fi
fi
printf '%s %s' "$consecutive" "$last_restart" > "$RESTART_FILE"

# --- the close-out failure: one push per FAILED close-out (#201, #330) -------
# Deliberately NOT part of the status de-dupe below. That one suppresses a
# repeated finding, which is right for a fault that persists; a failed sweep is
# an event, and its identity is the RUN rather than the state — two consecutive
# nights that both failed are two pushes, where they would be one status.
#
# A COMPLETED run sends nothing and writes nothing (#330, see the header). The
# de-dupe key therefore only ever holds a failed run's `finishedAt`, which is
# all it needs to: a newer finish is always a different run.
if [[ -n "${close_out_at:-}" && "$close_out_archive" != "COMPLETED" ]]; then
  announced=$(cat "$CLOSE_OUT_FILE" 2>/dev/null || echo "")
  if [[ "$close_out_at" != "$announced" ]]; then
    failure="⚠️ raptor close-out: archive ${close_out_archive:-UNKNOWN}, ${close_out_files:-0} file(s)"
    [[ -n "${close_out_detail:-}" ]] && failure="$failure — $close_out_detail"
    # Same commit-on-delivery rule as the status file: writing the key after a
    # failed POST would announce the run to nobody and then believe it had.
    if send "$failure ($close_out_at)"; then
      printf '%s' "$close_out_at" > "$CLOSE_OUT_FILE"
    else
      echo "$(ts) [close-out-not-committed] keeping $announced so the next pass retries the push for $close_out_at"
    fi
  fi
fi

# --- the wake report: one push per gap that overlapped play (#291) -----------
# THE FINDING NOTHING CAN DELIVER WHILE IT IS HAPPENING. A closed lid means the
# host is asleep, launchd does not run this job, and raptor is not running
# either — so no check anywhere can warn DURING a suspend. That is the #122 /
# #141 / #144 shape, and the CLAUDE.md line that goes with it: nothing inside
# raptor can report that raptor is absent. The pre-emptive half is covered
# from the other side by `lid` and by ON-BATTERY / MAY-SLEEP above.
# This is the half that is left: on the first pass after the machine comes back,
# say what the suspend cost.
#
# IT IS AN EVENT, NOT A STATUS, and that is a deliberate departure from
# everything above it. A gap is over by the time it can be read: the recorder
# reconnected and there is nothing to recover from. Carried as a status word it
# would sit in the de-dupe file for as long as it stayed the most recent gap and
# then produce a "recovered" notice for a fault that was never ongoing. So it is
# keyed like the close-out failure instead — on the gap's OWN `endedAt`, which
# the app publishes for exactly this purpose — and announced once.
#
# SILENT WHEN THE OVERLAP IS ZERO, and the silence is enforced in the app: the
# block is published only when a gap overlapped a market IN PLAY. A 322-second
# suspend with scope empty is a non-event — the 2026-09-11 case — and an alert
# that fires on every quiet lid close is one that gets muted before the night it
# matters.
if [[ -n "${gap_at:-}" ]]; then
  reported=$(cat "$GAP_FILE" 2>/dev/null || echo "")
  if [[ "$gap_at" != "$reported" ]]; then
    if [[ "SLEEP" == "$gap_cause" ]]; then
      gap_msg="🛌 raptor capture: slept ${gap_seconds}s while ${gap_markets} market(s) were in scope, ${gap_live} in play"
    else
      # SILENCE and DISCONNECT are named rather than folded into "slept": since
      # #290 the cause is decided from the wall-vs-monotonic clock rather than
      # from whichever detector noticed first, so the word means something and
      # the fix it points at differs.
      gap_msg="🕳️ raptor capture: lost ${gap_seconds}s to ${gap_cause} while ${gap_markets} market(s) were in scope, ${gap_live} in play"
    fi
    # Same commit-on-delivery rule as the status file: writing the key after a
    # failed POST would report the gap to nobody and then believe it had.
    if send "$gap_msg ($gap_at)"; then
      printf '%s' "$gap_at" > "$GAP_FILE"
    else
      echo "$(ts) [gap-not-committed] keeping $reported so the next pass retries the report for $gap_at"
    fi
  fi
fi

status="${status_parts:-OK}"
detail="$detail_parts"

# --- de-dupe: alert only on state change; notify on recovery ---
# The state file is the dedupe key, so committing it is what says "this
# transition has been announced" — and it must therefore be written only after
# the notification actually lands. Writing it unconditionally means a failed
# POST still advances prev, the next pass sees no change and stays silent, and
# the outage is announced to nobody. Leaving state untouched on a failed send
# makes the 5-minute cadence the retry, with no queue or backoff.
prev=$(cat "$STATE_FILE" 2>/dev/null || echo "OK")
delivered=1

if [[ "$status" != "OK" && "$status" != "$prev" ]]; then
  send "⚠️ raptor capture: $status — $detail ($(ts))" || delivered=""
elif [[ "$status" == "OK" && "$prev" != "OK" ]]; then
  send "✅ raptor capture: recovered ($prev → OK) ($(ts))" || delivered=""
fi

if [[ -n "$delivered" ]]; then
  printf '%s' "$status" > "$STATE_FILE"
else
  echo "$(ts) [state-not-committed] keeping prev=$prev so the next pass retries the $status alert"
fi

echo "$(ts) status=$status prev=$prev${detail:+ — $detail}"
