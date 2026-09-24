# raptor

Captures football data verbatim into PostgreSQL: the Betfair Exchange Stream,
Betfair's historic BASIC files, the Betfair REST catalogue (to decide what to
capture), and the football-data.co.uk archive. It stores each source unparsed
and keeps native identifiers exactly as received. Interpretation happens
downstream. See [CHARTER.md](CHARTER.md).

## Build

Java 26 and Docker (the tests use Testcontainers PostgreSQL).

    ./mvnw verify

`verify` runs, in order: the no-Betfair-data scan (`validate`), the build with
Error Prone and NullAway, the tests, and PMD. PMD is a signal, not a gate: its
report is committed under `docs/reports/`, and CI fails only if that report is
stale.

Modules: `schema` (the `raw`/`query`/`batch` Flyway migrations, published),
`capture` (the capture code, a library) and `app` (the Spring Boot
application).

## Run

    ./mvnw install -DskipTests
    ./mvnw -pl app spring-boot:run

This starts PostgreSQL through `compose.yaml` on port 5433, and the application
on 8085 — the ports of the resident stack, so on a machine already running it,
set `SERVER_PORT` to something else and remember the database is the live one.
The volume is external and must exist first (`docker volume create
raptor_postgres-data`; see `compose.yaml` for why compose will not create it). The recorder stays off unless `RAPTOR_STREAM_ENABLED=true`
is set and Betfair credentials (`BETFAIR_APP_KEY`, `BETFAIR_USERNAME`,
`BETFAIR_PASSWORD`, `BETFAIR_CERT_PEM_B64`, `BETFAIR_KEY_PEM_B64`) are present.
Custody files are read from `RAPTOR_DATA_ROOT`, and which leagues to capture
from `config/capture.properties`.

The operational screens are an Angular app in `frontend/raptor-ui`: `npm ci`
then `npx ng serve` there proxies `/api` to 8085. `bin/up` builds the image and
starts the whole stack (UI on 8086), with the recorder ON; `bin/down` stops the
application and leaves the database running. Set `RAPTOR_DATA_ROOT` (the host
custody root) in `~/.raptor/ops.env`, which `bin/up` exports — not in a `.env`
here, which the no-Betfair-data scan refuses because it names a home directory.
A bare `docker compose up` does not read ops.env and mounts the default
`~/raptor/data` instead, so start the stack with `bin/up`. The screens read `raw` and the ledger only, as the
read identity, and never `query`: whether capture is running must be
answerable without anything downstream.

## Operate

The watchers that must outlive the application — heartbeat, keep-awake, backup,
restore drill and the nightly close-out trigger — are launchd agents, configured
by an `ops.env` that keeps every path and label out of the repository. See
[docs/ops.md](docs/ops.md).

## Tests and data

No Betfair data is committed. The tests read synthetic samples (market ids
`1.9xxxxxxxx`, event ids `999xxxxx`) whose shape is pinned to the real sources
by manifests of key paths and types. The real-data tiers
(`-Preplay`, and the `*LiveSmokeTest` classes) run only where a corpus or
credentials are present, and are skipped otherwise.

## Licence

MIT. See [LICENSE](LICENSE).
