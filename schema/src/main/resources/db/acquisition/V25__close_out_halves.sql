-- The close-out has two halves that fail independently, and one status could
-- not say so (#201).
--
-- WHAT WENT WRONG, on the very first live run. 2026-09-07 23:30Z projected all
-- 44 of the night's markets, none failed, in 81 seconds — and the row reads
-- FAILED, because football-data.co.uk had been answering 503 for nineteen hours
-- and the archive sweep could not fetch any of its 22 files. The health screen
-- then reported `lastCloseOut: "never"`, because the only query anyone asks of
-- this table selected `where status = 'COMPLETED'`.
--
-- So a night whose IRREPLACEABLE half succeeded completely reported as no
-- close-out having ever succeeded — the "nothing has ever run" signal, which is
-- the exact ambiguity V24 was written to remove, reintroduced one level up.
--
-- WHY THAT IS NOT COSMETIC. #201's staleness probe alerts when no close-out has
-- succeeded in 26 hours, over ntfy, to an operator who is nearly always away.
-- Built on the old column it fires whenever a free website is down while the
-- capture data was projected on time — and a channel that cries wolf is how the
-- real alert stops being read. It fails the other way too: a night where the
-- archive sweep worked and MARKETS FAILED TO PROJECT produced the same FAILED
-- and the same "never". One signal, two opposite meanings.
--
-- WHY COLUMNS RATHER THAN A RICHER STATUS ENUM. A COMPLETED_WITH_ARCHIVE_FAILURE
-- value would have to grow again the moment a third source joins the chain, and
-- every reader would have to know which values counted as "capture was fine".
-- Two independent verdicts are what actually happened, and the morning summary
-- wants to report them separately anyway.
--
-- `status` stays as the roll-up so nothing that reads it starts lying.

alter table batch.close_out
	add column capture_status text,
	add column archive_status text;

-- BACKFILL FROM WHAT THE ROWS ALREADY PROVE, rather than defaulting to
-- COMPLETED: `markets_failed` records the capture half exactly, and a run that
-- rolled up to COMPLETED had both halves succeed by construction.
update batch.close_out
set capture_status = case when markets_failed = 0 then 'COMPLETED' else 'FAILED' end,
	archive_status = case when status = 'COMPLETED' then 'COMPLETED' else 'FAILED' end
where status <> 'RUNNING';

alter table batch.close_out
	add constraint close_out_capture_status_check
		check (capture_status is null or capture_status in ('COMPLETED', 'FAILED')),
	add constraint close_out_archive_status_check
		check (archive_status is null or archive_status in ('COMPLETED', 'FAILED')),
	-- Null exactly while running, for the same reason `finished_at` is: a run
	-- that started and never came back must stay distinguishable from one that
	-- finished, and a half-verdict invented at insert time would erase that.
	add constraint close_out_halves_when_done
		check ((status = 'RUNNING') = (capture_status is null));

-- The question the health screen and the staleness probe actually ask: when did
-- the CAPTURE half last succeed? Replaces the index on the roll-up, which is
-- now nobody's predicate.
drop index if exists batch.close_out_last_success;
create index close_out_last_capture_success
	on batch.close_out (finished_at desc)
	where capture_status = 'COMPLETED';

comment on column batch.close_out.capture_status is
	'Whether last night''s captured markets projected. THIS is what "did the '
	'close-out work" means: the archive can be re-fetched tomorrow, a capture '
	'that never reached query stays unprojected until someone notices.';
comment on column batch.close_out.archive_status is
	'Whether the football-data sweep and its projection succeeded. A free '
	'website being down is an ordinary night and must not read as a lost one.';
