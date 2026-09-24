-- Reversal of acquisition V35 (paddock#321): the read identity loses its
-- select on capture's file and run records, and with it every grant it held in
-- `raw` and `batch` — which before V35 was none at all (V1, V6).
--
-- WHEN THIS IS WANTED. Rarely. An image from before V35 does not need it: it
-- never names these tables as the reader, and Flyway ignores a migration applied
-- by a newer image (`ignoreMigrationPatterns` defaults to `*:future`). Run it
-- only to take the grant itself back.
--
-- It restores EXACTLY: each revoke names the table V35 named, never `all tables
-- in schema`. Probe it first with `commit` changed to `rollback`, and check that
-- the final select returns 0 / false / false, which is the pre-V35 state:
--
--   docker exec -i <postgres container> psql -U paddock -d paddock \
--     -v ON_ERROR_STOP=1 < scripts/rollback/V35__reader_reads_capture_records.sql

begin;

revoke select on raw.historic_file, raw.capture_file from paddock_reader;
revoke select (id, path, division, season, sha256, bytes, etag, last_modified,
		fetched_at, checked_at)
	on raw.football_file from paddock_reader;
revoke usage on schema raw from paddock_reader;

revoke select on batch.batch_job_instance, batch.batch_job_execution
	from paddock_reader;
revoke usage on schema batch from paddock_reader;

delete from query.flyway_schema_history_acquisition where version = '35';

select count(*) filter (where has_any_column_privilege('paddock_reader', c.oid, 'select'))
		as reader_tables,
	has_schema_privilege('paddock_reader', 'raw', 'usage') as raw_usage,
	has_schema_privilege('paddock_reader', 'batch', 'usage') as batch_usage
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname in ('raw', 'batch') and c.relkind in ('r', 'p', 'v');

commit;
