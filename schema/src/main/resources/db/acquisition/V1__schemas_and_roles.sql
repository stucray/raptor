-- The acquisition schema split.
--
--   raw    system of record. Append-only, never updated. Written only by the
--          recorder and the ingest jobs.
--   query  fully rebuildable projection of raw. The contract with the read side.
--   batch  Spring Batch's own tables (V2), kept out of both.
--
-- These three are migrated on their own version line, against their own history
-- table; the read side's `public` and its V1-V16 history are untouched. The
-- boundary is grants, not convention: the read side can SELECT on query and
-- cannot reach raw at all.

create schema if not exists raw;
create schema if not exists query;
create schema if not exists batch;

comment on schema raw is
	'System of record. Append-only. Unreplayable capture data lives here.';
comment on schema query is
	'Rebuildable projection of raw. The read contract with paddock.';
comment on schema batch is
	'Spring Batch job repository. Doubles as the acquisition run ledger.';

-- The read-side role. Created here rather than in compose so that a fresh
-- database is correct after `flyway migrate` alone. Idempotent: a role may
-- already exist from a previous deploy, and CREATE ROLE has no IF NOT EXISTS.
do $$
begin
	if not exists (select 1 from pg_catalog.pg_roles where rolname = 'paddock_reader') then
		create role paddock_reader nologin;
	end if;
end
$$;

grant usage on schema query to paddock_reader;
grant select on all tables in schema query to paddock_reader;

-- Applies to tables this migration user creates later, so new projected tables
-- are readable without revisiting the grant.
alter default privileges in schema query
	grant select on tables to paddock_reader;

-- Deliberately absent: any grant on `raw` to paddock_reader. The read side has
-- no business reading the system of record directly — if it needs something
-- from raw, that is a missing projection, not a missing grant.
