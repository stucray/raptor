# raptor ops configuration: sourced by every ops script, never run directly.
#
# WHY ONE FILE. The ops tooling is public, and the machine it runs on is not:
# where the state lives, what the launchd jobs are called, which containers and
# ports the stack uses, and where the alerting secrets are. All of that is read
# here, from `ops.env`, so no script carries a path, a label or a topic of its
# own. `ops.env.example` in this directory documents every setting.
#
# PRECEDENCE: the environment, then ops.env, then the defaults below. The
# environment wins so that a test suite can stub any setting without a file,
# and so that a one-off `RAPTOR_BACKEND_URL=… ./raptor-heartbeat.sh` does what
# it says.
#
# WHERE ops.env IS FOUND. $RAPTOR_OPS_ENV if set; otherwise beside the script
# that sourced this — which, deployed, is the state directory, because
# bin/deploy-agents copies the scripts there and the operator keeps ops.env
# there; otherwise ~/.raptor/ops.env, which is where a script run from the repo
# (bin/deploy-agents, post-boot-check.sh) looks. None at all means the defaults.
#
# ops.env is KEY=value lines. A value may be double-quoted, and may begin with
# `~` or contain `$HOME`, which are expanded; nothing else is evaluated, since
# the file is read by jobs that run unattended.

raptor_ops_env_file="${RAPTOR_OPS_ENV:-$(cd "$(dirname "${BASH_SOURCE[1]:-$0}")" && pwd)/ops.env}"
[[ -z "${RAPTOR_OPS_ENV:-}" && ! -r "$raptor_ops_env_file" ]] && raptor_ops_env_file="$HOME/.raptor/ops.env"
if [[ -r "$raptor_ops_env_file" ]]; then
  while IFS= read -r raptor_line || [[ -n "$raptor_line" ]]; do
    [[ "$raptor_line" =~ ^[[:space:]]*([A-Z][A-Z0-9_]*)=(.*)$ ]] || continue
    raptor_key="${BASH_REMATCH[1]}"
    raptor_val="${BASH_REMATCH[2]}"
    # Already in the environment: the environment wins.
    [[ -n "${!raptor_key+x}" ]] && continue
    raptor_val="${raptor_val%%[[:space:]]#*}"   # a trailing comment
    raptor_val="${raptor_val%"${raptor_val##*[![:space:]]}"}"   # trailing blanks
    raptor_val="${raptor_val%\"}"; raptor_val="${raptor_val#\"}"
    raptor_val="${raptor_val/#\~/$HOME}"
    raptor_val="${raptor_val//\$HOME/$HOME}"
    export "$raptor_key=$raptor_val"
  done < "$raptor_ops_env_file"
fi
unset raptor_line raptor_key raptor_val

# --- the defaults: raptor's own compose.yaml, and nothing personal ----------
: "${RAPTOR_STATE_DIR:=$HOME/.raptor}"
: "${RAPTOR_LABEL_PREFIX:=local.raptor}"
: "${RAPTOR_BACKEND_URL:=http://127.0.0.1:8087}"
: "${RAPTOR_POSTGRES_CONTAINER:=raptor-postgres-1}"
: "${RAPTOR_BACKEND_CONTAINER:=raptor-backend-1}"
: "${RAPTOR_FRONTEND_CONTAINER:=raptor-frontend-1}"
: "${RAPTOR_DB_NAME:=paddock}"
: "${RAPTOR_DB_USER:=paddock}"
: "${RAPTOR_BACKUP_DIR:=$RAPTOR_STATE_DIR/backups}"
# The sops file carrying `ntfy-enabled` and `ntfy-topic`. Empty means no alerts
# are sent; every script then logs what it would have said.
: "${RAPTOR_SECRETS:=}"
export RAPTOR_STATE_DIR RAPTOR_LABEL_PREFIX RAPTOR_BACKEND_URL \
  RAPTOR_POSTGRES_CONTAINER RAPTOR_BACKEND_CONTAINER RAPTOR_FRONTEND_CONTAINER \
  RAPTOR_DB_NAME RAPTOR_DB_USER RAPTOR_BACKUP_DIR RAPTOR_SECRETS
