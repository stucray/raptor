-- raptor#42 (PRD #41): each scoped market's catalogue entry, verbatim.
--
-- WHY. The stream names no runner: a live market's runners arrive as a selection
-- id, a sort priority and a status. Betfair says what each runner IS (its name,
-- and for some sports a great deal more) only in the market's catalogue entry,
-- and stops serving that entry once the market closes. Asked for by id on
-- 2026-10-02, finished markets returned no entry at all. So an entry raptor does
-- not keep while the market is listed is lost for good.
--
-- WHAT A ROW IS. One market's node from a `listMarketCatalogue` response asked for
-- by market id, with every projection that describes the market and its runners,
-- exactly as Betfair sent it. `fetched_at` is when raptor asked. A market gains a
-- row when it is first fetched (#42), and another whenever Betfair's entry for it
-- changes (#43).
--
-- Markets captured before this table existed have no row, and none can be made:
-- Betfair no longer serves their entries.
--
-- Append-only, like the rest of raw: never updated, never deleted. Keyed for
-- integrity only. The key's index also serves capture's own lookup of a market's
-- latest entry, and nothing else is indexed. Read grants come from raw's default
-- privileges, the same as every other table here.

create table raw.market_catalogue (
	id          bigint generated always as identity primary key,
	market_id   text        not null,
	fetched_at  timestamptz not null,
	entry       jsonb       not null,

	constraint market_catalogue_market_fetched_key unique (market_id, fetched_at)
);

comment on table raw.market_catalogue is
	'Each scoped market''s Betfair catalogue entry, verbatim, fetched by market id '
	'with its runners (#42). A new row only when the entry changes. Markets captured '
	'before this table existed have none: Betfair stops serving an entry once the '
	'market closes.';
comment on column raw.market_catalogue.id is
	'A surrogate key with no meaning.';
comment on column raw.market_catalogue.market_id is
	'The market''s id as Betfair sent it in the entry (marketId), the same id the '
	'stream rows carry.';
comment on column raw.market_catalogue.fetched_at is
	'When raptor asked Betfair for the entry.';
comment on column raw.market_catalogue.entry is
	'The market''s node from listMarketCatalogue, as received: description, event, '
	'competition, start time and runners with names, selection ids, sort priorities '
	'and metadata.';
