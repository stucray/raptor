#!/bin/bash
# Tests for raptor-heartbeat.sh — the first shell tests in this repo.
#
# WHY THESE EXIST. The heartbeat is the one component whose failures are
# invisible by construction: it runs from launchd every five minutes against a
# stack that is usually fine, and a broken probe reports OK from a script nobody
# reads. Every bug it is built to catch (#122, #141, #144) has the same shape as
# the bug it could itself have. It had grown to five probes, an automatic
# backend restart and a close-out push on completely untested shell.
#
# HOW. `HEARTBEAT_PATH_PREFIX` has been in the script since it was written and
# was never used by anything; it prepends to PATH, so a directory of stubs takes
# over `curl`, `docker` and `pmset` while the real `jq`, `awk` and `date` keep
# working. HOME is redirected too, so the state files land in the case's own
# temp directory and one case cannot see another's de-dupe.
#
# WHAT IS DELIBERATELY NOT STUBBED: jq. The probes are jq expressions against a
# real health payload, so stubbing it would test the stub. The fixtures here are
# shaped from the actual /actuator/health/capture response.
set -uo pipefail
# No operator config may leak into a test: every setting comes from the case.
# That means the ENVIRONMENT as well as ops.env — the environment beats the file
# by design, so a setting exported by whatever launched this suite (the deploy
# gate, a shell with a shadow's settings) would otherwise steer the cases.
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^(RAPTOR_|RESTART_|NTFY_|HEALTH_URL$|CAPTURE_URL$)')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
HEARTBEAT="$PWD/scripts/raptor-heartbeat.sh"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }

# --- fixtures ---------------------------------------------------------------
# `closeOut` and `lastCloseOutSecondsAgo` mirror what CaptureCoverageHealthIndicator
# publishes since #316: the archive sweep alone, and the age of the last run to
# FINISH whatever its verdict. `secondsAgo` absent is how the app spells "never".
capture_json() { # $1 = seconds since the last close-out finished ("" = never)
                 # $2 = coverage status  $3 = closeOut finishedAt ("" = none)
                 # $4 = unused since #316 (was the capture half)  $5 = archive verdict
  local since="$1" cover="$2" at="$3" arc="${5:-COMPLETED}"
  local details='"state":"IDLE","marketsInScope":0,"live":0'
  [[ -n "$since" ]] && details="$details,\"lastCloseOutSecondsAgo\":$since"
  [[ -z "$since" ]] && details="$details,\"lastCloseOut\":\"never\""
  if [[ -n "$at" ]]; then
    details="$details,\"closeOut\":{\"finishedAt\":\"$at\",\"archive\":\"$arc\",\"archiveFiles\":3,\"detail\":\"\"}"
  fi
  [[ "$cover" == "OUT_OF_SERVICE" ]] && details="$details,\"reason\":\"the recorder is not capturing\""
  # The analysis contributor is OMITTED unless $6 asks for it, which is the
  # pre-#212 payload every case above is written against — and is itself the
  # thing case 14 asserts stays silent.
  # gapInPlay, the wake report's whole input ($7 = endedAt). Absent by default,
  # which is what the app publishes on every pass where no gap overlapped play.
  if [[ -n "${7:-}" ]]; then
    details="$details,\"gapInPlay\":{\"endedAt\":\"$7\",\"cause\":\"${8:-SLEEP}\",\"seconds\":322,\"markets\":12,\"live\":${9:-3}}"
  fi
  local analysis=""
  if [[ -n "${6:-}" ]]; then
    analysis=',"analysis":{"status":"UP","details":{"enabled":true,"stale":'"$6"',"analysisReason":"no analysis close-out has succeeded whole for 30h; live features are behind query. Rebuildable — this is not a capture failure"}}'
  fi
  printf '{"status":"UP","components":{"captureCoverage":{"status":"%s","details":{%s}},"recorder":{"status":"UP","details":{"state":"IDLE","secondsSinceLastFrame":0.1}},"scope":{"status":"UP","details":{"marketsInScope":0,"live":0}},"spill":{"status":"UP","details":{"pendingFiles":0}}%s}}' \
    "$cover" "$details" "$analysis"
}

