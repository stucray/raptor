-- The capture ledger, recorded rather than reconstructed.
--
-- Its ancestor, `public.capture_run`, was derived by `capture_runs.py` parsing a
-- launchd log and the `captured/` directory — 539 lines inferring what happened
-- from what was left behind. Reading only `captured/` is what once made a
-- 391-market night invisible to the ledger (#65). A resident recorder knows what
-- it did and writes it down.
--
-- A ROW IS A SESSION, NOT A NIGHT, and that is the substantive change.
--
-- `public.capture_run`'s unit was one launchd invocation, because that is what a
-- capture WAS: a 12-hour window fired at a fixed hour. S6 abolished it. The
-- recorder is resident and has no schedule — `raw.market_scope`'s own comment
-- says it "is what replaces the capture WINDOW" — so a night is no longer a
-- thing the recorder does. It is a thing that happens to the fixture list.
--
-- Aggregating sessions into nights to keep the old shape would mean inventing a
-- boundary the recorder does not have: captures cross midnight, the old runs ran
-- 11:00Z to 11:00Z, and the shadow night alone opened eight sessions in fourteen
-- hours. So the ledger records sessions, and the question "was a night lost" is
-- asked of scope, which can answer it — see `query.market_scope`.
--
-- Rebuildable like every other projection: every row here is a function of
-- `raw.capture_session`, `raw.capture_gap` and `raw.stream_message`.

create table query.capture_session (
	-- The raw session's own id. A natural key, not a surrogate: re-projecting
	-- must land on the same row, and the recorder already minted an identity.
	session_id       bigint primary key,
	-- The run key an IMPORTED session was identified by in the launchd log, and
	-- null for every session the resident recorder opened for itself. The read
	-- side needs to be able to tell the two eras apart: an imported session's
	-- times and exit status were reconstructed by a log parser, and a resident
	-- one's were written down as they happened.
	source_key       text,

	started_at       timestamptz not null,
	ended_at         timestamptz,
	origin           text        not null,
	exit_status      text,
	exit_detail      text,
	build_version    text        not null,
	config_json      jsonb       not null,

	-- What the session actually received. Counted from the messages themselves
	-- rather than taken from anything the recorder claimed, so a session that
	-- reported a number it did not deliver is visible as a difference.
	markets          integer     not null,
	messages         bigint      not null,
	first_message_at timestamptz,
	last_message_at  timestamptz,

	-- What the recorder knows it lost. Distinct from a gap the parser infers
	-- from message silence: a quiet pre-match book produces the second without
	-- the first, which is why neither can be derived from the other, and why
	-- the old ledger's four disagreeing gap counts were guesses.
	gap_count        integer     not null,
	gap_total_ms     bigint      not null,
	max_gap_ms       bigint      not null,
	-- By cause, because the causes are not equivalent: a SLEEP is the laptop,
	-- a DISCONNECT is the stream, and a SILENCE is what macOS sleep leaves
	-- behind. This is what replaces gap_network / gap_quiet_book /
	-- gap_no_witness, which were the simultaneity method GUESSING at the same
	-- distinction from the outside.
	gaps_sleep       integer     not null,
	gaps_silence     integer     not null,
	gaps_disconnect  integer     not null,

	projection_id    bigint      not null references query.projection
);

create index capture_session_started_idx on query.capture_session (started_at desc);

comment on table query.capture_session is
	'One row per recorder session: what it was configured to do, when it ran, '
	'how it ended, what it received and what it knows it lost. Replaces '
	'public.capture_run, whose oracle was a launchd log rather than the '
	'recorder itself.';
