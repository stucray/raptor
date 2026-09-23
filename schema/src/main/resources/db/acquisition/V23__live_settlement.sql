-- The settlement outcome for live capture (#196).
--
-- Until now the live projection dropped it entirely: no live-captured market
-- could have a P&L computed, and every backtest was therefore confined to the
-- historic corpus. The data was never missing — 1,190 of the first 1,199
-- captured markets reached a CLOSED message carrying the full result — it was
-- read off the wire, held in `raw`, and then discarded at the decode step.
--
-- BY RUNNER ID, NOT BY NAME, and that is forced rather than chosen. The
-- historic side records `query.historic_market.winner` as a pipe-joined string
-- of runner NAMES, because the BASIC files carry them. The live wire does not:
-- `marketDefinition.runners` is `id`, `status` and `sortPriority` and nothing
-- else. Recording a name here would mean inventing one from a lookup, and a
-- fabricated identifier in the one column a simulation settles against is the
-- worst possible place for a guess.
--
-- It also normalises better. `market_runner` already keys on
-- (market_id, runner_id), so the outcome belongs on the row that already names
-- the runner rather than as a delimited string on the market.

alter table query.market_runner
	add column status text;

comment on column query.market_runner.status is
	'The runner''s LAST reported status: WINNER, LOSER, REMOVED or ACTIVE. '
	'NULL means the capture never saw a settlement — a market abandoned, still '
	'in flight, or dropped from scope before it closed. Absence has one '
	'spelling, and it is NULL.';

-- When the market settled, as distinct from when it closed. A market can reach
-- CLOSED with no settlement at all (void, abandoned), so the two are different
-- questions and a null here is the honest answer to the second.
alter table query.market
	add column settled_at timestamptz;

comment on column query.market.settled_at is
	'marketDefinition.settledTime, or NULL where the capture never saw the '
	'market settle. Not the same as the market reaching CLOSED.';