# --- the harness ------------------------------------------------------------
# Each case gets a fresh HOME (so fresh state files) and a fresh stub directory.
setup() { # $1 = capture payload
  work=$(mktemp -d)
  mkdir -p "$work/bin" "$work/home"
  printf '%s' "$1" > "$work/capture.json"

  # curl: routes on the URL. Health answers the body plus the "\n%{http_code}"
  # the script asks for; ntfy records what would have been pushed.
  cat > "$work/bin/curl" <<'STUB'
#!/bin/bash
url=""; data=""
while (( $# )); do
  case "$1" in
    -d) data="$2"; shift 2 ;;
    http*) url="$1"; shift ;;
    *) shift ;;
  esac
done
case "$url" in
  *ntfy*)                printf '%s\n' "$data" >> "$WORK/sent.log"; exit 0 ;;
  *health/capture)       cat "$WORK/capture.json"; exit 0 ;;
  *actuator/health)      printf '{"status":"UP"}\n200\n'; exit 0 ;;
esac
exit 1
STUB

  # docker: alive, container present, and every restart recorded so a test can
  # assert that a finding did NOT cause one.
  cat > "$work/bin/docker" <<'STUB'
#!/bin/bash
case "${1:-}" in
  info) exit 0 ;;
  ps) echo "abc123"; exit 0 ;;
  restart) printf '%s\n' "${2:-}" >> "$WORK/restarts.log"; exit 0 ;;
esac
exit 0
STUB

  # pmset: on AC with an assertion held, so the power probes stay quiet and a
  # case about the close-out is about the close-out.
  cat > "$work/bin/pmset" <<'STUB'
#!/bin/bash
case "$*" in
  *batt*)       echo "Now drawing from 'AC Power'" ;;
  *assertions*) echo "PreventUserIdleSystemSleep     1"; echo "PreventSystemSleep             1" ;;
esac
exit 0
STUB
  chmod +x "$work/bin/"*
  : > "$work/sent.log"; : > "$work/restarts.log"
}

run() { # runs the heartbeat once against the current stubs
  WORK="$work" HOME="$work/home" HEARTBEAT_PATH_PREFIX="$work/bin" \
    NTFY_ENABLED=true NTFY_TOPIC=test-topic \
    HEALTH_URL="http://stub/actuator/health" \
    CAPTURE_URL="http://stub/actuator/health/capture" \
    "$HEARTBEAT" 2>&1
}

teardown() { rm -rf "$work"; }

expect_status() { # $1 = output  $2 = expected status word
  if ! printf '%s' "$1" | grep -q "status=$2"; then
    fail "expected status=$2, got: $(printf '%s' "$1" | tail -1)"
  else pass; fi
}
expect_sent_count() { # $1 = how many pushes  $2 = label
  local n; n=$(grep -c . "$work/sent.log")
  if [[ "$n" != "$1" ]]; then
    fail "$2: expected $1 push(es), got $n: $(cat "$work/sent.log")"
  else pass; fi
}

echo "heartbeat tests"

# --- 1: a completed close-out sends NOTHING (#330) --------------------------
# The whole change. A nightly ✅ on a channel whose every message is meant to be
# urgent is noise; the absence is covered by STALE-CLOSE-OUT (case 5) instead.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED)"
out=$(run); out=$(run); out=$(run)
expect_sent_count 0 "a completed close-out, three passes"
expect_status "$out" OK
[[ -e "$work/home/.raptor/heartbeat.closeout" ]] \
  && fail "a completed close-out wrote the failure de-dupe key"
pass

