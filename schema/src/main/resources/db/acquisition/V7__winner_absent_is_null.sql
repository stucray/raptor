-- "No winner recorded" becomes NULL, as it is everywhere else in this table.
--
-- V5 declared `winner` not null and spelled absence as the empty string,
-- deliberately: the Python emits `""` there, and the parity gate compares the
-- parse against the Python byte for byte. But that is a fact about the wire
-- format, and it leaked into the schema — every other optional column on this
-- table already spells absence as NULL (`country` on 37 markets, `away` on 1),
-- so `winner` alone answered "is this absent?" in a different language.
--
-- It cost nothing yet and would have cost something specific later: the read
-- side reads this column through S8's compatibility views, `public`'s copy of
-- it IS nullable, and a `winner is null` written against either would have been
-- silently, permanently false against the other. Found by comparing the two
-- (issue #78): 15,513 markets differed on this column and no other.
--
-- The wire keeps its `""` — `MarketParse.winner` is what the parity gate
-- compares and must not move. The mapping happens once, at the boundary where
-- a parse becomes a row, in HistoricProjectionWriter.

alter table query.historic_market alter column winner drop not null;

update query.historic_market set winner = null where winner = '';

comment on column query.historic_market.winner is
	'The settled winner: pipe-separated when a market settles with more than one '
	'WINNER runner, exactly as the Python emitted it. NULL means no winner was '
	'recorded — the market never settled, or settled with none. The parse spells '
	'that absence as the empty string, matching the Python; the mapping to NULL '
	'happens in HistoricProjectionWriter.';
