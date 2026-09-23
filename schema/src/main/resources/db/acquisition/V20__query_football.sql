-- The football-data.co.uk archive, projected.
--
-- Replaces `public.football_match`, which the normaliser's spool run filled and
-- which was a full replace on every import. This is the same shape derived a
-- different way: `raw.football_file` -> FootballDataParser -> TeamNames.resolve
-- -> here. The read side's cutover onto it is a separate migration, deliberately
-- (#94): a cross-derivation comparison cannot be run once one of the two sides
-- has been dropped, and the spool table is the only copy of that baseline.
--
-- STILL FULLY REBUILDABLE. Every row is a function of raw and the parser version
-- that read it, which is what `query` means. The alias table the resolve applies
-- is committed in the build rather than derived, so the parser version covers it
-- too: a change to the names is a deploy, not a data edit.
create table query.football_match (
	id            bigint generated always as identity primary key,
	-- The version of the file this row was read from. Not just provenance: the
	-- archive republishes files in place, so "which bytes said this" is a real
	-- question with a changing answer, and raw.football_file keeps every version.
	file_id       bigint  not null references raw.football_file (id),
	-- Denormalised from that row so a re-projection can delete by file without a
	-- join, and so the common read (a season) never touches raw at all.
	path          text    not null,

	-- The ten columns the read side has always had. DivCode and Season come from
	-- the FILE NAME, never from the file's own `Div` column, which is a display
	-- label -- E0's rows say "E0" but SC0's say "SC0" in some seasons and
	-- "Scottish Premiership" in others.
	div_code      text    not null,
	div           text,
	season        text    not null,
	season_start  integer not null,
	-- Null where the file's Date cell did not parse. The archive has none today
	-- (unparsed=0 over 241,809 rows), and a null here is the honest spelling for
	-- a row whose date the trusted Python also could not read.
	match_date    date,
	home_team     text    not null,
	away_team     text    not null,
	fthg          integer,
	ftag          integer,
	ftr           text,

	-- The whole row, in the canonical spelling the parity gate pins: column name
	-- -> cell, absent as JSON null. 234 columns across the archive, mostly odds,
	-- and the simulation lab is the obvious next consumer.
	--
	-- Values are TEXT even where they are numbers, because the canonical spelling
	-- is what two implementations were proven to agree on. Rendering them as JSON
	-- numbers here would introduce a second numeric formatting, unverified, in
	-- the one place that claims to be verified.
	canonical     jsonb   not null,

	projection_id bigint  not null references query.projection (id)
);

comment on table query.football_match is
	'One row per match in the football-data.co.uk archive, projected from '
	'raw.football_file. Team names are resolved through the committed alias '
	'table, so home_team and away_team are archive-canonical -- which is what '
	'every cross-source join is made on.';

create index football_match_div_season_idx
	on query.football_match (div_code, season_start);
create index football_match_date_idx on query.football_match (match_date);
create index football_match_teams_idx on query.football_match (home_team, away_team, match_date);
create index football_match_file_idx on query.football_match (file_id);

-- The run ledger counts what each projection produces, and a match is not a
-- market. Nullable like the others: a row records one job's unit of work, and
-- most jobs leave most of these columns empty.
alter table query.projection add column matches bigint;

comment on column query.projection.matches is
	'Rows written into query.football_match by a projectFootballJob run.';
