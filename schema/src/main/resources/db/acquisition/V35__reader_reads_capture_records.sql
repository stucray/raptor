-- paddock#321 (PRD #309): the read identity may read capture's own records of
-- what it holds and what it ran — and still not a single captured message.
--
-- WHY THIS REVERSES V1 AND V6. Both said the read side has no business in `raw`
-- or `batch`: "if it needs something from raw, that is a missing projection, not
-- a missing grant". That was right while paddock's read side showed parsed data
-- and `query` existed to serve it. It is not right for raptor. raptor has no
-- projection — `query` is overround-analysis's since #316 — and its screens are
-- operational: which files custody holds, when each source last delivered, which
-- jobs ran and how they ended. PRD #309 asks for exactly that "from raw and the
-- ledger only". A projection built to avoid this grant would be a copy of
-- capture's bookkeeping kept for the sake of a rule whose reason has gone.
--
-- WHAT IS NOT GRANTED, AND WHY THAT IS THE LINE. The boundary the old rule
-- protected was the system of record's CONTENT: no screen should be able to
-- reach a payload, and nothing read-side should be able to write one. So:
--
--   * select only. The reader still cannot write `raw` or `batch`, and a reader
--     cannot make the system of record wrong.
--   * named tables only, never `all tables in schema`, and no default
--     privileges. `raw.stream_message` (the payloads), `raw.rejected_message`
--     and `raw.market_scope` stay unreadable, and so does every table added
--     later until a migration says otherwise. A table joining this list should
--     cost a sentence here, the way this one did.
--   * of `batch`, only the two tables that say which job ran and how it ended —
--     not the execution context, which holds serialised job state.
--
-- The three `raw.*_file` tables are one row per file custody holds, with its
-- path, digest and size. `raw.football_file` also keeps each CSV's bytes in
-- `content` (upstream republishes in place, so the row is the only copy of a
-- superseded version) — which makes it a payload table too, so its grant is
-- per column and `content` is not among them. The two `batch` tables are the
-- acquisition run ledger V2 described, which is what the screen shows where
-- paddock's showed `import_run` (a `public` table raptor does not own, closed
-- since #94).
--
-- Reversal: scripts/rollback/V35__reader_reads_capture_records.sql, tested with
-- rollback.

grant usage on schema raw to paddock_reader;
grant select on raw.historic_file, raw.capture_file to paddock_reader;
grant select (id, path, division, season, sha256, bytes, etag, last_modified,
		fetched_at, checked_at)
	on raw.football_file to paddock_reader;

grant usage on schema batch to paddock_reader;
grant select on batch.batch_job_instance, batch.batch_job_execution
	to paddock_reader;