# --- 2: a failed sweep is pushed, and is NOT a stale close-out ---------------
# football-data.co.uk answering 503 is an ordinary night (#201). The run
# finished, so the timer is firing; the push says what went wrong. Same HOME as
# case 1, so this is also the transition from a quiet good night to a bad one.
printf '%s' "$(capture_json 60 UP 2026-09-08T23:31:44Z "" FAILED)" > "$work/capture.json"
out=$(run)
expect_sent_count 1 "a failed close-out"
grep -q "⚠️ raptor close-out: archive FAILED, 3 file(s)" "$work/sent.log" \
  || fail "the push should name the archive failure: $(cat "$work/sent.log")"
pass
expect_status "$out" OK

# --- 3: re-running before the next close-out sends nothing more -------------
out=$(run); out=$(run)
expect_sent_count 1 "three passes, one failed close-out"

# --- 4: a NEW failed close-out is a new push; a good night after it is silent --
printf '%s' "$(capture_json 60 UP 2026-09-09T23:31:10Z "" FAILED)" > "$work/capture.json"
out=$(run)
expect_sent_count 2 "a second failed close-out"
printf '%s' "$(capture_json 60 UP 2026-09-10T23:31:05Z COMPLETED COMPLETED)" > "$work/capture.json"
out=$(run)
expect_sent_count 2 "a completed close-out after failures"
teardown

# --- 5: no close-out finished in >26h yields STALE-CLOSE-OUT ----------------
setup "$(capture_json 100000 UP 2026-09-05T23:31:21Z COMPLETED COMPLETED)"
out=$(run)
expect_status "$out" STALE-CLOSE-OUT
printf '%s' "$out" | grep -q "27h ago" || fail "the alert should say how stale"
pass
teardown

# --- 6: 26h is not yet stale ------------------------------------------------
setup "$(capture_json 90000 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED)"
out=$(run)
expect_status "$out" OK
teardown

# --- 7: "never" is a finding ------------------------------------------------
setup "$(capture_json "" UP "" COMPLETED COMPLETED)"
out=$(run)
expect_status "$out" STALE-CLOSE-OUT
expect_sent_count 1 "never: the alert, and no close-out push for a run that never happened"
teardown

# --- 8: it COMPOSES with a capture finding rather than replacing it ---------
setup "$(capture_json 100000 OUT_OF_SERVICE 2026-09-05T23:31:21Z COMPLETED COMPLETED)"
out=$(run)
expect_status "$out" "NOT-CAPTURING+STALE-CLOSE-OUT"
teardown

# --- 9: a stale close-out NEVER restarts the backend ------------------------
# The gate that must not rot: restarting a JVM does not fetch an archive, and
# the restart is only ever for a wedged recorder with markets in scope.
setup "$(capture_json 999999 UP 2026-09-01T23:31:21Z COMPLETED COMPLETED)"
for _ in 1 2 3 4; do out=$(run); done
if [[ -s "$work/restarts.log" ]]; then
  fail "a stale close-out restarted the backend $(wc -l < "$work/restarts.log") time(s)"
else pass; fi
expect_status "$out" STALE-CLOSE-OUT
teardown

# --- 10: a failed close-out is retried when the push fails ------------------
# Commit-on-delivery: a failed POST must not advance the de-dupe key, or the
# run is announced to nobody and the script believes it was announced.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z "" FAILED)"
cat > "$work/bin/curl" <<'STUB'
#!/bin/bash
url=""
for a in "$@"; do case "$a" in http*) url="$a" ;; esac; done
case "$url" in
  *ntfy*)          exit 7 ;;
  *health/capture) cat "$WORK/capture.json"; exit 0 ;;
  *actuator/health) printf '{"status":"UP"}\n200\n'; exit 0 ;;
esac
exit 1
STUB
chmod +x "$work/bin/curl"
out=$(run)
printf '%s' "$out" | grep -q "close-out-not-committed" || fail "a failed push should not commit the key"
pass
[[ -s "$work/home/.raptor/heartbeat.closeout" ]] && fail "the de-dupe key was written despite a failed push"
teardown

