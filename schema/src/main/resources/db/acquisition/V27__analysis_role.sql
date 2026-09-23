-- #247 (PRD #245): the role overround-analysis connects as, and the schema it owns.
--
-- paddock creates a login it never uses because paddock owns the database, and a
-- fresh database must be correct after `flyway migrate` alone (V6's reasoning).
-- The password arrives as a placeholder from `paddock.analysis-role.password`, so
-- none of it is in the repository.
--
-- THE CONTRACT, AND ONLY IT: `query` read-only, plus `team_alias` read-only,
-- which lives in `public` and is granted by public migration V34 on the history
-- that creates it. No grant on `raw` or `batch` (V6 already revoked PUBLIC's), and
-- none on the rest of `public`, whose presentation views paddock must stay free to
-- change without silently changing an analysis result.

do $$
begin
	if not exists (select 1 from pg_catalog.pg_roles where rolname = 'overround_analysis') then
		create role overround_analysis login password '${analysisPassword}';
	else
		alter role overround_analysis login password '${analysisPassword}';
	end if;
end
$$;

-- CONNECTION LIMIT: 20. Measured, not guessed (2026-09-11, the live server).
--
-- What the limit exists to prevent is an ad hoc lab query during a match starving
-- the recorder's COPY into spill. So that was measured first: a peak-second batch
-- (1,278 rows, the busiest second of the 2026-09-05 card; its busiest minute ran
-- 273 msg/s) was COPYed 30 times at the 200 ms flush cadence while N concurrent
-- full scans of `query.price_tick` (11 GB, 34M rows) ran beside it:
--
--     N scans   p50 ms   p95 ms   max ms
--        0        4.5      5.8      6.8
--        4        4.4      6.7      7.8
--        8        7.5     13.1     15.2
--       16        6.1     11.9     17.2
--       24        6.9     15.8     18.4
--
-- Even at 24 the worst batch is 11x inside the flush interval and ~1,600x inside
-- the 30 s socket timeout that turns a stalled COPY into a spill. Concurrency does
-- not set the bound; the server's connection budget does. max_connections is 100
-- with 3 reserved for superusers, and paddock's two pools hold up to 10 each. A
-- role able to take the other 77 could stop paddock's pools replacing a retired
-- connection, and that failure DOES reach the write path, through the connection
-- timeout rather than the socket. 20 leaves 57 for paddock, psql, the restore
-- drill and a restart overlap. It also sits inside the range measured above, and
-- overround-analysis sizes its pool under it.
alter role overround_analysis connection limit 20;

grant usage on schema query to overround_analysis;
grant select on all tables in schema query to overround_analysis;

-- A projection a later migration adds is readable without coming back here.
-- Default privileges bind to the role that creates the table, which is the owner
-- that runs every acquisition migration.
alter default privileges in schema query grant select on tables to overround_analysis;

-- Its own schema, empty until #250 moves the analysis and simulation tables in.
-- Owned outright, so overround-analysis's own Flyway history can build in it
-- without paddock granting anything further.
create schema if not exists analysis authorization overround_analysis;
comment on schema analysis is
	'overround-analysis''s own. Findings derived from query; paddock never reads or writes it.';
