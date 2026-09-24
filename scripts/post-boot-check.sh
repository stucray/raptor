#!/bin/bash
# Did the whole chain come back after a reboot?
#
#   bash scripts/post-boot-check.sh
#
# NOT an agent, and deliberately not in `bin/deploy-agents` (#192). Nothing
# loads it: it runs for ten seconds when a human asks, so the state-directory
# indirection the launchd jobs need — a branch checkout must not rewrite a
# script mid-run — buys this nothing. It reads the same ops.env as the agents,
# so it checks the containers, port and label this machine actually uses.
#
# The chain under test, in order, because each link only matters if the one
# before it held:
#   1. Docker Desktop started at login          (settings-store.json AutoStart)
#   2. the containers came back                 (restart: unless-stopped)
#   3. the backend is serving                   (health 200)
#   4. the recorder re-acquired and re-subscribed
#   5. the heartbeat agent is loaded and has run since boot
#
# A FAILURE AT STEP 1 OR 2 IS THE INTERESTING ONE. That is the case the whole
# exercise exists for, and until this script has been run once after a real
# reboot, none of it is verified — the settings file is owned by Docker Desktop
# and may be rewritten when it quits.
# Configuration first, before any default below reads a setting: ops.env
# can only supply a value the script has not already defaulted.
# shellcheck source=raptor-ops-env.sh
source "$(dirname "$0")/raptor-ops-env.sh"

set -uo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
HEARTBEAT_LABEL="$RAPTOR_LABEL_PREFIX.heartbeat"

pass=0; fail=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); }
info() { printf '        %s\n' "$1"; }

echo "boot: $(sysctl -n kern.boottime | sed 's/.*} //')"
echo "now:  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo

echo "1. Docker daemon"
if docker info >/dev/null 2>&1; then
  ok "docker responds — Docker Desktop started on its own"
else
  bad "docker daemon unreachable — AutoStart did not take (check Settings > General)"
fi

echo "2. containers"
for c in "$RAPTOR_POSTGRES_CONTAINER" "$RAPTOR_BACKEND_CONTAINER" "$RAPTOR_FRONTEND_CONTAINER"; do
  state=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null)
  policy=$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$c" 2>/dev/null)
  if [[ "$state" == "running" ]]; then
    ok "$c running (policy=$policy)"
  else
    bad "$c is ${state:-absent} (policy=${policy:-unknown})"
  fi
done

echo "3. backend"
if curl -sf --max-time 10 "$RAPTOR_BACKEND_URL/actuator/health" >/dev/null 2>&1; then
  ok "health 200"
else
  bad "$RAPTOR_BACKEND_URL/actuator/health does not answer"
fi

echo "4. recorder"
body=$(curl -sS --max-time 10 "$RAPTOR_BACKEND_URL/actuator/health/capture" 2>/dev/null)
if printf '%s' "$body" | jq -e . >/dev/null 2>&1; then
  st=$(printf '%s' "$body" | jq -r '.components.recorder.details.state')
  sid=$(printf '%s' "$body" | jq -r '.components.recorder.details.sessionId')
  fr=$(printf '%s' "$body" | jq -r '.components.recorder.details.framed')
  wr=$(printf '%s' "$body" | jq -r '.components.recorder.details.written')
  scope=$(printf '%s' "$body" | jq -r '.components.scope.details.marketsInScope')
  sub=$(printf '%s' "$body" | jq -r '.components.scope.details.subscribed')
  # session/framed/written are absent — printed as null — whenever there is no
  # live session, which is the correct reading of an IDLE recorder rather than a
  # sign this script has drifted from the health payload.
  info "state=$st session=$sid framed=$fr written=$wr scope=$scope subscribed=$sub"
  # A NEW session id is the correct outcome, not a fault: the lease was
  # reacquired and #129's reconciliation closes the one the reboot orphaned.
  if [[ "$scope" -gt 0 && "$st" != "RECORDING" ]]; then
    bad "$scope market(s) in scope but recorder is $st"
  elif [[ "$scope" -gt 0 ]]; then
    ok "RECORDING, $sub of $scope markets re-subscribed"
  else
    ok "$st with nothing in scope — correct when no fixture is inside the horizon"
  fi
else
  bad "capture health unreadable"
fi

echo "5. heartbeat"
# NOT `launchctl list | grep -q ...`: under `set -o pipefail`, grep -q closes the
# pipe on its first match, launchctl dies with SIGPIPE, and the pipeline reports
# 141 — so a MATCH reads as a failure. Same family as `| head` killing a
# producer. Capture first, match second.
agents=$(launchctl list 2>/dev/null)
if grep -qF "$HEARTBEAT_LABEL" <<<"$agents"; then
  ok "agent loaded"
else
  bad "$HEARTBEAT_LABEL is NOT loaded"
fi
last=$(tail -1 "$RAPTOR_STATE_DIR/heartbeat.log" 2>/dev/null)
info "last log line: ${last:-<none>}"
# The log is what proves it ran since boot rather than merely being registered.
boot_epoch=$(sysctl -n kern.boottime | sed -n 's/.*sec = \([0-9]*\).*/\1/p')
log_epoch=$(stat -f %m "$RAPTOR_STATE_DIR/heartbeat.log" 2>/dev/null || echo 0)
if (( log_epoch > boot_epoch )); then
  ok "heartbeat has written since boot"
else
  bad "heartbeat log has not been touched since boot — it has not run"
fi

echo
if (( fail == 0 )); then
  echo "ALL $pass CHECKS PASSED — the chain came back unaided."
else
  echo "$fail CHECK(S) FAILED, $pass passed."
fi
exit $(( fail > 0 ))
