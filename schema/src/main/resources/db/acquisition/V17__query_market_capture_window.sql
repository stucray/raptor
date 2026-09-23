-- When a market's capture actually started and stopped.
--
-- `query.market` records what the capture CONTAINED — messages, ticks,
-- suspensions — and not when it happened. The read side's capture-nights screen
-- needs both: it groups markets by the night they were captured on and shows
-- each one's window beside its counts.
--
-- The Python's `captured_market` carried these as `captured_from` /
-- `captured_to`, and its `night_date` was exactly the UTC date of the first —
-- verified on all 601 rows before this column was added, rather than assumed.
-- S8's compatibility view derives the night from `first_tick_at` for that
-- reason, instead of storing a third column that could disagree with the two it
-- is a function of.
--
-- Distinct from `market_time`, which is the KICKOFF. A resident recorder
-- subscribes when a fixture enters the four-hour horizon, so its window opens
-- long before kickoff and the two must not be confused; the Python's started at
-- its fire hour, so the same column means "when this recorder was listening"
-- and not "when the match was".

alter table query.market add column first_tick_at timestamptz;
alter table query.market add column last_tick_at  timestamptz;

comment on column query.market.first_tick_at is
	'Publish time of the market''s earliest observation: when this capture began '
	'receiving for it, which is not when the market opened and not kickoff.';
