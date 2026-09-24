#!/usr/bin/env bash
#
# Fire the nightly close-out (#334).
#
# WHY LAUNCHD AND NOT SPRING. The close-out was a Spring cron, and Spring waits
# out a cron's delay on the monotonic clock, which inside the Docker VM does not
# advance while the Mac sleeps. So a firing did not run late and catch up — it
# slipped by exactly the sleep that followed: 63 minutes on 2026-09-22/23, with
# nothing reporting it. launchd's StartCalendarInterval is documented to start a
# job whose time passed during sleep on the next wake, coalescing missed ones
# into one. That catch-up is the whole reason this file exists; the curl is
# incidental.
#
# WHAT IT DOES. POSTs /ops/close-out and logs the verdict. The app holds an
# advisory lock for the run, so this landing beside a hand-run POST gets a 409
# and stands down rather than sweeping the archive twice.
#
# WHY IT RETRIES, AND ONLY ON "NOT LISTENING". On wake launchd fires at once,
# and the Docker VM may not be answering yet; a single refused connection would
# lose the night to the 26h STALE-CLOSE-OUT. So a connection that could not be
# made is retried for a bounded while. Anything that DID reach the app — a 5xx,
# a 404 from an image without the endpoint, a timeout mid-run — is not retried:
# the app has recorded what happened in batch.close_out, and a retry would be a
# second run, not a second attempt at the first.
#
# DEPLOY: `bin/deploy-agents`, like every agent here — launchd runs the copy in
# the state directory (RAPTOR_STATE_DIR) so a branch checkout cannot rewrite it mid-run.
#
# Overridable env: RAPTOR_CLOSE_OUT_URL, CLOSE_OUT_ATTEMPTS, CLOSE_OUT_RETRY_S,
#                  CLOSE_OUT_PATH_PREFIX (prepended to PATH, for stubbing curl).

# Configuration first, before any default below reads a setting: ops.env
# can only supply a value the script has not already defaulted.
# shellcheck source=raptor-ops-env.sh
source "$(dirname "$0")/raptor-ops-env.sh"

set -uo pipefail
export PATH="${CLOSE_OUT_PATH_PREFIX:+$CLOSE_OUT_PATH_PREFIX:}/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

URL="${RAPTOR_CLOSE_OUT_URL:-$RAPTOR_BACKEND_URL/ops/close-out}"
ATTEMPTS="${CLOSE_OUT_ATTEMPTS:-30}"
RETRY_S="${CLOSE_OUT_RETRY_S:-20}"

ts() { date -u +%Y-%m-%dT%H:%M:%SZ; }

body_file=$(mktemp)
trap 'rm -f "$body_file"' EXIT

for (( attempt = 1; attempt <= ATTEMPTS; attempt++ )); do
  code=$(curl -sS -o "$body_file" -w '%{http_code}' --max-time 1800 -X POST "$URL" 2>/dev/null)
  rc=$?
  body=$(cat "$body_file" 2>/dev/null)
  # 7 = could not connect: nothing reached the app, so nothing ran. The only
  # outcome worth another try.
  if (( rc == 7 )); then
    if (( attempt < ATTEMPTS )); then
      sleep "$RETRY_S"
      continue
    fi
    echo "$(ts) FAILED: $URL not answering after $ATTEMPTS attempt(s); no close-out ran"
    exit 1
  fi
  if (( rc != 0 )); then
    echo "$(ts) FAILED: curl exit $rc on attempt $attempt (the app may have run it; see batch.close_out) ${body}"
    exit 1
  fi
  case "$code" in
    200) echo "$(ts) close-out ran (attempt $attempt): $body"; exit 0 ;;
    409) echo "$(ts) close-out already running; left to that run: $body"; exit 0 ;;
    *)   echo "$(ts) FAILED: HTTP $code on attempt $attempt: $body"; exit 1 ;;
  esac
done
