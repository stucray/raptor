-- The live projection's status timeline, stored outright rather than left to be
-- rebuilt from the ticks (#214).
--
-- WHY IT HAS TO EXIST. `query.price_tick` carries the market's status on every
-- runner row, so a timeline was recoverable from it by reading where the status
-- changed — until a message arrives with a `marketDefinition` and no `rc`. The
-- parser emits one observation per runner in the book, and a market with no book
-- yet has none, so such a message produces NO ROWS AT ALL and the status it
-- carried is recoverable from nothing downstream. That is the first message of
-- every capture the recorder opens mid-flight, and it cost ten spans of 5,302
-- (0.19%) when the analysis layer rebuilt the timeline from ticks: every one a
-- sub-half-second suspension before the market had a book.
--
-- The parse always knew. It reads the message stream, not the tick rows, and
-- `query.market_span` has held all 5,302 spans since #208 — the loss was never
-- in the projection, only in what the projection wrote down.
--
-- Same shape and same standing as V5's `query.historic_transition`, which is why
-- the analysis layer's two adapters now differ only in table name: the historic
-- corpus has stored its transitions outright since the first projection, and the
-- live one storing them removes the asymmetry rather than adding a special case.
--
-- NO in_play COLUMN, unlike historic_transition. The parse carries inPlay, but
-- this list is appended to only when the STATUS changes, so an in_play here
-- would be that flag sampled at somebody else's instants — right whenever it was
-- read and silent about every change in between. `query.market.inplay_at` is the
-- fact, and `query.price_tick.in_play` is the series.

create table query.live_transition (
	market_id     text        not null,
	pt            timestamptz not null,
	-- Not null, and deliberately narrower than the parse's nullable status: a
	-- transition is only ever appended when a market definition carried one, so
	-- a null here would mean the parser changed under us and this should fail
	-- loudly rather than store a hole.
	status        text        not null,
	projection_id bigint      not null references query.projection
);

create index live_transition_market_pt_idx on query.live_transition (market_id, pt);

comment on table query.live_transition is
	'One row per market-status CHANGE in a live capture, as the market '
	'definitions carried it. query.market_span is derived from this; the rows '
	'are kept because a definition-only message writes no price_tick at all, so '
	'the ticks cannot express a status change that precedes the first book.';
