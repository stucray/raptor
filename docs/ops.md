# Operating raptor on a Mac

Capture is unreplayable, and nothing inside the application can report that the
application is absent: a stopped process says nothing, which looks exactly like a
quiet night. So the watchers live outside it, as launchd agents on the host.

| Agent | What it does | When |
|---|---|---|
| `heartbeat` | Polls `/actuator/health/capture`; alerts on a state change; restarts a backend that is wedged with markets in scope | every 5 min |
| `keep-awake` | Holds a power assertion while the recorder is recording, and for a while after | resident |
| `backup` | `pg_dump` of the `raw` schema, verified by reading it back | daily, 05:47Z |
| `restore-drill` | Restores the newest dump into a throwaway PostgreSQL and compares counts | weekly (daily while red), 06:11Z |
| `close-out` | `POST /ops/close-out`: the nightly football-data fetch | daily, 23:30Z |

And two scripts a person runs:

- `lid` — "is it safe to shut the lid?" Exit 0 safe, 1 not, 2 unknown. It reads
  the scope, never the recorder state, and says how long the answer holds.
- `scripts/post-boot-check.sh` — after a reboot, did the whole chain come back?

## Configure

Nothing machine-specific is in the repository. Copy
[`scripts/ops.env.example`](../scripts/ops.env.example) to `~/.raptor/ops.env`
and change what differs; every line in the example is the default. The settings
are the state directory, the launchd label prefix, which agents to run, the UTC
times of the scheduled ones, the backend URL and container names, and the sops
file that carries the ntfy topic. With no secrets file, alerts are logged and
not sent.

Every script reads it through `scripts/raptor-ops-env.sh`. The environment
overrides the file, and the file overrides the defaults. The file is data: `~`
and `$HOME` are expanded and nothing else is evaluated.

## Deploy

    bin/deploy-agents           # render, copy, (re)load, verify registration
    bin/deploy-agents --check   # report drift; changes nothing

launchd runs the copies in the state directory, never the repository, so a
branch checkout cannot rewrite or delete a watchdog mid-run. The deploy:

- runs every suite under `scripts/test/` first, and refuses if one fails;
- renders the plist templates in `scripts/launchd/`, converting each UTC time to
  local because `StartCalendarInterval` is local time — so re-deploy after a
  clock or timezone change — and refuses a plist that still has a placeholder
  in it or does not lint;
- refuses while markets are in scope, since reloading keep-awake drops the
  power assertion (`--force` overrides);
- waits for `launchctl bootout` to finish before bootstrapping, because it
  returns before the job is gone, and then checks the job is registered rather
  than that its file is on disk;
- reports, under `--check`, anything installed under the label prefix that is
  not in `RAPTOR_AGENTS`.

## Running a shadow

To prove the tooling on a machine that already has watchers, run only the
heartbeat, observe-only, under its own label and state directory:

    RAPTOR_STATE_DIR=~/.raptor
    RAPTOR_LABEL_PREFIX=local.raptor
    RAPTOR_AGENTS=heartbeat
    RESTART_ENABLED=false
    RAPTOR_SECRETS=

Its verdicts land in `~/.raptor/heartbeat.log` for comparison, and it can
neither alert nor restart anything.

## Hardware

Plug in before capture. `caffeinate -s` is void on battery by design, and
closing the lid on battery sleeps the machine whatever assertion is held — which
is what `lid` is for.
