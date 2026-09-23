# Charter

raptor captures football data, and only captures it.

## What raptor is

- **A capture application.** It records the Betfair Exchange Stream for the markets in scope, loads Betfair's historic BASIC files, polls Betfair's REST catalogue to decide scope, and fetches the football-data.co.uk archive. Each source is its own adapter.
- **The system of record.** What it captures goes into PostgreSQL's `raw` schema, append-only. `raw` is never updated, and never deleted from outside an explicit retention decision. A message that isn't persisted when it arrives is gone for good, because the live stream can't be replayed.
- **The keeper of custody.** Source files on disk are kept read-only. A load is proven faithful against them by digest, because `jsonb` doesn't preserve bytes.
- **Its own witness.** It records its own capture ledger (sessions, gaps, scope) and reports whether capture is running and what it got.

## What raptor is not

- **Not an interpreter.** raptor stores each source **verbatim** and never parses the payload. Ingest does framing and addressing only: where one message ends and the next begins, and which market it belongs to. A parse bug must be structurally unable to reach the system of record; `WritePathIsolationTest` holds that, and there is no parser anywhere in this build.
- **Not a mapper.** Native identifiers are kept **exactly as received**: Betfair market, event and selection ids, competition names, country codes, football-data division codes. Canonical countries, competitions and teams are a separate concern, deliberately deferred. Because nothing is overwritten with a mapping, one can be added later without re-capturing anything.
- **Not analysis.** Parsing, projection, joins, features and results live downstream, in a separate private application that reads `raw`. A comparison population, a cohort or a control set is an analysis construct, not a capture concern.
- **Not a data publisher.** Betfair's data is licensed for personal use. This repository contains no Betfair data, nothing derived from it that identifies a real market, and no personal details. `scripts/scan-no-betfair-data.py` enforces that on every build. Tests run on synthetic samples, whose shape is pinned by manifests of key paths and types.

## What to capture

**Capture the leagues being traded, from whatever sources cover them.** Scope follows the trading programme. A market is captured because it may be traded, not to fill capacity or to serve as a comparison. New sources (for example, a live match-events feed) arrive as new adapters under the same rules: verbatim, native ids, no interpretation.

## Rules that follow from this

- The write path never blocks on the database. Framing happens on the read loop, the queue is bounded, and COPY runs in short batches. A spill file is used only when PostgreSQL can't accept a write.
- Single writer: the capture lease is a PostgreSQL advisory lock. Two recorders can never both write `raw`.
- Every timestamp written by COPY carries an explicit offset.
- Absence has one spelling, and it is NULL.
- raptor never stops the database it writes to.

Issue numbers in comments (`#122`, `PRD #73`) refer to the private repository this code came from. They're kept because each one marks a decision that was made for a reason.
