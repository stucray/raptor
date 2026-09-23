-- #316 (PRD #309): paddock's nightly close-out no longer projects anything.
--
-- overround-analysis writes `query` now, and projects the live backlog and the
-- football archive as the first step of its own 00:30Z close-out. What is left
-- here at 23:30Z is the one half that writes `raw`: fetching the current
-- season's football-data files. So the close-out ledger keeps recording a run
-- every night, and from here each row has an archive verdict and no capture one.
--
-- WHY NULL, AND NOT 0 OR COMPLETED. The capture half's columns recorded a
-- projection; on a row from after #316 there was no projection to record, and a
-- zero would read as a night that projected nothing — which is precisely the
-- failure V25 existed to tell apart from a quiet night. NULL means "this run had
-- no capture half", and the column comments below say so. Rows written before
-- this migration keep their values: they did project, and they say what they did.
--
-- The roll-up `status` follows the archive alone from here, since it is the only
-- half there is.

alter table batch.close_out
	-- V25 tied "finished" to the capture verdict being present. A finished run
	-- now has no capture verdict, so the same guarantee moves to the archive one:
	-- null exactly while running, never invented at insert time.
	drop constraint close_out_halves_when_done,
	add constraint close_out_archive_when_done
		check ((status = 'RUNNING') = (archive_status is null)),
	alter column markets_projected drop not null,
	alter column markets_projected drop default,
	alter column markets_failed drop not null,
	alter column markets_failed drop default;

-- The index served "when did the capture half last succeed", which paddock no
-- longer asks. overround-analysis answers it now, from its own ledger.
drop index if exists batch.close_out_last_capture_success;

comment on column batch.close_out.capture_status is
	'Whether the night''s captured markets projected into query. NULL on every '
	'run since #316: overround-analysis projects query now, and reports it on its '
	'own close-out (analysis.analysis_close_out.projection_status).';
comment on column batch.close_out.markets_projected is
	'Markets projected by the capture half. NULL since #316, when the half left.';
comment on column batch.close_out.markets_failed is
	'Markets that failed to project. NULL since #316, when the half left.';
comment on column batch.close_out.archive_status is
	'Whether the football-data sweep fetched the current season into raw. Since '
	'#316 this is the whole run: projecting the archive is overround-analysis''s.';
