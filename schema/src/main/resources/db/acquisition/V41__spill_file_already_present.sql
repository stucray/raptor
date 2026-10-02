-- raptor#40: a spill file's ledger row says how many of its messages were
-- already stored when it was replayed.
--
-- WHY. The recorder spills a batch whose write it cannot confirm, and that
-- includes a batch whose COMMIT reached the server while the reply never reached
-- the recorder. Since V38 put a unique key on raw.stream_message, replaying such
-- a file was refused outright, and after three drains the file was set aside with
-- an ERROR saying its messages were not captured, which was false. The replay now
-- appends only what is absent and counts what was already there, identically.
-- This column is where that count is kept, so "already present" is a recorded
-- fact and never an assumption.
--
-- NULLABLE, NO DEFAULT. NULL means "replayed before this column existed", which
-- is true of every row this migration finds (one on live). A default of 0 would
-- claim a measurement that was never made. The replay always supplies the value.

alter table raw.spill_file add column already_present integer;

alter table raw.spill_file add constraint spill_file_already_present_within_messages
	check (already_present between 0 and messages);

comment on column raw.spill_file.already_present is
	'How many of the file''s messages were already in raw.stream_message, '
	'identically, when it was replayed: their write had committed although the '
	'recorder never heard back (#40). Counted, not written again. NULL: replayed '
	'before this column existed.';
