-- The indexes S2 deliberately deferred.
--
-- V3 shipped `raw.stream_message` with no index at all, because building one
-- during the bulk load costs 5-10x on the way in. That deferral had a cost the
-- plan did not price: integrity checks over 20.4M unindexed rows must be
-- set-based, since a correlated subquery per file times out outright. Now that
-- the corpus has landed, the projection needs both access paths.
--
-- Declared on the partitioned parent, so PostgreSQL creates and attaches a
-- matching index on every partition — including the ones PartitionMaintenance
-- adds later.

-- The projection's access path. A market's blocks must be read back in the
-- exact order the file held them, and `seq` is the total order that reproduces
-- it: `pt` alone does not, because a single message carries many blocks at one
-- publish time.
create index stream_message_file_seq_idx on raw.stream_message (file_id, seq);

-- paddock's access path, and the live projection's: everything downstream asks
-- for one market over a time range.
create index stream_message_market_pt_idx on raw.stream_message (market_id, pt);
