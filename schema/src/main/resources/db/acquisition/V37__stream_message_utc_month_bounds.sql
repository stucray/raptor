-- raptor#26: new months of raw.stream_message begin at midnight UTC.
--
-- WHY. Every persisted date and time is UTC; a local zone belongs only at the
-- frontend. V3 broke that without storing a single local value: its `do` block
-- cast `date` bounds in the session's timezone, the session was at UTC+7, and so
-- every month runs 17:00Z to 17:00Z. PartitionMaintenance extends from the last
-- bound — which is right, a clock-derived bound would leave a hole — and so it
-- carries the offset forward for ever. A partition's name then states a month
-- its range does not match.
--
-- WHAT. Walking back from the latest partition, every one that is EMPTY and
-- begins AFTER now() is dropped; the first that holds a row, or covers now or
-- anything earlier, stays, and so does everything before it. From the kept
-- partition's upper bound a single bridging partition runs to the next midnight-
-- UTC month start at least a month on, then whole UTC months follow until the
-- runway reaches at least as far as it did. From there PartitionMaintenance's
-- month-on-from-the-last-bound arithmetic keeps every new bound on midnight UTC.
-- Nothing is done at all when the droppable partitions already end on midnight
-- UTC — so a database whose V3 ran in a UTC session, as CI's does, is untouched,
-- and running this twice changes nothing the second time.
--
-- WHAT STAYS. Months holding rows keep their 17:00Z bounds. Moving them means
-- rewriting the append-only system of record, and a bound changes no stored
-- value: every `pt` is a `timestamptz`, an instant, whichever partition holds it.
--
-- NO ROW IS TOUCHED. Only empty tables are dropped, and the parent is locked in
-- SHARE mode before any is checked — reads carry on, writes wait — so nothing can
-- land in a partition between the check that found it empty and the drop. Creating a partition scans the DEFAULT one for rows it would claim; that
-- partition is empty unless something is already wrong, in which case this
-- fails loudly rather than misfiling a row.
--
-- NO lock_timeout, unlike PartitionMaintenance. That one gives up and retries
-- tomorrow; a migration that gave up would stop the application booting. It runs
-- before the recorder starts, so the only thing it can wait on is a reader.
--
-- Reversal: none needed for an image rollback — the previous build extends from
-- whatever the last bound is and reads nothing else of it. The replaced
-- partitions were empty; to put 17:00Z bounds back, drop the empty future months
-- and let PartitionMaintenance recreate them from a 17:00Z bound, then
--   delete from query.flyway_schema_history_acquisition where version = '37';

do $$
declare
	p          record;
	is_empty   boolean;
	old_last   timestamptz;
	keep_last  timestamptz;
	doomed     regclass[] := '{}';
	misaligned boolean := false;
	lo         timestamptz;
	hi         timestamptz;
	t          timestamp;
	r          regclass;
begin
	lock table raw.stream_message in share mode;

	for p in
		select c.oid::regclass as rel,
			(split_part(split_part(pg_get_expr(c.relpartbound, c.oid),
				'FROM (''', 2), '''', 1))::timestamptz as lower_bound,
			(split_part(split_part(pg_get_expr(c.relpartbound, c.oid),
				'TO (''', 2), '''', 1))::timestamptz as upper_bound
		from pg_inherits i
		join pg_class c on c.oid = i.inhrelid
		where i.inhparent = 'raw.stream_message'::regclass
		  and pg_get_expr(c.relpartbound, c.oid) <> 'DEFAULT'
		order by 2 desc
	loop
		if old_last is null then
			old_last := p.upper_bound;
		end if;
		if p.lower_bound <= now() then
			keep_last := p.upper_bound;
			exit;
		end if;
		execute format('select not exists (select 1 from %s)', p.rel) into is_empty;
		if not is_empty then
			keep_last := p.upper_bound;
			exit;
		end if;
		doomed := doomed || p.rel;
		if p.upper_bound <> date_trunc('month', p.upper_bound at time zone 'UTC') at time zone 'UTC' then
			misaligned := true;
		end if;
	end loop;

	if keep_last is null then
		-- Nothing covers now or holds a row: not a schema this was written for,
		-- and not one to rebuild by guessing.
		raise notice 'raw.stream_message has no partition to keep; leaving its bounds alone';
		return;
	end if;
	if cardinality(doomed) = 0 then
		misaligned := keep_last <> date_trunc('month', keep_last at time zone 'UTC') at time zone 'UTC';
	end if;
	if not misaligned then
		return;
	end if;

	foreach r in array doomed loop
		execute format('drop table %s', r);
	end loop;

	lo := keep_last;
	loop
		exit when lo >= old_last
			and lo = date_trunc('month', lo at time zone 'UTC') at time zone 'UTC';
		-- A month on, then up to the next UTC month start: the bridge is between
		-- one and two months long, and every partition after it is exactly one.
		t := (lo at time zone 'UTC') + interval '1 month';
		hi := (case when t = date_trunc('month', t) then t
			else date_trunc('month', t) + interval '1 month' end) at time zone 'UTC';
		-- Named for the month its midpoint falls in, as PartitionMaintenance names
		-- them; bounds rendered in UTC with the offset spelled out.
		execute format(
			'create table raw.%I partition of raw.stream_message '
			'for values from (%L) to (%L) with (fillfactor = 100, '
			'autovacuum_vacuum_insert_scale_factor = 0, '
			'autovacuum_vacuum_insert_threshold = 1000000)',
			'stream_message_' || to_char((lo + (hi - lo) / 2) at time zone 'UTC', 'YYYY_MM'),
			to_char(lo at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00',
			to_char(hi at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00');
		lo := hi;
	end loop;
end
$$;
