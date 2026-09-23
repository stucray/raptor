-- The fixture a historic market belonged to.
--
-- `query.historic_market.name` is the MARKET name — "Over/Under 2.5 Goals" —
-- and `home` / `away` are its two RUNNERS, which for a totals market are
-- "Under 2.5 Goals" and "Over 2.5 Goals". None of those say which match it was.
--
-- paddock's historic search has always searched on the fixture, and the read
-- side's `historic_market.event_name` carried it. The projection could not:
-- it never read the field, so S8's compatibility view had nothing to serve.
--
-- Deriving it from the event's MATCH_ODDS sibling was tried and rejected. It
-- agrees on 220,470 of 221,821 rows — 99.4%, which is exactly the kind of
-- nearly-right that gets believed. `eventName` is on the wire's own
-- marketDefinition in every BASIC file; reading it is exact and costs nothing.
--
-- It is deliberately NOT part of the canonical parse output the parity gate
-- digests. That form is the contract with the Python's emitter and must stay
-- byte-identical to a frozen manifest; this column is read for the projection's
-- benefit alone.

alter table query.historic_market add column event_name text;

create index historic_market_event_name_idx
	on query.historic_market (lower(event_name));

comment on column query.historic_market.event_name is
	'The fixture, as the wire spells it ("Burnley v Southampton"), from the '
	'marketDefinition''s own eventName. Distinct from `name`, which is the '
	'market''s name, and from `home`/`away`, which are its runners.';
