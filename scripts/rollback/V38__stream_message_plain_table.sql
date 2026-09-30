-- Reversal of acquisition V38 (#33): raw.stream_message goes back to being the
-- partitioned table V38 set aside, with every row captured since carried back
-- into it.
--
-- WHEN THIS IS WANTED. Before starting any image from before V38. Such an image
-- writes by partition and runs the partition extender, and against the plain
-- table its extender and runway indicator find no partitions to read. Flyway
-- would not stop it: a migration applied by a newer image is ignored
-- (`ignoreMigrationPatterns` defaults to `*:future`).
--
-- ONLY UNTIL #35 SHIPS. That migration drops the set-aside table, and after it
-- there is nothing to go back to.
--
-- WHAT IT DOES, in one transaction:
--   1. copies into the set-aside table every row written since V38. V38 numbered
--      its copy 1..N in a single statement under an exclusive lock, and the
--      set-aside table has not changed since, so N is its row count and every
--      later row has an id above N;
--   2. checks that both tables now hold the same number of rows, and raises if not;
--   3. renames the tables and indexes back, restores V3's comment, and drops the
--      plain table, whose rows are all in the partitioned one by step 2;
--   4. removes V38's history row.
--
-- Stop the backend first, so nothing writes during it. Probe it with `commit`
-- changed to `rollback`:
--
--   docker exec -i <postgres container> psql -U paddock -d paddock \
--     -v ON_ERROR_STOP=1 < scripts/rollback/V38__stream_message_plain_table.sql

begin;

lock table raw.stream_message in exclusive mode;
lock table raw.stream_message_partitioned in exclusive mode;

insert into raw.stream_message_partitioned (session_id, file_id, market_id, pt, received_at,
	seq, payload, segment_type, change_type)
select session_id, file_id, market_id, pt, received_at, seq, payload, segment_type,
	change_type
from raw.stream_message
where id > (select count(*) from raw.stream_message_partitioned)
order by id;

do $$
declare
	plain       bigint := (select count(*) from raw.stream_message);
	partitioned bigint := (select count(*) from raw.stream_message_partitioned);
begin
	if plain <> partitioned then
		raise exception 'V38 reversal: % row(s) in raw.stream_message but % in the set-aside table',
			plain, partitioned;
	end if;
	raise notice 'V38 reversal: % row(s) in both tables', plain;
end
$$;

alter table raw.stream_message rename to stream_message_v38;
alter table raw.stream_message_partitioned rename to stream_message;
alter index raw.stream_message_partitioned_file_seq_idx rename to stream_message_file_seq_idx;
alter index raw.stream_message_partitioned_market_pt_idx rename to stream_message_market_pt_idx;
comment on table raw.stream_message is
	'Betfair MCM stream messages, verbatim. NEVER make this unlogged: it is the '
	'obvious throughput knob and it is catastrophically wrong, because unlogged '
	'tables are TRUNCATED on crash recovery.';

drop table raw.stream_message_v38;

delete from query.flyway_schema_history_acquisition where version = '38';

select 'partitioned' as shape, count(*) as partitions
from pg_inherits where inhparent = 'raw.stream_message'::regclass;

commit;
