#!/bin/bash
# Tests for scripts/raptor-ops-env.sh — the one place the ops tooling reads its
# machine-specific settings from (#322).
#
# WHY THESE EXIST. Every agent sources this file, so a mistake in it is a
# mistake in all of them at once, and the failure is quiet: a setting read from
# the wrong place still produces a value, just not the operator's. The cases pin
# the three sources and their order, and that the file is DATA — it is read by
# jobs that run unattended, so a value must never be executed.
set -uo pipefail
# No operator config may leak into a test: every setting comes from the case.
# That means the ENVIRONMENT as well as ops.env — the environment beats the file
# by design, so a setting exported by whatever launched this suite (the deploy
# gate, a shell with a shadow's settings) would otherwise steer the cases.
while IFS= read -r inherited; do unset "$inherited"; done \
  < <(compgen -e | grep -E '^(RAPTOR_|RESTART_|NTFY_|HEALTH_URL$|CAPTURE_URL$)')
export RAPTOR_OPS_ENV=/dev/null
cd "$(dirname "$0")/../.."
LOADER="$PWD/scripts/raptor-ops-env.sh"

passed=0; failed=0
fail() { echo "  FAIL: $1"; failed=$(( failed + 1 )); }
pass() { passed=$(( passed + 1 )); }
check() { # $1 = actual  $2 = expected  $3 = case
  [[ "$1" == "$2" ]] && pass || fail "$3: got '$1', expected '$2'"
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Runs the loader in a clean environment and prints one variable.
read_setting() { # $1 = ops.env path  $2 = variable  [$3… = extra env]
  local file="$1" var="$2"; shift 2
  env -i HOME="$work/home" PATH="$PATH" RAPTOR_OPS_ENV="$file" "$@" \
    bash -c 'source "$0"; printf "%s" "${!1}"' "$LOADER" "$var"
}

echo "ops-env:"

# With no file, the defaults: raptor's own compose, nothing personal.
check "$(read_setting /dev/null RAPTOR_STATE_DIR)" "$work/home/.raptor" "default state dir"
check "$(read_setting /dev/null RAPTOR_LABEL_PREFIX)" "local.raptor" "default label prefix"
check "$(read_setting /dev/null RAPTOR_SECRETS)" "" "no secrets by default, so no alerts"
check "$(read_setting /dev/null RAPTOR_BACKUP_DIR)" "$work/home/.raptor/backups" \
  "backup dir follows the state dir"

cat > "$work/ops.env" <<'EOF'
# a comment line
RAPTOR_STATE_DIR="~/.somewhere"
RAPTOR_LABEL_PREFIX=com.example.raptor   # trailing comment
RAPTOR_BACKEND_URL=http://127.0.0.1:9999
RAPTOR_SECRETS=$HOME/secrets.sops.yaml
RAPTOR_DB_NAME=$(touch INJECTED)
lowercase=ignored
EOF

check "$(read_setting "$work/ops.env" RAPTOR_STATE_DIR)" "$work/home/.somewhere" \
  "quotes stripped and ~ expanded"
check "$(read_setting "$work/ops.env" RAPTOR_LABEL_PREFIX)" "com.example.raptor" \
  "a trailing comment is not part of the value"
check "$(read_setting "$work/ops.env" RAPTOR_SECRETS)" "$work/home/secrets.sops.yaml" \
  "\$HOME expanded"
check "$(read_setting "$work/ops.env" RAPTOR_BACKUP_DIR)" "$work/home/.somewhere/backups" \
  "a default derived from a setting the file changed"

# THE FILE IS DATA. A command substitution is kept as text, never run.
( cd "$work" && read_setting "$work/ops.env" RAPTOR_DB_NAME >/dev/null )
[[ -e "$work/INJECTED" ]] && fail "a value in ops.env was executed" || pass
check "$(read_setting "$work/ops.env" RAPTOR_DB_NAME)" '$(touch INJECTED)' \
  "a command substitution stays literal"

# The environment wins over the file, so a test or a one-off run can override.
check "$(read_setting "$work/ops.env" RAPTOR_BACKEND_URL RAPTOR_BACKEND_URL=http://x:1)" \
  "http://x:1" "the environment beats ops.env"

# A named file that does not exist is not silently swapped for another.
mkdir -p "$work/home/.raptor"
echo 'RAPTOR_LABEL_PREFIX=from.home' > "$work/home/.raptor/ops.env"
check "$(read_setting "$work/missing.env" RAPTOR_LABEL_PREFIX)" "local.raptor" \
  "an explicit RAPTOR_OPS_ENV is not replaced by ~/.raptor/ops.env"

echo "  $passed passed, $failed failed"
(( failed == 0 ))
