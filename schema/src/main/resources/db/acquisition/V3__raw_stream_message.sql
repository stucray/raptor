-- The system of record.
--
-- Message-level, deliberately NOT shredded into ticks and transitions. The
-- corpus's entire value is that it can be re-parsed when a parse turns out to
-- have been wrong — which has happened repeatedly — and shredding at ingest
-- bakes today's parse into the only copy we have. Shredding belongs in `query`,
-- which is rebuildable.

create table raw.historic_file (
	id          bigint generated always as identity primary key,
	path        text        not null unique,
	sha256      bytea       not null,
	bytes       bigint      not null,
	messages    integer     not null,
	loaded_at   timestamptz not null default now()
);

comment on table raw.historic_file is
	'One row per Betfair BASIC vendor file. The sha256 is what lets a load be '
	'proven faithful against the files on disk — jsonb does not preserve bytes, '
	'so nothing inside the database can serve as that evidence.';

create table raw.capture_session (
	id            bigint generated always as identity primary key,
	started_at    timestamptz not null,
	ended_at      timestamptz,
	origin        text        not null check (origin in ('RESIDENT', 'MANUAL')),
	exit_status   text,
	exit_detail   text,
	config_json   jsonb       not null,
	build_version text        not null
);

comment on table raw.capture_session is
	'One row per recorder session. Replaces paddock''s capture_run and the '
	'539-line launchd-log parser behind it: a resident recorder knows what it '
	'did and writes it down, rather than having it reconstructed afterwards.';

create table raw.stream_message (
	-- Exactly one provenance, enforced below. The two are genuinely different
	-- kinds of thing and collapsing them would lose that: a vendor file is
	-- immutable and re-downloadable from Betfair, while a capture is ours and
	-- unreplayable. Retention and trust differ accordingly.
	session_id  bigint      references raw.capture_session,
	file_id     bigint      references raw.historic_file,

	market_id   text        not null,
	pt          timestamptz not null,
	-- Our clock at receipt. Null for vendor files, which carry only Betfair's
	-- pt; for live capture the two disagree and both matter.
	received_at timestamptz,
	-- Monotonic within the session or file, giving a total order under equal pt.
	seq         bigint      not null,
	payload     jsonb       not null,

	constraint stream_message_one_provenance
		check (num_nonnulls(session_id, file_id) = 1)
) partition by range (pt);

comment on table raw.stream_message is
	'Betfair MCM stream messages, verbatim. NEVER make this unlogged: it is the '
	'obvious throughput knob and it is catastrophically wrong, because unlogged '
	'tables are TRUNCATED on crash recovery.';

-- Monthly partitions across the historic span (2019-10 is the corpus''s first
-- month) plus a year of headroom. Beyond that, PartitionMaintenance creates the
-- next month a week ahead — and lazily on a check-constraint violation, because
-- a missing partition at 20:00 on a Saturday is not an acceptable failure.
do $$
declare
	start_month date := date '2019-01-01';
	end_month   date := date_trunc('month', now())::date + interval '12 months';
	m           date;
begin
	m := start_month;
	while m < end_month loop
		execute format(
			'create table raw.stream_message_%s partition of raw.stream_message '
			'for values from (%L) to (%L) with (fillfactor = 100, '
			'autovacuum_vacuum_insert_scale_factor = 0, '
			'autovacuum_vacuum_insert_threshold = 1000000)',
			to_char(m, 'YYYY_MM'), m, m + interval '1 month');
		m := (m + interval '1 month')::date;
	end loop;
end
$$;

-- Deliberately no index on stream_message yet. The projection's access path is
-- (market_id, pt), but building it before the bulk load costs 5-10x on the way
-- in; V4 adds it after S2 has landed the corpus.
