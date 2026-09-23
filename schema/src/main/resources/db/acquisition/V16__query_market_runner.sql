-- Which runner is which, so the read side can say "home" instead of a selection id.
--
-- NOTE (raptor): this comment is not byte-identical to the copy the live
-- database was migrated with. That copy named two real market ids and their
-- runners' selection ids as evidence, and raptor is public and carries no real
-- Betfair identifiers, so the evidence is described below instead of quoted.
-- The SQL is unchanged. Changing a comment changes Flyway's checksum, so the
-- cutover (#323) updates this version's row in
-- query.flyway_schema_history_acquisition; Version16ChecksumTest pins the new
-- value that step must write.
--
-- `query.price_tick` identifies a runner by its Betfair selection id, which is
-- the honest thing for it to store: the wire carries an id and a sort priority,
-- and a role is a reading of the second. But the read side's price-path chart
-- has always labelled its series `home` / `away` / `draw` / `under` / `over`,
-- and S8's compatibility view cannot invent that from an id.
--
-- The mapping is NOT a lookup of well-known ids. Only three of the five roles
-- have global selection ids — The Draw is 58805 everywhere, and Under/Over pairs
-- are global per line — while MATCH_ODDS team runners carry per-market ids that
-- mean nothing outside their own market. What is stable across every market is
-- the SORT PRIORITY, which Betfair assigns in the market definition:
--
--   MATCH_ODDS      1 = home, 2 = away, 3 = draw
--   OVER_UNDER_*    1 = under, 2 = over
--
-- Verified on the wire before this table was written, rather than assumed: on a
-- captured MATCH_ODDS market the runners at priority 1, 2 and 3 (the last being
-- The Draw) were exactly the ones the Python labelled home / away / draw, and on
-- a captured OVER_UNDER_25 market priority 1 and 2 were labelled under / over.
--
-- The parse has carried this all along — `CaptureParse.runnerSortPriority` —
-- and the projection was throwing it away, keeping only its size as `n_runners`.

create table query.market_runner (
	market_id     text    not null,
	runner_id     bigint  not null,
	-- Nullable because the wire is: a runner can appear in a market definition
	-- without one. A role cannot be read from an absent priority, and inventing
	-- an ordinal from row order would be a guess that looks like a fact.
	sort_priority integer,
	projection_id bigint  not null references query.projection,

	primary key (market_id, runner_id)
);

comment on table query.market_runner is
	'One row per runner per live-captured market: its selection id and the sort '
	'priority the market definition gave it. The read side reads a ROLE from the '
	'priority; storing the role itself would bake one market type''s reading of '
	'it into the projection.';