# --- 11: an analysis component on the payload is now IGNORED (#255) ---------
# THE NEGATIVE CONTROL FOR A DELETION, and the reason it is a case rather than
# four deleted ones. This payload is exactly what used to raise STALE-ANALYSIS:
# `analysis.details.stale` true, with a perfectly healthy capture half beside
# it. Slice 10 moved the derivation to overround-analysis, which reports its own
# staleness on its own endpoint, and dropped `analysis` from paddock's capture
# group — so the probe went. Deleting its cases and stopping there would have
# left nothing to fail if somebody re-added it.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED true)"
out=$(run)
expect_status "$out" OK
printf '%s' "$out" | grep -q "STALE-ANALYSIS" \
  && fail "the analysis probe is gone (#255) but something still raised STALE-ANALYSIS"
pass
teardown

# --- 12: the close-out probe still fires on its own -------------------------
# The half that stayed. Case 5 already covers it on a payload with no analysis
# component at all; this one repeats it WITH the ignored component present, so
# that removing the analysis probe cannot be shown to have taken the capture
# finding beside it.
setup "$(capture_json 100000 UP 2026-09-05T23:31:21Z COMPLETED COMPLETED true)"
out=$(run)
expect_status "$out" STALE-CLOSE-OUT
teardown

# --- 13: an ignored analysis component NEVER restarts the backend -----------
# The same gate as case 9. A stale derivation could not justify restarting the
# recorder when it was reported here, and it certainly cannot now that it is not.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED true)"
for _ in 1 2 3 4; do out=$(run); done
if [[ -s "$work/restarts.log" ]]; then
  fail "an analysis component restarted the backend $(wc -l < "$work/restarts.log") time(s)"
else pass; fi
expect_status "$out" OK
teardown

# --- 14: a backend that publishes no analysis component at all is silent ----
# The shape paddock actually deploys after #255: `analysis` is not in the capture
# group, so the field is simply not on the wire.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED)"
out=$(run)
expect_status "$out" OK
teardown


# --- 15: a suspend that overlapped play is reported, once -------------------
# The finding #291 exists for, and the only one here that is retrospective by
# construction: while the lid was shut nothing ran, so this is a report and not
# a warning.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED "" 2026-09-17T19:42:11Z SLEEP 3)"
out=$(run)
grep -q "slept 322s while 12 market(s) were in scope, 3 in play" "$work/sent.log" \
  || fail "the wake report should say what the suspend cost: $(cat "$work/sent.log")"
pass
# It is an EVENT, not a status: a gap that has already ended must not hold the
# state word, or the next pass reports a recovery from a fault that was over
# before anybody was told about it.
expect_status "$out" OK

# --- 16: the same gap is not reported again ---------------------------------
# One push so far: this. A five-minute cadence must not turn one suspend into
# 288 notifications.
out=$(run); out=$(run)
expect_sent_count 1 "three passes, one gap"

# --- 17: a NEW gap is a new report ------------------------------------------
printf '%s' "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED "" 2026-09-17T21:05:00Z SLEEP 2)" \
  > "$work/capture.json"
out=$(run)
expect_sent_count 2 "a second gap"
teardown

# --- 18: no gap block, no report --------------------------------------------
# The quiet end-of-evening lid close, where the app publishes nothing because
# nothing was in play. The silence is the feature.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED)"
out=$(run)
expect_sent_count 0 "no gap and a completed close-out"
grep -q "in play" "$work/sent.log" && fail "a pass with no gapInPlay block reported a gap"
pass
expect_status "$out" OK
teardown

# --- 19: a cause that is not SLEEP is named as itself -----------------------
# #290 made the word mean something — it is decided from the wall-vs-monotonic
# clock now, not from whichever detector noticed first — so calling a DISCONNECT
# a suspend would send its reader looking for a lid that was never closed.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED "" 2026-09-17T19:42:11Z DISCONNECT 1)"
out=$(run)
grep -q "lost 322s to DISCONNECT" "$work/sent.log" || fail "the cause should be named: $(cat "$work/sent.log")"
pass
grep -q "slept" "$work/sent.log" && fail "a DISCONNECT was reported as a suspend"
pass
teardown

