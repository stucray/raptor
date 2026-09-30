-- raptor#33 (PRD #28): raw.stream_message becomes one plain, append-only table,
-- keyed only for integrity.
--
-- WHY. V3 partitioned the system of record by month on Betfair's publish time,
-- and V4 indexed it for two ways of reading it. None of that was for capture. It
-- cost capture a failure mode (a `pt` outside every partition cannot be written),
-- and it took machinery to guard against that: an extender, a DEFAULT partition,
-- a runway health check, V37's realignment. The table now states what a message
-- IS and nothing about how it will be read:
--
--   * `id`, a surrogate key with no meaning. No code orders by it.
--   * unique (session_id, seq, market_id): market m's change in message seq of
--     capture session s. The market is part of the key because sessions imported
--     from the earlier recorder's files numbered `seq` per stream message, so one
--     message carrying several markets gives several rows one (session_id, seq).
--     Checked on live 2026-09-30 before this was written (#31): 0 duplicates.
--   * unique (file_id, seq): message seq of vendor file f. 0 duplicates.
--   * NULLs are distinct, so each key binds only rows of its own provenance.
--   * No other index, and no partitioning.
--
-- Every column keeps its type and nullability. The provenance check, both
-- foreign keys, the grants, the table and column comments and the storage
-- options all carry over. The grants and comments are copied from the old
-- table's catalogue rather than restated, so this moves exactly what live has.
--
-- HOW. The new table is built beside the old one and every row is copied,
-- ordered by provenance and seq. The constraints are added after the copy,
-- because building them once is far cheaper than maintaining them per row.
-- The copy is then counted per capture session and per vendor file against its
-- source, and ANY difference raises, which rolls the whole migration back. Only
-- then is the old table renamed aside to `raw.stream_message_partitioned`, with
-- its partitions and DEFAULT partition, and the new one given the canonical name.
--
-- LOCKING. The old table is locked EXCLUSIVE at the start: reads carry on, writes
-- wait, so no row can land after the copy has read past it. Nothing should be
-- writing anyway, because this runs at startup before the recorder does. The
-- renames at the end need ACCESS EXCLUSIVE, which waits for any reader still
-- running. Deploy with `lid` SAFE and with no long analysis read in flight.
--
-- The set-aside table is NOT dropped here. That is #35, and it ships only after
-- a nightly backup of the new table has passed the restore drill.
--
-- REVERSAL, until #35 ships: scripts/rollback/V38__stream_message_plain_table.sql.
-- It copies back any row captured since, renames the tables back and removes this
-- history row. Run it BEFORE starting an image from before V38, which writes by
-- partition and extends partitions. Rehearse it with `commit` changed to
-- `rollback` first.

set local work_mem = '256MB';
set local maintenance_work_mem = '1GB';

lock table raw.stream_message in exclusive mode;

create table raw.stream_message_new (
	id           bigint generated always as identity,
	session_id   bigint,
	file_id      bigint,
	market_id    text        not null,
	pt           timestamptz not null,
	received_at  timestamptz,
	seq          bigint      not null,
	payload      jsonb       not null,
	segment_type text,
	change_type  text,

	constraint stream_message_one_provenance
		check (num_nonnulls(session_id, file_id) = 1)
) with (fillfactor = 100,
	autovacuum_vacuum_insert_scale_factor = 0,
	autovacuum_vacuum_insert_threshold = 1000000);

-- The order is total. File rows come first by (file_id, seq), then session rows
-- by (session_id, seq, market_id), and both keys are unique.
insert into raw.stream_message_new (session_id, file_id, market_id, pt, received_at, seq,
	payload, segment_type, change_type)
select session_id, file_id, market_id, pt, received_at, seq,
	payload, segment_type, change_type
from raw.stream_message
order by file_id nulls last, session_id, seq, market_id;

-- Proof that the copy is whole, and the migration fails if it is not. One pass
-- over each table, grouped by provenance: a capture session or a vendor file is
-- a group, and every group must hold the same number of rows on both sides.
-- GROUP BY treats NULLs as equal, which a join on these columns would not.
do $$
declare
	source_rows bigint;
	copied_rows bigint;
	sessions    bigint;
	files       bigint;
	mismatched  bigint;
begin
	select count(*) filter (where source is distinct from copied),
		count(*) filter (where session_id is not null),
		count(*) filter (where file_id is not null),
		coalesce(sum(source), 0), coalesce(sum(copied), 0)
	into mismatched, sessions, files, source_rows, copied_rows
	from (
		select session_id, file_id,
			sum(n) filter (where side = 'source') as source,
			sum(n) filter (where side = 'copy')   as copied
		from (
			select 'source' as side, session_id, file_id, count(*) as n
			from raw.stream_message group by session_id, file_id
			union all
			select 'copy', session_id, file_id, count(*)
			from raw.stream_message_new group by session_id, file_id
		) counted
		group by session_id, file_id
	) per_provenance;

	if mismatched <> 0 or source_rows <> copied_rows then
		raise exception 'V38: the copy of raw.stream_message is not whole: % of % session/file group(s) differ, % source row(s) against % copied',
			mismatched, sessions + files, source_rows, copied_rows;
	end if;
	raise notice 'V38: copied % row(s); counts equal for % capture session(s) and % vendor file(s)',
		copied_rows, sessions, files;
end
$$;

alter table raw.stream_message_new
	add constraint stream_message_pkey primary key (id),
	add constraint stream_message_session_seq_market_key unique (session_id, seq, market_id),
	add constraint stream_message_file_seq_key unique (file_id, seq),
	add constraint stream_message_session_id_fkey
		foreign key (session_id) references raw.capture_session,
	add constraint stream_message_file_id_fkey
		foreign key (file_id) references raw.historic_file;

-- Grants and comments, copied from the catalogue so that what moves is exactly
-- what live has. The owner's own entry is implicit and is not re-granted.
do $$
declare
	g   record;
	col record;
begin
	for g in
		select a.grantee, a.privilege_type, a.is_grantable
		from pg_class c, aclexplode(c.relacl) a
		where c.oid = 'raw.stream_message'::regclass
			and a.grantee <> c.relowner
	loop
		execute format('grant %s on raw.stream_message_new to %s%s',
			g.privilege_type,
			case when g.grantee = 0 then 'public' else g.grantee::regrole::text end,
			case when g.is_grantable then ' with grant option' else '' end);
	end loop;

	execute format('comment on table raw.stream_message_new is %L',
		obj_description('raw.stream_message'::regclass, 'pg_class'));
	for col in
		select a.attname, col_description(a.attrelid, a.attnum) as description
		from pg_attribute a
		where a.attrelid = 'raw.stream_message'::regclass
			and a.attnum > 0 and not a.attisdropped
			and col_description(a.attrelid, a.attnum) is not null
	loop
		execute format('comment on column raw.stream_message_new.%I is %L',
			col.attname, col.description);
	end loop;

	-- Checked, not assumed: `raw`'s default privileges also grant on a new table,
	-- so a copy that granted MORE than the source had would pass unnoticed.
	if (select array_agg(x::text order by x::text)
			from pg_class c, unnest(c.relacl) x where c.oid = 'raw.stream_message'::regclass)
		is distinct from
		(select array_agg(x::text order by x::text)
			from pg_class c, unnest(c.relacl) x where c.oid = 'raw.stream_message_new'::regclass)
	then
		raise exception 'V38: the new table''s grants differ from raw.stream_message''s';
	end if;
end
$$;

comment on column raw.stream_message_new.id is
	'A surrogate key with no meaning. It is neither an order nor a provenance: '
	'order within a session or file is seq.';

-- Set the old table aside, with its partitions, and put the new one in its place.
alter table raw.stream_message rename to stream_message_partitioned;
alter index raw.stream_message_file_seq_idx rename to stream_message_partitioned_file_seq_idx;
alter index raw.stream_message_market_pt_idx rename to stream_message_partitioned_market_pt_idx;
comment on table raw.stream_message_partitioned is
	'raw.stream_message as it was before V38 (#33), set aside intact. Every row is '
	'also in raw.stream_message. Dropped by a later migration (#35) once a backup '
	'of the new table has passed the restore drill; nothing reads or writes it.';

alter table raw.stream_message_new rename to stream_message;
alter sequence raw.stream_message_new_id_seq rename to stream_message_id_seq;

analyze raw.stream_message;
