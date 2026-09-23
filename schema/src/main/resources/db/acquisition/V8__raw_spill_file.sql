-- The spill ledger: which spill files have been ingested.
--
-- The spill exists because a ten-minute Postgres restart on a Saturday evening
-- would otherwise lose a match permanently — Betfair's `clk` replay covers
-- minutes, not tens of them. It is exceptional by construction: no file exists
-- in normal operation, nothing reads one except the replay job, and it is never
-- the primary path.
--
-- This table is what keeps replay from costing the thing it protects. Exactly
-- once comes from the ORDER of two acts: the ledger row and the messages are
-- written in ONE transaction, and the file is unlinked only after that
-- transaction commits. Crash before the commit and the file replays; crash
-- after and the file is already gone. The one surviving failure — replaying a
-- file that was already ingested — is refused by the unique key on `name`,
-- which is what makes the job idempotent without a single `delete` against the
-- system of record.
--
-- `raw` stays append-only. Replay writes the same rows, to the same table, that
-- the writer would have written.

create table raw.spill_file (
	id           bigint      generated always as identity primary key,
	-- The file name, and the reason this table works. A second attempt at the
	-- same file conflicts here rather than duplicating its messages.
	name         text        not null unique,
	session_id   bigint      not null references raw.capture_session,
	cause        text        not null check (cause in ('DB_UNAVAILABLE', 'QUEUE_FULL')),
	messages     integer     not null,
	bytes        bigint      not null,
	-- When the recorder gave up on the database, and when the messages finally
	-- landed. The distance between them is the length of the outage, which is a
	-- fact about the capture worth keeping: it is the only record of a window
	-- where the system of record was not the system of record.
	spilled_at   timestamptz not null,
	ingested_at  timestamptz not null default now()
);

comment on table raw.spill_file is
	'One row per spill file ingested. Written in the same transaction as the '
	'file''s messages; the file is unlinked only after that transaction commits, '
	'so a re-run conflicts on `name` instead of duplicating the book.';