# --- 20: a gap that overlapped play NEVER restarts the backend --------------
# The same gate as cases 9 and 13, and sharper: the gap is over. Restarting the
# recorder that survived a suspend would answer a lost interval by losing more.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED "" 2026-09-17T19:42:11Z SLEEP 3)"
for _ in 1 2 3 4; do out=$(run); done
if [[ -s "$work/restarts.log" ]]; then
  fail "a reported gap restarted the backend $(wc -l < "$work/restarts.log") time(s)"
else pass; fi
expect_status "$out" OK
teardown

# --- 21: the wake report is retried when the push fails ---------------------
# Commit-on-delivery, as for the close-out failure: a suspend announced to nobody and then
# believed announced is the exact failure this whole script exists to remove.
setup "$(capture_json 3600 UP 2026-09-07T23:31:21Z COMPLETED COMPLETED "" 2026-09-17T19:42:11Z SLEEP 3)"
cat > "$work/bin/curl" <<'STUB'
#!/bin/bash
url=""
for a in "$@"; do case "$a" in http*) url="$a" ;; esac; done
case "$url" in
  *ntfy*)          exit 7 ;;
  *health/capture) cat "$WORK/capture.json"; exit 0 ;;
  *actuator/health) printf '{"status":"UP"}\n200\n'; exit 0 ;;
esac
exit 1
STUB
chmod +x "$work/bin/curl"
out=$(run)
printf '%s' "$out" | grep -q "gap-not-committed" || fail "a failed push should not commit the gap key"
pass
[[ -s "$work/home/.raptor/heartbeat.gap" ]] && fail "the gap de-dupe key was written despite a failed push"
teardown

# --- 22: a recorder wedged with markets in scope IS restarted --------------
# The witness for case 23 below, and the only case here where a restart is the
# RIGHT answer: without it, "no restart" in 23 would pass just as well against a
# heartbeat that could never restart anything.
wedged_json='{"status":"OUT_OF_SERVICE","components":{"scope":{"status":"UP","details":{"marketsInScope":5}},"captureCoverage":{"status":"OUT_OF_SERVICE","details":{"reason":"recorder is not capturing"}},"recorder":{"status":"UP","details":{"state":"RECONNECTING"}}}}'
setup "$wedged_json"
out=$(run); out=$(run); out=$(run)
[[ "$(grep -c . "$work/restarts.log")" == "1" ]] && pass \
  || fail "a wedged recorder with markets in scope was restarted $(grep -c . "$work/restarts.log") time(s), expected once"
teardown

# --- 23: RESTART_ENABLED=false IN ops.env makes it observe-only (#322) ------
# What a shadow heartbeat depends on: it runs beside the real one and must not
# restart the backend the real one watches. The setting has to come through
# ops.env, and that only works if the config is read BEFORE the tunables take
# their defaults — the first port read it after, and the file's value was
# silently ignored.
setup "$wedged_json"
printf 'RESTART_ENABLED=false\n' > "$work/ops.env"
for _ in 1 2 3; do
  out=$(WORK="$work" HOME="$work/home" HEARTBEAT_PATH_PREFIX="$work/bin" \
    RAPTOR_OPS_ENV="$work/ops.env" NTFY_ENABLED=true NTFY_TOPIC=test-topic \
    HEALTH_URL="http://stub/actuator/health" \
    CAPTURE_URL="http://stub/actuator/health/capture" \
    "$HEARTBEAT" 2>&1)
done
[[ -s "$work/restarts.log" ]] \
  && fail "RESTART_ENABLED=false in ops.env still restarted the backend" || pass
expect_status "$out" NOT-CAPTURING
teardown

echo "  $passed passed, $failed failed"
exit $(( failed > 0 ? 1 : 0 ))
