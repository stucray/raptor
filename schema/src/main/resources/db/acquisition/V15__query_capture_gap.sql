-- The gaps themselves, not just how many there were.
--
-- query.capture_session carries gap COUNTS, which is enough to say a session lost
-- something and not enough to say whether it mattered. The question the capture
-- panel exists to answer is sharper than that: was the recorder blind while a
-- market was IN PLAY? A gap at 04:00 on a Tuesday costs nothing; the same gap
-- during the second half is the thing the whole programme is built to avoid.
--
-- Answering it needs the intervals, and the read side cannot reach them: it
-- holds no grant on `raw` at all, deliberately, and that boundary is about which
-- data is rebuildable rather than about which process is running. So the
-- intervals are projected, like everything else the read side needs.

create table query.capture_gap (
	id            bigint primary key,
	session_id    bigint      not null,
	started_at    timestamptz not null,
	ended_at      timestamptz not null,
	cause         text        not null,
	detail        text,
	projection_id bigint      not null references query.projection
);

create index capture_gap_started_idx on query.capture_gap (started_at);

comment on table query.capture_gap is
	'One row per interval the recorder knows it was not receiving. Distinct from '
	'a gap the PARSER infers from message silence, which is on query.market: a '
	'quiet book produces the second without the first, so neither can be derived '
	'from the other and both are kept.';
