-- raptor#35 (PRD #28): drop the partitioned raw.stream_message that V38 set aside
-- as `raw.stream_message_partitioned`, with all of its partitions, the DEFAULT
-- partition included.
--
-- WHY NOW. V38 kept it only as the way back. The plain table has since been backed
-- up and that backup restored by the drill and compared against live: OK on
-- 2026-09-30 for dump raptor-raw-20260930T062439Z (#34). Until this runs, every
-- nightly dump carries the corpus twice.
--
-- THE LAST COMPARISON. Once the table is gone, nothing can compare it with the
-- plain one again, so this checks first and raises if any of its rows is not in
-- raw.stream_message, which rolls the whole migration back with nothing dropped.
-- That is exactly the question a drop has to answer: a row only the set-aside
-- table holds is a row this would destroy. A row only the plain table holds is
-- not, because it survives. A row matches on its integrity key, whichever
-- provenance it has (the provenance check guarantees one), plus its market and
-- publish time. Comparing counts instead would need a cut-off for rows captured
-- since V38, and any cut-off read from the set-aside table shrinks with a loss
-- from it and can hide one.
--
-- COST. Two hash anti-joins over the whole corpus on both sides, at startup.
-- A hash join spills to disk in batches, so `work_mem` bounds its memory rather
-- than deciding whether this finishes. It is kept SERIAL: a parallel hash join
-- builds its table in shared memory, and the container's /dev/shm cannot hold
-- one this size. Probed on live 2026-10-01 with parallelism on, both joins failed
-- in under 4s with "could not resize shared memory segment ... No space left on
-- device", which here would have failed the startup.
--
-- LOCKING. Dropping the table also drops its foreign keys, which locks
-- raw.capture_session and raw.historic_file. `lock_timeout` bounds the wait for a
-- reader still running, so a long analysis read fails this migration (and the
-- startup) rather than wedging it. Deploy with `lid` SAFE.
--
-- NO REVERSAL. scripts/rollback/V38 goes with this migration, because it needs
-- the table dropped here. An image from after V38 (b6bd644 or later) still starts
-- cleanly against this database: it never names the set-aside table. An image
-- from BEFORE V38 can no longer be run at all, since it writes by partition. The
-- only way back to that is restoring a dump taken before this migration.

set local work_mem = '64MB';
set local max_parallel_workers_per_gather = 0;

do $$
declare
	set_aside      bigint;
	partitions     bigint;
	missing_session bigint;
	missing_file   bigint;
begin
	select count(*) into missing_session
	from raw.stream_message_partitioned k
	where k.session_id is not null and not exists (
		select 1 from raw.stream_message p
		where p.session_id = k.session_id and p.seq = k.seq
			and p.market_id = k.market_id and p.pt = k.pt);

	select count(*) into missing_file
	from raw.stream_message_partitioned k
	where k.file_id is not null and not exists (
		select 1 from raw.stream_message p
		where p.file_id = k.file_id and p.seq = k.seq
			and p.market_id = k.market_id and p.pt = k.pt);

	if missing_session + missing_file <> 0 then
		raise exception 'V39: % row(s) of the set-aside table are not in raw.stream_message (% from capture sessions, % from vendor files); nothing dropped',
			missing_session + missing_file, missing_session, missing_file;
	end if;

	set_aside := (select count(*) from raw.stream_message_partitioned);
	partitions := (select count(*) from pg_inherits
		where inhparent = 'raw.stream_message_partitioned'::regclass);

	set local lock_timeout = '30s';
	drop table raw.stream_message_partitioned;

	raise notice 'V39: dropped raw.stream_message_partitioned, % partition(s) and % row(s), every one of them also in raw.stream_message',
		partitions, set_aside;
end
$$;
