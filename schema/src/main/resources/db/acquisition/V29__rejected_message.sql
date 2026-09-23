-- #271: where a message goes when Postgres is up and the ROW is what it will
-- not take.
--
-- `RawWriteLoop` could not tell "the database is unavailable" from "the database
-- rejected this row", and spilled both. Spilling is exactly right for the first
-- and exactly wrong for the second: the retry can never succeed, `SpillIngest`
-- unlinks a file only after its transaction commits, and the drain therefore
-- replays the same doomed file every minute forever, accumulating files at
-- capture rate behind it.
--
-- NONE OF THIS HAS EVER HAPPENED, AND THAT IS THE HONEST STATE OF IT. No
-- message has been refused by this database in the life of the project, and no
-- spill file has ever been stuck. The corpus was searched before this was
-- written: 221,780 files, 1.06 GB of raw Betfair bytes, and not one carries a
-- unicode escape of ANY form. So this guards a class of failure, not a sighting.
--
-- What IS measured, against this server, is what such a refusal would look like.
-- Each of these was run rather than recalled:
--
--   23503  a session_id with no raw.capture_session row
--   23514  a pt outside every partition range, and the provenance check
--   23502  a null in market_id / pt / seq / payload
--   22P02  a payload that is not valid JSON
--   22P05  an unsupported Unicode escape
--
-- The realistic routes to one of those are schema-shaped rather than
-- payload-shaped: a migration that adds a constraint under a running recorder,
-- a session row that is not there, a partition set that ran out (which is what
-- #267 was, and #272 fixed). Those are deploy-time events, which is precisely
-- why the recorder should survive them rather than wedge.
--
-- WHY NOTHING HERE CAN REJECT A ROW. That is the entire design of this table,
-- so it is worth being explicit: a quarantine that can itself refuse the row it
-- exists to catch is not a quarantine.
--
--   * `payload` is TEXT, not `jsonb`. `jsonb` is the stricter type and is what
--     would have refused the row; text accepts anything the wire can carry that
--     is not a NUL byte.
--   * NO foreign keys. `session_id` and `file_id` are plain bigints, because
--     "the session row is not there" is 23503, one of the rejections caught.
--   * NO check constraints. The provenance rule is one thing 23514 fires on.
--   * NOT partitioned, and `pt` is nullable. A `pt` outside every partition
--     range is the #267 failure, and it must land here whatever its value.
--   * Every column but the identity and the timestamp is nullable, because a
--     null where the system of record demands one is 23502, and this table has
--     to be able to hold the evidence of that.
--
-- If a write to THIS table still fails, the writer falls back to spilling, and
-- the operator is no worse off than before the change.

create table raw.rejected_message (
	id bigint generated always as identity primary key,
	rejected_at timestamptz not null default now(),
	-- The SQLSTATE and the driver's message, so the reason is recoverable from
	-- the row rather than only from a log line that has since rotated away.
	sqlstate text,
	failure text,
	session_id bigint,
	file_id bigint,
	market_id text,
	pt timestamptz,
	received_at timestamptz,
	seq bigint,
	payload text
);

comment on table raw.rejected_message is
	'Messages Postgres refused on their own merits (SQLSTATE class 22 or 23), '
	'held whole so the cause can be fixed and the rows replayed. NOT part of '
	'the system of record: a row here has not been captured. #271.';

comment on column raw.rejected_message.payload is
	'TEXT, deliberately: jsonb is what rejected it. See the migration header.';

-- A read on this table happens once per health check, so it must not become a
-- scan of something large. It should be empty; the index is what keeps the
-- "is it empty" answer cheap if it ever is not.
create index rejected_message_rejected_at_idx on raw.rejected_message (rejected_at desc);
