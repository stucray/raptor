-- The football-data.co.uk results archive, in the system of record.
--
-- The third source, and the first whose raw is fetched from the public internet
-- rather than captured from a stream or downloaded once. Two things follow from
-- that, and they are why this table holds the bytes where raw.historic_file and
-- raw.capture_file hold only a digest:
--
--  * The upstream files are MUTABLE. football-data.co.uk republishes a season's
--    CSV whenever a result is corrected, and the fetch overwrites the file on
--    disk. Custody keeps the current bytes; nothing kept the previous ones. So
--    every analysis this project has ever run against a corrected file was run
--    against bytes that no longer exist anywhere.
--  * The parse must stay out of the write path (CLAUDE.md), so what is stored
--    has to be the CSV exactly as served -- unparsed, undecoded, not even
--    charset-guessed. 15 of these files are cp1252 and the rest are UTF-8; that
--    is the projection's problem, in S9's parser, not this table's.
--
-- Hence: append-only, one row per (path, sha256). A re-fetch that finds the
-- same bytes writes nothing; a re-fetch that finds new bytes adds a row beside
-- the old one, and the newest row for a path is what the projection reads.
create table raw.football_file (
	id            bigint generated always as identity primary key,
	-- Relative to the source's custody directory -- 'raw/E0/E0-2526.csv' --
	-- which is the same spelling the data repo's manifest.csv uses, so a row
	-- here and a row there name the same file without translation.
	path          text        not null,
	division      text        not null,
	season        text        not null,
	sha256        bytea       not null,
	bytes         bigint      not null,
	-- The CSV as served. Postgres TOASTs and compresses it; the whole archive is
	-- around 90 MB before compression.
	content       bytea       not null,
	-- What the server gave us to revalidate with. Both are opaque strings echoed
	-- back verbatim: an ETag is exact where a date comparison is only as good as
	-- the clock, so the fetch prefers it. Null where the server sent neither,
	-- which costs a full body on the next sweep and nothing else.
	etag          text,
	last_modified text,
	-- When these bytes first arrived. Never updated: it is the observation.
	fetched_at    timestamptz not null default now(),
	-- When the server last confirmed this version is still the current one --
	-- a 304, or an unconditional re-fetch that turned out identical. This IS
	-- updated, and it is the one thing about a row that ever is. The content is
	-- immutable; the validators and this stamp are the cache key for the next
	-- request rather than data derived from the file, and without refreshing
	-- them a server that rotates an ETag without changing a byte would make
	-- every future sweep re-download the same file forever.
	checked_at    timestamptz not null default now(),
	-- Append-only: the same bytes at the same path are the same observation, and
	-- a sweep that re-downloads them (no validators held, say) must not add a
	-- second row. Different bytes are a different observation and always do.
	unique (path, sha256)
);

comment on table raw.football_file is
	'One row per version of a football-data.co.uk CSV ever fetched. Unlike '
	'raw.historic_file and raw.capture_file this holds the content as well as '
	'the digest, because upstream republishes these files in place -- the disk '
	'keeps only the current bytes, and a superseded version exists nowhere '
	'else. The bytes are stored exactly as served: the parse belongs to the '
	'projection, never to the write path.';

-- The projection asks one question of this table: the newest row for each path.
create index football_file_latest on raw.football_file (path, fetched_at desc);
