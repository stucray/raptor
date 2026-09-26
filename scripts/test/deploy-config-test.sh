#!/bin/bash
# Tests for bin/deploy-config — the deployed copy of capture.properties (#19).
#
# WHY THESE EXIST. The copy is there so that git operations in the checkout can
# no longer reconfigure live capture. So the cases are about what does NOT
# reach the deployed file — a branch checkout, a commit not yet deployed, an
# uncommitted edit, a HEAD without the file — as much as about what does.
#
# HOW. A throwaway git repository holding a copy of the script and the ops-env
# loader, with RAPTOR_CONFIG_DIR pointed into the scratch directory. No Docker,
# no network, nothing of the operator's.
set -uo pipefail
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^RAPTOR_')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
ROOT="$PWD"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }
check() { (( $1 == 0 )) && pass || fail "$2 ($out)"; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
repo="$work/repo"
DEPLOYED="$work/deployed"
export RAPTOR_CONFIG_DIR="$DEPLOYED" RAPTOR_STATE_DIR="$work/state"

mkdir -p "$repo/bin" "$repo/scripts" "$repo/config"
cp "$ROOT/bin/deploy-config" "$repo/bin/"
cp "$ROOT/scripts/raptor-ops-env.sh" "$repo/scripts/"
g() { git -C "$repo" "$@" >/dev/null 2>&1; }
g init -b main
g config user.email test@example.invalid
g config user.name test
printf 'capture.leagues=League A\ncapture.market-types=MATCH_ODDS\n' > "$repo/config/capture.properties"
g add -A; g commit -m one

run() { out="$("$repo/bin/deploy-config" "$@" 2>&1)"; rc=$?; }
deployed() { cat "$DEPLOYED/capture.properties" 2>/dev/null; }

echo "deploy-config:"

# Never deployed: --check says so, and a check writes nothing.
run --check
check $(( rc != 1 )) "--check before any deploy exits 1"
[[ "$out" == *MISSING* ]]; check $? "--check before any deploy says MISSING"
[[ ! -e "$DEPLOYED/capture.properties" ]]; check $? "--check writes nothing"

# A deploy writes the committed file, says where it came from, leaves no temp.
run
check $(( rc != 0 )) "a clean deploy exits 0"
[[ "$(deployed)" == "$(git -C "$repo" show HEAD:config/capture.properties)" ]]
check $? "the deployed file is the committed one"
sha1=$(git -C "$repo" rev-parse --short HEAD)
[[ "$(cat "$DEPLOYED/DEPLOYED_FROM")" == "$sha1 main "* ]]; check $? "DEPLOYED_FROM names the commit and branch"
[[ -z "$(find "$DEPLOYED" -name '.capture.properties.*')" ]]; check $? "no temporary file is left behind"

run --check
check $(( rc != 0 )) "--check right after a deploy exits 0"
[[ "$out" == *matches* ]]; check $? "--check right after a deploy says it matches"

# THE CASE #19 WAS FILED FOR: a branch checkout no longer reaches capture.
g checkout -b old-idea
printf 'capture.leagues=League A\ncapture.market-types=MATCH_ODDS\ncapture.control-countries=GB\n' \
  > "$repo/config/capture.properties"
g commit -am "an older idea"
g checkout main
g checkout old-idea
[[ "$(deployed)" != *control-countries* ]]; check $? "checking out another branch leaves the deployed copy alone"
run --check
check $(( rc != 1 )) "--check on a branch whose config differs exits 1"
[[ "$out" == *DRIFT* && "$out" == *"$sha1 main"* ]]; check $? "--check names the drift and what is deployed"
g checkout main

# A commit that has not been deployed is not live either.
printf 'capture.leagues=League A,League B\ncapture.market-types=MATCH_ODDS\n' > "$repo/config/capture.properties"
g commit -am two
[[ "$(deployed)" != *"League B"* ]]; check $? "a commit is not live until it is deployed"
run
check $(( rc != 0 )) "deploying the new commit exits 0"
[[ "$(deployed)" == *"League B"* ]]; check $? "the deploy makes the new commit live"

# An uncommitted edit is refused, and the deployed copy is untouched.
before="$(deployed)"
printf 'capture.leagues=League A,League Typo\ncapture.market-types=MATCH_ODDS\n' > "$repo/config/capture.properties"
run
check $(( rc != 1 )) "an uncommitted edit is refused"
[[ "$out" == *"uncommitted changes"* ]]; check $? "the refusal says why"
[[ "$(deployed)" == "$before" ]]; check $? "a refused deploy leaves the deployed copy as it was"
g checkout -- config/capture.properties

# A HEAD without the file cannot be deployed, and does not empty capture.
g checkout -b no-config
g rm -q config/capture.properties
g commit -m "no config"
run
check $(( rc != 1 )) "a HEAD without the file is refused"
[[ "$(deployed)" == "$before" ]]; check $? "and the deployed copy survives it"

echo "  $passed passed, $failed failed"
(( failed == 0 ))
