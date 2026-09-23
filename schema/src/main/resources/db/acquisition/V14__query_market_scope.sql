-- What the recorder was trying to capture, on the read side.
--
-- The other half of replacing a reconstructed ledger with a recorded one. The
-- sessions say what the recorder DID; scope says what it was FOR, and only the
-- second can answer the question the health screen exists to ask.
--
-- WHY THE VERDICT HAD TO MOVE. `public.capture_run`'s freshness rule was "has a
-- run started since the last due fire hour", judged against
-- `capture.fire-hour-utc`. That was a sound rule while launchd fired a 12-hour
-- window every night. After S8 unloads it nothing fires at any hour, so the
-- oracle becomes a fiction that still returns a confident answer — the worst
-- kind of monitoring.
--
-- Scope answers it directly and keeps answering it: a fixture that entered
-- scope, was subscribed, reached DONE and produced NO MESSAGES is a lost
-- fixture, whatever the schedule was or was not doing. That is a stronger claim
-- than the old rule could make, which could only ever say a run had not started.

create table query.market_scope (
	market_id        text primary key,
	event_id         text,
	event_name       text,
	competition_id   text,
	competition_name text,
	market_type      text        not null,
	country_code     text,
	kickoff          timestamptz,
	-- True for the leagues the programme exists to record, false for the control
	-- set. A control market that produced nothing is not a lost fixture; the
	-- verdict has to be able to tell them apart.
	requested        boolean     not null,

	state            text        not null,
	exit_reason      text,
	first_seen_at    timestamptz not null,
	state_changed_at timestamptz not null,
	in_play_since    timestamptz,

	-- How many messages this market actually produced. Not on `raw.market_scope`,
	-- because the recorder has no reason to count as it goes and a counter it
	-- maintained would be another self-report. Counted here, from the messages,
	-- which is what makes "subscribed and silent" a fact rather than an
	-- inference.
	messages         bigint      not null,

	projection_id    bigint      not null references query.projection
);

create index market_scope_kickoff_idx on query.market_scope (kickoff desc);
create index market_scope_state_idx on query.market_scope (state);

comment on table query.market_scope is
	'One row per market the recorder has had in scope, including the ones it has '
	'finished with, with the count of what each actually produced. A DONE and '
	'requested market with zero messages is a lost fixture — the signal that '
	'replaces "no run has started since the last due fire hour", which stops '
	'meaning anything once nothing fires.';
