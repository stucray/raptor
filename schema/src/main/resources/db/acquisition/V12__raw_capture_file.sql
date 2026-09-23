-- The Python recorder's captures, brought into the system of record.
--
-- Eleven nights of live capture (611 files, 2026-08-21 to 2026-09-02) exist
-- only as `.ndjson.gz` files under the custody root and as the spool-derived
-- tables in `public`. S8 serves the read side from `query`, and a projection
-- can only project what `raw` holds — so without this the cutover would quietly
-- drop ten nights of capture history.
--
-- Two things are needed: somewhere to record each file's arrival and its
-- digest, and a session for its messages to belong to.

-- The recorder that wrote these files was launched by launchd, which is neither
-- of the origins a resident recorder can have. Recording it as RESIDENT would
-- be false and recording it as MANUAL would erase the distinction between a run
-- someone started and a run the schedule started -- which is exactly the
-- distinction the league-capture programme's lost nights turned on.
alter table raw.capture_session drop constraint capture_session_origin_check;
alter table raw.capture_session add constraint capture_session_origin_check
	check (origin in ('RESIDENT', 'MANUAL', 'SCHEDULED'));

-- The natural key of an imported session, and the reason the seed below can be
-- re-run. Null for every session a resident recorder opens for itself: it knows
-- its own identity and does not need one assigned.
alter table raw.capture_session add column source_key text unique;

comment on column raw.capture_session.source_key is
	'For a session IMPORTED from the Python era, the run_key its launchd log '
	'was parsed under. The log parser that minted it is deleted by S11, so this '
	'is the last durable trace of how these sessions were identified.';

create table raw.capture_file (
	id          bigint generated always as identity primary key,
	-- Path under the capture root, which is the natural key: one file per
	-- market, named for it.
	path        text        not null unique,
	sha256      bytea       not null,
	bytes       bigint      not null,
	messages    integer     not null,
	-- LOADED     its messages are in raw.stream_message
	-- SUPERSEDED the resident recorder captured this market itself, so paddock's
	--            own session holds it and loading the file too would put two
	--            observations of one market into one system of record
	status      text        not null check (status in ('LOADED', 'SUPERSEDED')),
	-- The `_meta` header line, verbatim, when the file has one. It is not a
	-- Betfair message and must not become one -- but it carries the only record
	-- of the event and competition names the Python resolved, which the read
	-- side's fixture join is built on. A resident capture has no header, and its
	-- absence says nothing about the capture.
	meta_json   jsonb,
	loaded_at   timestamptz not null default now()
);

comment on table raw.capture_file is
	'One row per Python-era capture file. The sha256 is what lets the load be '
	'proven faithful against the files on disk -- jsonb does not preserve '
	'bytes, so nothing inside the database can serve as that evidence. The same '
	'role raw.historic_file plays for the vendor corpus, for a corpus that is '
	'ours and unreplayable rather than re-downloadable.';

-- Seed the imported sessions from paddock's own capture-run ledger.
--
-- WHY FROM A SPOOL-DERIVED TABLE, when raw is the system of record. Because
-- public.capture_run is not a derivation of the DATA -- it is the record of
-- what the recorder DID, reconstructed from its launchd logs by a parser S11
-- deletes. Once `public` is cleaned up, the exit statuses, the origins and the
-- configuration each night actually ran with are gone. This is the last moment
-- at which they can be carried across, and a session with no record of how it
-- ended is a worse system of record than one seeded from the only source there
-- has ever been.
--
-- Guarded rather than assumed: on a fresh database (and in every test) `public`
-- holds no ledger, and the correct outcome there is no imported sessions at
-- all.
do $$
begin
	if to_regclass('public.capture_run') is null then
		return;
	end if;

	insert into raw.capture_session (
		source_key, started_at, ended_at, origin, exit_status, exit_detail,
		config_json, build_version)
	select
		r.run_key,
		r.started_at,
		r.ended_at,
		r.origin,
		r.exit_status,
		r.exit_detail,
		jsonb_strip_nulls(jsonb_build_object(
			'source', 'imported from ' || r.log_file || ' segment ' || r.segment,
			'marketTypes', r.market_types,
			'countries', r.countries,
			'leagues', r.leagues,
			'controlCountries', r.control_countries,
			'competitions', r.competitions,
			'competitionsResolved', r.competitions_resolved,
			'competitionsNotFound', r.competitions_not_found,
			'competitionsAmbiguous', r.competitions_ambiguous,
			'windowHours', r.window_h,
			'runHours', r.run_hours)),
		'record_suspensions.py'
	from public.capture_run r
	on conflict (source_key) do nothing;
end
$$;
