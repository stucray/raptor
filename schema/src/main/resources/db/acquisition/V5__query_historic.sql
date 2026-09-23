-- The first projection: the historic BASIC corpus, derived from `raw` and
-- rebuildable from it at any time.
--
-- Nothing here is a source of truth. Every row is a function of
-- `raw.stream_message` and the parser version that read it, which is why the
-- projection can be dropped and rebuilt whenever a parse turns out to have been
-- wrong — the case this whole architecture exists to make cheap.

create table query.projection (
	id               bigint generated always as identity primary key,
	job_name         text        not null,
	-- The job's identifying parameter: a corpus month here, a market id for the
	-- live projection. Text because the jobs disagree about what a unit is.
	partition_key    text        not null,
	job_execution_id bigint      not null,
	started_at       timestamptz not null default now(),
	completed_at     timestamptz,
	markets          integer,
	ticks            bigint,
	transitions      bigint
);

comment on table query.projection is
	'Provenance for projected rows. Replaces paddock''s import_run, which '
	'recorded a file arriving from the normaliser; this records a derivation '
	'from raw, and the BATCH_JOB_EXECUTION it ran under.';

create index projection_partition_idx on query.projection (job_name, partition_key);

-- paddock keeps `historic_market` and `historic_market_meta` as two tables only
-- because two separate import runs produced them. They are 1:1 on market_id and
-- one derivation produces both, so they are one table here; S8's compatibility
-- views split them back for paddock's existing controllers.
create table query.historic_market (
	market_id     text primary key,
	-- The vendor file this market was parsed from, relative to the custody root.
	-- Keeps the projection traceable to a file on disk without a join through
	-- raw, which paddock cannot read.
	file_path     text        not null,
	event_id      text,
	market_type   text,
	country       text,
	market_time   timestamptz,
	open_date     timestamptz,
	name          text,
	home          text,
	away          text,
	n_runners     integer     not null,
	inplay_at     timestamptz,
	close_at      timestamptz,
	-- Pipe-separated when a market settles with more than one WINNER, exactly as
	-- the Python emitted it. Empty string means "parsed, no winner recorded" and
	-- is distinct from null.
	winner        text        not null,
	n_ticks       integer     not null,
	projection_id bigint      not null references query.projection
);

create index historic_market_time_idx on query.historic_market (market_time);
create index historic_market_name_idx on query.historic_market (lower(name));

create table query.historic_price_tick (
	market_id     text        not null,
	-- Nullable because the wire is: a message can carry market changes with no
	-- publish time. Inventing one would be worse than recording the absence.
	pt            timestamptz,
	runner_id     bigint      not null,
	-- Also nullable, and not the same thing as absent: the wire carries an
	-- explicit null ltp, which is a real tick saying the last traded price is
	-- gone.
	ltp           numeric,
	projection_id bigint      not null references query.projection
);

create index historic_price_tick_market_pt_idx
	on query.historic_price_tick (market_id, pt);

create table query.historic_transition (
	market_id     text        not null,
	pt            timestamptz,
	status        text,
	in_play       boolean,
	projection_id bigint      not null references query.projection
);

create index historic_transition_market_pt_idx
	on query.historic_transition (market_id, pt);
