-- #320 (PRD #309): the capture ledger leaves `query` for a schema capture owns.
--
-- The ledger (capture_session, market_scope, capture_gap) is capture's record of
-- itself: what the recorder was asked to watch, what it did, and where it went
-- quiet. It is built from `raw` with no parser. It sat in `query` because that
-- was where derived tables went, but `query` is overround-analysis's since #316,
-- and the ledger is not. raptor inherits this schema.
--
-- WHAT MOVES, AND HOW. `alter table ... set schema` is a catalogue update: no
-- page is read or written, and indexes, constraints and the ACL travel with each
-- table, so the existing grants (paddock_reader, overround_analysis: select)
-- arrive intact. Table names are unchanged, so every reader changes only the
-- schema it names.
--
-- WHAT DOES NOT MOVE: the run bookkeeping. Each ledger row carried a
-- `projection_id` referencing `query.projection`, which overround-analysis owns
-- (V32), so every ledger refresh was writing a row into another application's
-- table and joining back to it to decide what a spill replay had made stale.
-- `ledger.ledger_run` takes that over. It is seeded with every run the ledger
-- job ever recorded (5,066 on 2026-09-23), ids preserved, so each foreign key
-- re-points to the same run it referenced before. Those rows are left in
-- `query.projection`: it is not this history's table to delete from, and a
-- history row nothing references harms nothing.
--
-- Reversal: scripts/rollback/V34__ledger_schema.sql, tested with rollback.

-- The foreign keys below lock `query.projection` too. It is written by
-- overround-analysis's close-out, so wait a bounded time rather than queue
-- behind it; a failed migration retries at the next start.
set local lock_timeout = '5s';

create schema ledger;

comment on schema ledger is
	'The capture ledger: what capture was asked to watch, what it did, and where '
	'it went quiet. Built from raw without a parser; owned by capture (raptor), '
	'readable by the read identity and overround-analysis. Moved out of query by '
	'#320.';

create table ledger.ledger_run (
	id               bigint generated always as identity primary key,
	job_name         text        not null,
	partition_key    text        not null,
	job_execution_id bigint      not null,
	started_at       timestamptz not null default now(),
	completed_at     timestamptz
);

comment on table ledger.ledger_run is
	'One row per ledger refresh. The ledger''s own run bookkeeping, which lived in '
	'query.projection until #320; rows from before the move keep their ids.';

insert into ledger.ledger_run
	(id, job_name, partition_key, job_execution_id, started_at, completed_at)
overriding system value
select id, job_name, partition_key, job_execution_id, started_at, completed_at
from query.projection
where job_name = 'projectCaptureLedgerJob';

-- Continue after the highest carried id, so a new run can never reuse one.
select setval(pg_get_serial_sequence('ledger.ledger_run', 'id'),
	coalesce((select max(id) from ledger.ledger_run), 0) + 1, false);

alter table query.capture_session drop constraint capture_session_projection_id_fkey;
alter table query.market_scope drop constraint market_scope_projection_id_fkey;
alter table query.capture_gap drop constraint capture_gap_projection_id_fkey;

alter table query.capture_session set schema ledger;
alter table query.market_scope set schema ledger;
alter table query.capture_gap set schema ledger;

alter table ledger.capture_session rename column projection_id to ledger_run_id;
alter table ledger.market_scope rename column projection_id to ledger_run_id;
alter table ledger.capture_gap rename column projection_id to ledger_run_id;

alter table ledger.capture_session add constraint capture_session_ledger_run_id_fkey
	foreign key (ledger_run_id) references ledger.ledger_run (id);
alter table ledger.market_scope add constraint market_scope_ledger_run_id_fkey
	foreign key (ledger_run_id) references ledger.ledger_run (id);
alter table ledger.capture_gap add constraint capture_gap_ledger_run_id_fkey
	foreign key (ledger_run_id) references ledger.ledger_run (id);

-- The moved tables brought their grants. The schema and the new table need
-- theirs: the same two readers, select only, named explicitly rather than
-- "all tables in schema", so a later table is a decision rather than a default.
grant usage on schema ledger to paddock_reader, overround_analysis;
grant select on ledger.ledger_run to paddock_reader, overround_analysis;
