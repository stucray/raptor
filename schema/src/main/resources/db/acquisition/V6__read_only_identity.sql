-- The read side's login identity, and the grants that bound it.
--
-- V1 created `paddock_reader` as a NOLOGIN role back when the read side was a
-- separate process connecting as its own user and this role only carried grants.
-- One process needs it to be something the application can actually connect as,
-- so it gains LOGIN and a password here.
--
-- The password arrives as a Flyway placeholder from the same configuration the
-- read datasource uses, so there is one definition of the credential and no
-- secret in the repository. A fresh database is correct after `flyway migrate`
-- alone — no provisioning step that a new clone could skip and only discover
-- when the read side cannot connect.

do $$
begin
	if not exists (select 1 from pg_catalog.pg_roles where rolname = 'paddock_reader') then
		create role paddock_reader login password '${readerPassword}';
	else
		alter role paddock_reader login password '${readerPassword}';
	end if;
end
$$;

-- `query` is the contract: readable, and only readable. A projection is derived
-- from raw and rebuilt from it; the read side has no business writing one, and
-- an attempt to is a bug this refuses rather than absorbs.
grant usage on schema query to paddock_reader;
grant select on all tables in schema query to paddock_reader;

-- Applies to tables a later migration creates, so adding a projection does not
-- mean remembering to come back here. Scoped to the owner, which is who runs
-- every migration.
alter default privileges in schema query grant select on tables to paddock_reader;

-- `public` is the read side's own. It computes those tables — execution
-- calibration, the simulation feature tables, the lab's saved runs — and the
-- spool importers write them on every run, so the "read-only" identity is
-- read-only with respect to the acquisition schemas, not to the database.
grant usage on schema public to paddock_reader;
grant select, insert, update, delete, truncate on all tables in schema public to paddock_reader;
grant usage, select on all sequences in schema public to paddock_reader;
alter default privileges in schema public
	grant select, insert, update, delete, truncate on tables to paddock_reader;
alter default privileges in schema public grant usage, select on sequences to paddock_reader;

-- Deliberately absent, and asserted by a test rather than trusted: any grant on
-- `raw` or `batch`. Not select, not usage. If the read side needs something from
-- the system of record, that is a missing projection and not a missing grant —
-- and the run ledger is the write path's own bookkeeping.
--
-- Stated as a revoke as well as an omission, because PUBLIC carries a default
-- USAGE grant on schemas in some configurations and an inherited privilege is
-- exactly the kind of thing nobody notices.
revoke all on schema raw from public;
revoke all on schema batch from public;
revoke all on all tables in schema raw from public;
revoke all on all tables in schema batch from public;
