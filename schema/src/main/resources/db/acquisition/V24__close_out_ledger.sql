-- What the nightly close-out did, and whether it ran at all (#199).
--
-- WHY THIS SCHEMA, because it is the one debatable choice in this change.
-- The charter gives each schema a lifecycle: `raw` is the append-only system of
-- record, `query` is FULLY REBUILDABLE (every row a function of raw and the
-- parser version that read it), `batch` is the run ledger, `public` is the read
-- side's own.
--
-- A close-out record is none of the first three by default. It is emphatically
-- NOT rebuildable — nothing in `raw` says whether a chain ran at 23:30Z last
-- night — so putting it in `query` would place the one kind of row that cannot
-- be regenerated into the schema whose defining property is that every row can
-- be. That is a worse trade than it looks: `query` is TRUNCATE-and-reproject
-- territory (the documented space lever), and this table must survive that.
--
-- `batch` is the run ledger: "what ran, when, against which unit of work". This
-- is exactly that, for a unit of work Spring Batch does not model. The charter
-- names Spring Batch as its writer, which was a description of what was there
-- rather than a prohibition — and the alternative readings are all worse.
--
-- WHY NOT MAKE THE CHAIN A BATCH JOB and get this for free. Because the
-- per-market fan-out is deliberately NOT inside a job: one market failing must
-- fail alone and stay visible as its own BATCH_JOB_EXECUTION, which is the whole
-- reason ProjectLiveController launches one job per market rather than one job
-- per night. A parent job would have to either swallow those or fail with them.

create table batch.close_out (
	id                bigint      generated always as identity primary key,
	started_at        timestamptz not null,
	finished_at       timestamptz,
	-- Counted rather than derived from BATCH_JOB_EXECUTION: this table's job is
	-- to answer "did last night's close-out happen and what did it do" in one
	-- row, without a join to a repository whose retention is not ours.
	markets_projected integer     not null default 0,
	markets_failed    integer     not null default 0,
	archive_files     integer     not null default 0,
	status            text        not null,
	-- Why a run was unsuccessful, in words, for the same reason the capture
	-- health indicator carries `reason`: the reader of a failed overnight run
	-- needs the sentence, not a status to go and correlate.
	detail            text,

	constraint close_out_status_check
		check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
	-- A finished run has a finish time and vice versa. The pairing is what makes
	-- "a run that started and never came back" a distinguishable state rather
	-- than an ambiguous one.
	constraint close_out_finished_when_done
		check ((status = 'RUNNING') = (finished_at is null))
);

-- The only question anyone asks of this table: when did one last succeed? The
-- health screen and the heartbeat's staleness probe (#201) both read exactly it.
create index close_out_last_success
	on batch.close_out (finished_at desc)
	where status = 'COMPLETED';

comment on table batch.close_out is
	'One row per nightly close-out run. Not rebuildable: nothing in raw records '
	'whether a scheduled chain fired, which is why an absence here is a finding '
	'rather than a gap.';
