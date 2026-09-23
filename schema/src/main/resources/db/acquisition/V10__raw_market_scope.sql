-- What the recorder is trying to capture, and why it stopped.
--
-- This table is what replaces the capture WINDOW. Today a night is a 12-hour
-- launchd run: `capture.run-hours`, `capture.fire-hour-utc` and
-- `capture.ledger-lag-hours` between them decide what gets recorded, which
-- means a 20:00Z EFL Cup tie and a 12:30Z Saturday kickoff cannot both be
-- covered without subscribing to twelve hours of nothing in between — and a
-- short-notice reschedule is simply missed.
--
-- Scope is per market instead. A fixture enters when its kickoff comes inside
-- the horizon, is subscribed while it fits under Betfair's cap, and leaves when
-- it is over. The recorder then has no schedule at all: it records what is in
-- scope, and the scope moves on its own.
--
-- The states, and what ends each one:
--
--   PENDING     kickoff is inside the horizon; not yet in a subscription
--   SUBSCRIBED  in the current marketSubscription
--   LIVE        the catalogue reported it in-play
--   DONE        the catalogue reported it CLOSED or SETTLED, or a guard fired
--
-- DONE is deliberately reachable by a guard as well as by an answer. A market
-- that is abandoned, voided or simply never closed cleanly must still leave
-- scope, or it holds a subscription slot against a fixture that will never
-- produce another message — and the cap is 200, so a handful of those is a
-- Saturday's worth of capacity gone.

create table raw.market_scope (
	market_id        text        primary key,
	event_id         text,
	event_name       text,
	competition_id   text,
	competition_name text,
	market_type      text        not null,
	country_code     text,
	kickoff          timestamptz,
	-- True for the leagues the programme exists to record, false for the
	-- control set. The planner reads this: a control market may never crowd out
	-- a requested one, which is a rule that has to survive a restart, so it
	-- lives in the table rather than in the poll that discovered it.
	requested        boolean     not null default false,

	state            text        not null
		check (state in ('PENDING', 'SUBSCRIBED', 'LIVE', 'DONE')),
	-- Why it left scope, when it has. Null while it has not.
	exit_reason      text
		check (exit_reason is null or exit_reason in
			('CLOSED', 'SETTLED', 'IN_PLAY_ELAPSED', 'KICKOFF_ELAPSED', 'OUT_OF_HORIZON')),

	first_seen_at    timestamptz not null default now(),
	state_changed_at timestamptz not null default now(),
	-- When the catalogue first reported it in-play. Null until it does, and the
	-- clock the IN_PLAY_ELAPSED guard measures from.
	in_play_since    timestamptz,

	constraint market_scope_exit_reason_only_when_done
		check ((state = 'DONE') = (exit_reason is not null))
);

comment on table raw.market_scope is
	'One row per market the recorder has had in scope, including the ones it '
	'has finished with. Not a queue: rows are kept after DONE, because "what '
	'was this recorder trying to capture on the night of the 14th, and why did '
	'it stop" is exactly the question a gap in the corpus raises.';

comment on column raw.market_scope.state is
	'PENDING -> SUBSCRIBED -> LIVE -> DONE, though LIVE is skippable: a market '
	'can close without the poll ever catching it in-play, and a fixture that '
	'never kicks off goes PENDING -> DONE on a guard.';

-- The planner's read: everything still in scope, earliest kickoff first.
create index market_scope_open_by_kickoff on raw.market_scope (kickoff)
	where state <> 'DONE';
