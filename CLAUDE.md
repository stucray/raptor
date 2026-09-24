# raptor

Capture only. Read [CHARTER.md](CHARTER.md) first: raptor stores each source
verbatim, keeps native ids exactly as received, and never interprets.

## Hard rules

- **No Betfair data, no real market ids, no personal details** in any committed
  file. `scripts/scan-no-betfair-data.py` runs in `mvn validate` and fails the
  build. Tests use the synthetic samples under `capture/src/test/resources`;
  never commit a real capture, even trimmed.
- **No parser.** Nothing here may depend on a `wire`/parser package, and the
  parsers must not be on the classpath (`WritePathIsolationTest`).
- **The inherited migrations are frozen.** `schema/.../db/acquisition/V1`–`V33`
  must stay byte-identical to what the live database recorded, except V16 (see
  `Version16ChecksumTest`). A change is a new migration, never an edit.
- **`raw` is append-only.** Never `update`, never `delete`.
- Every COPY timestamp carries an explicit offset.
- **The ops scripts carry nothing machine-specific** (`docs/ops.md`). Paths,
  labels, containers, ports and the secrets file come from `ops.env` through
  `scripts/raptor-ops-env.sh`, which each script sources as its FIRST statement —
  a default set before it silently wins over the file. Every suite under
  `scripts/test/` pins `RAPTOR_OPS_ENV=/dev/null`, and `bin/deploy-agents`
  runs them all before it touches launchd.

## Build

- `./mvnw verify` from the root. The committed PMD report under
  `docs/reports/` must be regenerated after the last edit; `CI=true` makes a
  stale report fail the build.
- Module-scoped runs resolve siblings from `~/.m2`: use `-am`
  (`./mvnw -pl capture -am test`).
- `-Dtest` takes commas; with `-am` add `-Dsurefire.failIfNoSpecifiedTests=false`
  and confirm the `Tests run:` lines rather than `BUILD SUCCESS`.
