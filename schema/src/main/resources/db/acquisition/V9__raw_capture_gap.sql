-- What the recorder knows it lost.
--
-- Today a gap is INFERRED: parse_captures.py compares message timestamps
-- against GAP_S=60, then gap_simultaneity.py cross-checks the candidates
-- against other markets to guess whether a quiet book, a network drop or a
-- sleeping laptop produced them — which is why capture_run carries
-- gap_alerts / gap_network / gap_quiet_book / gap_no_witness and why those
-- four routinely disagree with each other.
--
-- A resident recorder does not have to guess. It knows when it stopped
-- receiving, why it tore the stream down, and when it came back. Writing that
-- down turns the simultaneity apparatus into a cross-check on a known answer
-- instead of the answer itself.

create table raw.capture_gap (
	id          bigint generated always as identity primary key,
	session_id  bigint      not null references raw.capture_session,
	started_at  timestamptz not null,
	ended_at    timestamptz not null,
	cause       text        not null check (cause in ('SLEEP', 'SILENCE', 'DISCONNECT')),
	detail      text,
	recorded_at timestamptz not null default now(),

	-- A gap that ends before it starts is a clock bug, and the whole point of
	-- this table is that its spans can be trusted arithmetic.
	constraint capture_gap_span check (ended_at >= started_at)
);

comment on table raw.capture_gap is
	'One row per interval the recorder knows it was not receiving. A gap is '
	'always attached to the session that was running when it happened; time '
	'lost while no recorder was running at all is a different question, and it '
	'is answered by the space between one capture_session''s ended_at and the '
	'next one''s started_at.';

comment on column raw.capture_gap.cause is
	'SLEEP: wall clock advanced materially more than the monotonic clock, so '
	'the machine suspended. SILENCE: the socket claimed to be alive but no '
	'frame arrived inside the silence timeout — what macOS sleep leaves behind, '
	'and what waiting for an IOException would take minutes to notice. '
	'DISCONNECT: the source ended or failed and the supervisor reconnected.';

create index capture_gap_session_started on raw.capture_gap (session_id, started_at);
