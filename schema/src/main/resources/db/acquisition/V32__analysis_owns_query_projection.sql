-- #316 (PRD #309): overround-analysis becomes the writer of `query`'s
-- projection tables, and their OWNER.
--
-- WHY OWNERSHIP AND NOT A GRANT. Writing rows needs only DML, but #316 also
-- moves every future `query` migration into overround-analysis's own Flyway
-- history, and a migration that alters a table must run as its owner. A grant
-- would switch the writer and leave the schema's evolution with an application
-- that no longer produces the data. `alter ... owner` is catalogue-only (one
-- `pg_class` row per relation, no page read or written), the identity sequence
-- follows its table, and the existing grants follow with the grantor rewritten —
-- so `paddock_reader`'s screens keep reading with nothing restated.
--
-- WHAT DOES NOT MOVE: the capture ledger. `capture_session`, `market_scope` and
-- `capture_gap` are capture operations, refreshed by paddock every five minutes,
-- and #320 moves them into raptor's own schema. They stay paddock's here, and
-- this role gains no write on them. `flyway_schema_history_acquisition` stays
-- paddock's too: it is the history of the migrations that are frozen from here.
--
-- `query.projection` IS SHARED. It is the provenance table every row in `query`
-- points at, the ledger's rows included. It moves with the projection tables,
-- because they are almost all of its rows, and paddock keeps insert and update
-- on it so the ledger can still record its own runs.
--
-- THE GRANTS TO current_user ARE NOT DECORATION. The migration identity is a
-- superuser on the deployed database and would bypass them, but nothing here
-- should depend on that: it reads the projection tables (the projection backlog
-- measures `raw` against `query.market` and `query.football_match`) and writes
-- `query.projection` for the ledger. Stated rather than inherited.
--
-- REVERSAL: scripts/rollback/V32__analysis_owns_query_projection.sql, probed
-- with the ACL of every relation counted on both sides (#256).

grant create on schema query to overround_analysis;

alter table query.projection owner to overround_analysis;
alter table query.historic_market owner to overround_analysis;
alter table query.historic_price_tick owner to overround_analysis;
alter table query.historic_transition owner to overround_analysis;
alter table query.market owner to overround_analysis;
alter table query.price_tick owner to overround_analysis;
alter table query.market_span owner to overround_analysis;
alter table query.market_runner owner to overround_analysis;
alter table query.live_transition owner to overround_analysis;
alter table query.football_match owner to overround_analysis;

grant select, insert, update on query.projection to current_user;
grant select on query.historic_market, query.historic_price_tick,
	query.historic_transition, query.market, query.price_tick, query.market_span,
	query.market_runner, query.live_transition, query.football_match
	to current_user;

-- A table overround-analysis adds to `query` later is readable by paddock's
-- screens on the day it lands, as one paddock adds is today (V1, V6). Named for
-- the role that will create them, which is what `alter default privileges`
-- keys on.
alter default privileges for role overround_analysis in schema query
	grant select on tables to paddock_reader;

comment on schema query is
	'Rebuildable projection of raw. The projection tables are owned and written by '
	'overround-analysis since #316; the capture ledger (capture_session, market_scope, '
	'capture_gap) by paddock until #320.';
