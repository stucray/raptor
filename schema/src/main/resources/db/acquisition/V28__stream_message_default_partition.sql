-- #267: the backstop under the partition extender.
--
-- WHAT IT IS FOR. `raw.stream_message` is `partition by range (pt)`, and a row
-- whose `pt` falls outside every partition is not slow or misplaced — it is
-- rejected, with *no partition of relation found for row*. The recorder then
-- cannot write the system of record at all, and because the rejection is
-- data-shaped rather than a database outage, RawWriteLoop's catch-all spills
-- every batch to disk and SpillDrain retries them forever without ever
-- succeeding. A DEFAULT partition turns that total write failure into rows
-- landing somewhere findable.
--
-- IT IS A NET, NOT A PLAN. PartitionMaintenance is what keeps the runway
-- moving; this is what happens when it has not. Two things follow from that:
--
--   * A row here is a FAULT REPORT. It means either the extender stopped or a
--     `pt` arrived that nothing anticipated, and `partitionRunway` reports the
--     partition non-empty for exactly that reason.
--   * A non-empty default BLOCKS creating the partition that should have held
--     its rows: PostgreSQL scans the default when a new partition is attached
--     and refuses if any row belongs in the new range. Recovery is to move the
--     rows out, create the partition, and put them back. That is a mess, and a
--     mess is the point — it is what an outage was traded for.
--
-- The bound is not needed and not given: DEFAULT catches whatever the others do
-- not, including a `pt` before 2019 that a corrupt or misparsed vendor file
-- could produce.

create table raw.stream_message_default
	partition of raw.stream_message default
	with (fillfactor = 100,
		autovacuum_vacuum_insert_scale_factor = 0,
		autovacuum_vacuum_insert_threshold = 1000000);

comment on table raw.stream_message_default is
	'Backstop for rows outside every monthly partition (#267). A row in here is '
	'a fault report, not data in its right place: it means PartitionMaintenance '
	'stopped extending the runway, or a pt arrived that nothing anticipated. It '
	'also blocks creating the partition that range belongs to until it is '
	'emptied.';
