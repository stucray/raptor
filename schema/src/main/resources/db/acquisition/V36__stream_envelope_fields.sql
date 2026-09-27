-- raptor#22: each live message keeps the two envelope fields that say what kind
-- of message it came in.
--
-- WHY. A row holds one market change (`mc[i]`) and its `pt`. The envelope around
-- it was dropped, so a reader could not tell a change Betfair split across
-- several messages (a segmented message, which shares one `pt`) from two changes
-- inside one millisecond — and a reader that emits a book per message emits a
-- half-applied one after the first segment. Also dropped: whether a message was
-- the full image a (re)subscription starts with, or a resubscription's catch-up
-- delta, which is what a reader needs to know to apply it.
--
--   * segment_type: the envelope's `segmentType` — SEG_START, SEG or SEG_END,
--     NULL when the change was not segmented.
--   * change_type: the envelope's `ct` — SUB_IMAGE or RESUB_DELTA, NULL for an
--     ordinary delta. (HEARTBEAT messages carry no market change and are never
--     stored.)
--
-- Verbatim, as everything in `raw` is: no check constraint on the values. A value
-- Betfair adds later is stored, not refused into the quarantine.
--
-- NULL also means "not recorded": every row written before this migration, every
-- row loaded from a vendor or capture file, and every row of a spill file written
-- by an earlier build. The two columns cannot tell those apart from "not sent",
-- and do not try to.
--
-- NOT STORED: `clk`/`initialClk`. They are resume tokens, opaque by contract and
-- not an ordering, and at roughly 400 bytes a row a token on every row would add
-- about a tenth to the table. The recorder keeps the latest in memory for its
-- reconnects, which is their purpose.
--
-- Nullable with no default, so on the partitioned parent this is catalogue-only:
-- no partition is rewritten. `rejected_message` gains the same two columns so a
-- quarantined row keeps them.
--
-- Reversal: none needed for an image rollback — the previous build names its
-- COPY columns and never reads these. To remove them:
--   alter table raw.stream_message drop column segment_type, drop column change_type;
--   alter table raw.rejected_message drop column segment_type, drop column change_type;
--   delete from query.flyway_schema_history_acquisition where version = '36';

set local lock_timeout = '10s';

alter table raw.stream_message
	add column segment_type text,
	add column change_type text;

comment on column raw.stream_message.segment_type is
	'The stream envelope''s segmentType (SEG_START, SEG, SEG_END) for a change split across messages; NULL when not segmented, and on rows not recorded with it (before V36, vendor and capture files).';
comment on column raw.stream_message.change_type is
	'The stream envelope''s ct (SUB_IMAGE, RESUB_DELTA); NULL for an ordinary delta, and on rows not recorded with it (before V36, vendor and capture files).';

alter table raw.rejected_message
	add column segment_type text,
	add column change_type text;
