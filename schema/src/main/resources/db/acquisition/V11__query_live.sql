-- The second projection: live capture, derived from `raw` and rebuildable from
-- it at any time.
--
-- Same standing as V5's historic tables and the same provenance shape — every
-- row carries a projection_id leading back to a BATCH_JOB_EXECUTION and from
-- there to the raw messages it read. Nothing here is a source of truth.
--
-- WHY SEPARATE TABLES FROM query.historic_*, rather than one set with a source
-- column. The two projections answer different questions from different feeds:
-- BASIC carries last traded price only, at one-minute conflation, while a live
-- capture carries the whole book unconflated. A shared table would be mostly
-- null on one side and would invite a query that silently mixes a conflated
-- price series with an unconflated one — which is the comparison the whole
-- prospective-capture programme exists to avoid making by accident.

create table query.market (
	market_id        text primary key,
	event_id         text,
	-- From the capture's `_meta` header when it had one. A market projected from
	-- raw has no header — the recorder does not write one — so these are null
	-- until a market definition carries them, and their absence says nothing
	-- about the capture's completeness.
	event_name       text,
	competition_name text,
	market_type      text,
	market_time      timestamptz,
	market_base_rate double precision,
	n_runners        integer     not null,
	n_messages       integer     not null,
	n_ticks          integer     not null,

	-- The suspension clock, as the parse found it. inplay_at is the first
	-- message with inPlay true; suspended_at/reopened_at are the FIRST
	-- suspension and its reopen, kept for continuity with the Python's columns.
	-- The full set of suspensions is market_span.
	inplay_at        timestamptz,
	suspended_at     timestamptz,
	reopened_at      timestamptz,
	-- MILLISECONDS, not the seconds the Python reports. Seconds there are a
	-- presentation choice made by a rounding rule, and a column that stores the
	-- rounded value cannot answer a question the rounding threw away.
	suspension_ms    bigint,

	matched          double precision not null,
	tradeable        boolean     not null,

	-- Gaps the PARSER inferred from message silence, not gaps the recorder
	-- wrote down. The two are different facts and both are kept: raw.capture_gap
	-- is what the recorder knows it lost, and these are what the messages look
	-- like afterwards. A quiet pre-match book produces the second without the
	-- first, which is why neither can be derived from the other.
	gap_count        integer     not null,
	max_gap_ms       bigint      not null,
	gap_total_ms     bigint      not null,
	last_dense_at    timestamptz,
	-- True when the messages ran out mid-stream. Always false for a projection
	-- from raw, where every message was framed and committed one at a time;
	-- the column exists so a projection from a file can say otherwise.
	truncated        boolean     not null,

	suspension_count integer     not null,
	incident_count   integer     not null,
	projection_id    bigint      not null references query.projection
);

create index market_time_idx on query.market (market_time);
create index market_inplay_idx on query.market (inplay_at);

create table query.price_tick (
	market_id      text        not null,
	pt             timestamptz not null,
	runner_id      bigint      not null,
	-- Every price is nullable and each null is a fact: an empty side of the book
	-- is not the same as a side nobody has quoted, and the wire distinguishes
	-- them. Absence has one spelling here, and it is NULL (V7).
	best_back      double precision,
	best_back_size double precision,
	best_lay       double precision,
	best_lay_size  double precision,
	spread_ticks   integer,
	-- Sum of the best three prices' sizes on each side: what the fill question
	-- needs and what BASIC could never answer.
	depth_back_3   double precision not null,
	depth_lay_3    double precision not null,
	ltp            double precision,
	traded_volume  double precision,
	status         text,
	in_play        boolean,
	bet_delay      integer,
	projection_id  bigint      not null references query.projection
);

-- One row per runner per message, so a market is tens of thousands of rows and
-- the index has to be the one the read side actually uses: a market's series in
-- time order.
create index price_tick_market_idx on query.price_tick (market_id, pt);

create table query.market_span (
	market_id     text        not null,
	-- The span's ordinal BEFORE flicker merging, so these are not contiguous: a
	-- merged flicker keeps the first span's number. Deliberately the Python's
	-- numbering, because the read side's existing rows use it.
	n             integer     not null,
	suspended_at  timestamptz not null,
	-- Null when the market never reopened — settled, abandoned, or the capture
	-- ended first. A span with no reopen is a real observation, not a defect.
	reopened_at   timestamptz,
	open_gap_ms   bigint,
	-- The n of the incident's first span: a VAR review of a goal carries the
	-- goal's number, so counting events means counting role = 'primary'.
	incident      integer     not null,
	role          text        not null check (role in ('primary', 'continuation')),
	n_flickers    integer     not null,
	projection_id bigint      not null references query.projection,

	primary key (market_id, n)
);

comment on table query.market_span is
	'Suspensions after flicker merging: the signal the prospective capture '
	'programme exists to measure. The exchange announces a material event by '
	'suspending, so the span says WHEN with no detector and no threshold, and '
	'the reprice across it says WHAT.';
