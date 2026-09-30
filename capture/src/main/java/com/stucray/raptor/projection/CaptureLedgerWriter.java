package com.stucray.raptor.projection;

import com.stucray.raptor.datasource.Acquisition;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Derives {@code ledger.capture_session} from what the recorder wrote down.
 *
 * <p>Pure SQL, and deliberately so: every column is an aggregate over
 * {@code raw}, and a Java pass that read the rows out to count them would be
 * slower, longer and no more trustworthy. Nothing here parses anything — the
 * ledger is about the <em>session</em>, not about what was in the messages.
 *
 * <p><b>Counted from the messages, never from what the recorder claimed.</b>
 * {@code raw.capture_session.exit_detail} carries the recorder's own
 * "framed=767587 written=767587", and the old ledger's {@code msgs} came from
 * the Python's log line. Both are self-reports. Counting the rows instead means
 * a session that reported a number it did not deliver shows up as a difference
 * rather than as agreement with itself.
 *
 * <h2>Why this is incremental, and what that cost (#261)</h2>
 *
 * <p>It used to replace both tables wholesale every run, which meant two
 * aggregates over every row of {@code raw.stream_message} that carries a
 * session — 14.8M rows of a 34.1M-row, 13 GB table, growing with every
 * capture. On a five-minute timer against a pool whose {@code
 * socketTimeout} is 30s, the step reached a 26s average and started losing runs
 * to the driver killing the connection mid-statement. Measured breakdown of that
 * scan: {@code count(*)} 5.3s, {@code count(distinct market_id)} <b>14.2s</b>,
 * the {@code con} filter 2.7s. The expensive part was reducing 14.8M market ids
 * to 1,611 distinct ones — not, as first supposed, reading inside the payload.
 *
 * <p>So it now recomputes only what can have changed. <b>A closed session's
 * aggregates are immutable:</b> raw is append-only and nothing ever writes that
 * session's id again, so the row derived once stays correct forever. Same for a
 * market that has left scope. Everything else is kept, and the run touches the
 * one open session instead of all eighty-three.
 *
 * <p>Two things make that safe rather than merely faster:
 *
 * <ul>
 * <li><b>A spilled message can arrive hours after its session closed, and that
 * is what actually threatens this.</b> When Postgres cannot take a write the
 * recorder spills to disk, and {@code SpillDrain} replays the file later —
 * carrying the original {@code session_id} into a session that has long since
 * ended. It has happened: {@code raw.spill_file} id 1, session 45, spilled
 * 2026-09-05T09:30:54Z against a session that closed two seconds earlier, and
 * {@code ingested_at} 2026-09-06T04:17:18Z — <b>18.8 hours later</b>. So the
 * guard is not a time window but the record itself: a session is stale while it
 * has a spill file ingested after the projection run that derived its row.
 * {@code raw.spill_file} is a handful of rows, so asking costs nothing.
 * <li><b>Reached through the session key, and through nothing else.</b> The
 * table's only indexes are the ones its uniqueness needs (#33), and {@code
 * unique (session_id, seq, market_id)} leads with the session. Both aggregates
 * select by session id, so a run reads the stale sessions' rows and no others.
 * Until #33 the table was partitioned by {@code pt} and both queries carried a
 * {@code pt} floor to prune it; with no partitions there is nothing to prune and
 * no floor.
 * </ul>
 *
 * <p><b>The contract is unchanged where it matters.</b> Every row is still a
 * pure function of {@code raw}, and {@link #write(long, boolean)} with {@code
 * full} re-derives all of it from nothing — which is what to run when this class
 * changes, because an incremental pass by definition will not revisit a row
 * whose <em>recipe</em> moved. Rows keep the {@code ledger_run_id} of the run
 * that actually derived them, so the ledger says when each number was computed
 * rather than when it was last copied.
 */
@Component
class CaptureLedgerWriter {

	/**
	 * How long after a session ends, or a market leaves scope, it is still
	 * recomputed.
	 *
	 * <p><b>Belt and braces, not the mechanism.</b> The first version of this said
	 * the grace existed because the writer COPYs in batches and a message could
	 * land after {@code ended_at} was stamped. That is wrong, and
	 * {@code Recording.close()} is explicit about why: it stops the read loop,
	 * joins it, stops the writer, and <em>joins the writer — which exits only once
	 * the queue is empty</em> — and only then calls {@code endSession()}. The
	 * batches commit before the close does, on a different connection but in that
	 * order, so no consistent snapshot can see {@code ended_at} set without also
	 * seeing them. The race the grace was written for does not exist.
	 *
	 * <p>What does exist is spill replay, which arrives hours later and is guarded
	 * exactly, by {@code spill_file.ingested_at} — see the class comment. This
	 * window is kept anyway because it is nearly free (one extra session recomputed
	 * per card, ~770ms) and because it covers the residue of reasoning like the
	 * above being wrong again. It is deliberately not load-bearing: if it were
	 * deleted the ledger would still be correct, and if a fifteen-minute window
	 * were ever the only thing standing between this and a wrong number, that would
	 * be the bug.
	 *
	 * <p><b>It was, once, and it was (paddock#337).</b> A session's ending reached
	 * the ledger only through this window, and a session ends when the recorder
	 * stops — which is when this in-process refresh stops too. Seven sessions on
	 * the live database ended with no refresh inside fifteen minutes and stayed
	 * "still running" in the ledger for days. The stale queries now also compare
	 * each row with its source, so the claim above holds again.
	 */
	private static final String FREEZE_GRACE = "15 minutes";

	/**
	 * The aggregate for one run's worth of sessions, read through the session key.
	 */
	private static final String PROJECT_SESSIONS_SQL = """
			insert into ledger.capture_session (
				session_id, source_key, started_at, ended_at, origin, exit_status,
				exit_detail, build_version, config_json, markets, messages, conflated,
				first_message_at, last_message_at, gap_count, gap_total_ms, max_gap_ms,
				gaps_sleep, gaps_silence, gaps_disconnect, ledger_run_id)
			select s.id, s.source_key, s.started_at, s.ended_at, s.origin, s.exit_status,
				s.exit_detail, s.build_version, s.config_json,
				coalesce(m.markets, 0), coalesce(m.messages, 0),
				coalesce(m.conflated, 0), m.first_message_at, m.last_message_at,
				coalesce(g.gap_count, 0), coalesce(g.gap_total_ms, 0),
				coalesce(g.max_gap_ms, 0), coalesce(g.gaps_sleep, 0),
				coalesce(g.gaps_silence, 0), coalesce(g.gaps_disconnect, 0),
				:ledgerRunId
			from raw.capture_session s
			left join (
				select session_id,
					count(distinct market_id) as markets,
					count(*)                  as messages,
					-- The one thing here that reads inside a message, and it is not
					-- parsing: `con` is the server's own statement about DELIVERY —
					-- that it merged several changes into this envelope and kept only
					-- the last — which is a property of the session, not of the book.
					-- Nothing else can see it: a conflated message is not a gap, not a
					-- silence and not a disconnect (#132).
					count(*) filter (where payload ->> 'con' = 'true') as conflated,
					-- OUR clock, not Betfair's. The ledger says what the recorder did,
					-- and a session's span is measured by when it received rather than
					-- by when the exchange published.
					min(received_at)          as first_message_at,
					max(received_at)          as last_message_at
				from raw.stream_message
				where session_id in (:sessionIds)
				group by session_id
			) m on m.session_id = s.id
			left join (
				select session_id,
					count(*) as gap_count,
					sum((extract(epoch from (ended_at - started_at)) * 1000)::bigint)
						as gap_total_ms,
					max((extract(epoch from (ended_at - started_at)) * 1000)::bigint)
						as max_gap_ms,
					count(*) filter (where cause = 'SLEEP')      as gaps_sleep,
					count(*) filter (where cause = 'SILENCE')    as gaps_silence,
					count(*) filter (where cause = 'DISCONNECT') as gaps_disconnect
				from raw.capture_gap
				where session_id in (:sessionIds)
				group by session_id
			) g on g.session_id = s.id
			where s.id in (:sessionIds)""";


	/**
	 * Scope, with what each market actually produced.
	 *
	 * <p>The message count is computed here rather than kept on
	 * {@code raw.market_scope}: a counter the recorder maintained as it went
	 * would be one more self-report, and the whole point of this ledger is that
	 * its numbers can be checked against the rows. It is what makes "subscribed
	 * and silent" a fact instead of an inference.
	 *
	 * <p><b>Counted over the sessions that ran while the market was in scope</b>
	 * (raptor#32), not over every session row carrying its id: a session whose span
	 * overlaps the market's, from {@code first_seen_at} to the {@code
	 * state_changed_at} that took it to {@code DONE}, or open-ended if it has not
	 * left. It is the question scope actually asks — what did the recorder get while
	 * it was meant to be recording this market — and it reaches the rows through the
	 * session, the key capture writes by, rather than through a market index. Each
	 * message has one session and each market one scope row, so no row is counted
	 * twice. On 2026-09-30 it agreed with the per-market count it replaced for all
	 * 2,866 scoped markets: 2,762 equal, 104 with nothing under either.
	 */
	private static final String PROJECT_SCOPE_SQL = """
			with w as (
				select sc.market_id, cs.id as session_id
				from raw.market_scope sc
				join raw.capture_session cs
					on cs.started_at <= case when sc.state = 'DONE' then sc.state_changed_at
							else cast('infinity' as timestamptz) end
					and coalesce(cs.ended_at, cast('infinity' as timestamptz))
						>= sc.first_seen_at
				where sc.market_id in (:marketIds)
			)
			insert into ledger.market_scope (
				market_id, event_id, event_name, competition_id, competition_name,
				market_type, country_code, kickoff, requested, state, exit_reason,
				first_seen_at, state_changed_at, in_play_since, messages, ledger_run_id)
			select s.market_id, s.event_id, s.event_name, s.competition_id,
				s.competition_name, s.market_type, s.country_code, s.kickoff,
				s.requested, s.state, s.exit_reason, s.first_seen_at,
				s.state_changed_at, s.in_play_since,
				coalesce(m.messages, 0), :ledgerRunId
			from raw.market_scope s
			left join (
				select w.market_id, cast(sum(c.n) as bigint) as messages
				from w
				join (
					select r.session_id, r.market_id, count(*) as n
					from raw.stream_message r
					-- An ARRAY, not `in (select ...)`, and the difference is the whole
					-- cost. The session key is (session_id, seq, market_id): given the
					-- sessions as an array, each is a range scan of its own entries
					-- with the markets filtered inside it. Given a subquery, or only
					-- the market list, the planner walks the entire 2.4 GB index,
					-- because market_id is its third column. Rehearsed on a restored
					-- backup (#33, 51.3M rows, 40 markets of one weekend): 455ms this
					-- way, 6.4s and 17.7s the other two, with identical counts.
					where r.session_id = any (array(select distinct session_id from w))
						and r.market_id in (:marketIds)
					group by r.session_id, r.market_id
				) c on c.session_id = w.session_id and c.market_id = w.market_id
				group by w.market_id
			) m on m.market_id = s.market_id
			where s.market_id in (:marketIds)""";

	/**
	 * The gap intervals, so the read side can ask whether a gap overlapped a
	 * market that was in play. It cannot reach {@code raw} to find out — it holds
	 * no grant there at all — and a count alone cannot answer it.
	 */
	private static final String PROJECT_GAPS_SQL = """
			insert into ledger.capture_gap
				(id, session_id, started_at, ended_at, cause, detail, ledger_run_id)
			select g.id, g.session_id, g.started_at, g.ended_at, g.cause, g.detail,
				:ledgerRunId
			from raw.capture_gap g""";

	/**
	 * The sessions whose aggregates can still change.
	 *
	 * <p>A session qualifies if it is open, if it closed recently enough to still
	 * be draining, or if it has no projected row at all — which covers both a new
	 * session and the first run after a full rebuild.
	 */
	private static final String STALE_SESSIONS_SQL = """
			select s.id
			from raw.capture_session s
			where s.ended_at is null
				or s.ended_at > now() - cast(:grace as interval)
				or not exists (
					select 1 from ledger.capture_session q where q.session_id = s.id)
				-- The row disagrees with its source: most often a session that ended
				-- while nothing refreshed, because the refresh runs in the process
				-- whose stopping is what ended it (paddock#337). Every column the
				-- ledger copies verbatim is compared, so no window has to guess when
				-- raw last moved.
				or exists (
					select 1 from ledger.capture_session q
					where q.session_id = s.id
						and (q.source_key, q.started_at, q.ended_at, q.origin, q.exit_status,
								q.exit_detail, q.build_version, q.config_json)
							is distinct from
							(s.source_key, s.started_at, s.ended_at, s.origin, s.exit_status,
								s.exit_detail, s.build_version, s.config_json))
				-- A spill replayed after this row was derived. THIS is the guard that
				-- matters for the counts; see FREEZE_GRACE.
				or exists (
					select 1
					from raw.spill_file f
					join ledger.capture_session q on q.session_id = s.id
					join ledger.ledger_run p on p.id = q.ledger_run_id
					where f.session_id = s.id
						and f.ingested_at > p.started_at)""";

	/** Every session, for a full rebuild. */
	private static final String ALL_SESSIONS_SQL = "select s.id from raw.capture_session s";

	/**
	 * The markets whose message count can still change.
	 *
	 * <p>{@code DONE} is the only terminal state, and the database enforces that
	 * it is exactly the states carrying an {@code exit_reason}, so this is the
	 * whole of "has left scope" rather than a guess at it.
	 */
	private static final String STALE_MARKETS_SQL = """
			select s.market_id
			from raw.market_scope s
			where s.state <> 'DONE'
				or s.state_changed_at > now() - cast(:grace as interval)
				or not exists (
					select 1 from ledger.market_scope q where q.market_id = s.market_id)
				-- The row disagrees with its source: a market that left scope while
				-- nothing refreshed would otherwise stay as it was last derived
				-- (paddock#337, the same hole as the sessions').
				or exists (
					select 1 from ledger.market_scope q
					where q.market_id = s.market_id
						and (q.event_id, q.event_name, q.competition_id, q.competition_name,
								q.market_type, q.country_code, q.kickoff, q.requested, q.state,
								q.exit_reason, q.first_seen_at, q.state_changed_at, q.in_play_since)
							is distinct from
							(s.event_id, s.event_name, s.competition_id, s.competition_name,
								s.market_type, s.country_code, s.kickoff, s.requested, s.state,
								s.exit_reason, s.first_seen_at, s.state_changed_at, s.in_play_since))
				-- Any spill replayed after this row was derived. Not correlated to the
				-- market, because `raw.spill_file` records the session and one file can
				-- carry messages for every market that session held — so the honest
				-- answer is "this count may have moved", and being over-eager here
				-- costs one slow run after an outage rather than a wrong number
				-- forever.
				or exists (
					select 1
					from raw.spill_file f
					join ledger.market_scope q on q.market_id = s.market_id
					join ledger.ledger_run p on p.id = q.ledger_run_id
					where f.ingested_at > p.started_at)""";

	/** Every scoped market, for a full rebuild. */
	private static final String ALL_MARKETS_SQL = "select s.market_id from raw.market_scope s";

	private final JdbcClient jdbc;

	CaptureLedgerWriter(@Acquisition JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Re-derive what can have changed, in one transaction.
	 *
	 * <p>Both tables are written together because they are read together: the
	 * verdict asks what the recorder was for and what it did in the same breath,
	 * and a screen built from a fresh scope beside a stale session ledger would be
	 * quietly wrong in a way neither table could show on its own.
	 *
	 * <p>The gap table is replaced wholesale regardless. It is a copy rather than
	 * an aggregate, it is a few dozen rows, and {@code raw.capture_gap} has no
	 * marker for "recently written" to select on — so incrementality would cost a
	 * column to buy nothing measurable.
	 *
	 * @param full re-derive every row instead of only the live ones. Use it when
	 *     this class changes: an incremental pass will not revisit a row whose
	 *     recipe moved, so a logic change needs one full run to take effect.
	 * @return sessions and scoped markets written — the stale ones, under {@code
	 *     full == false}, not the table's size
	 */
	Written write(long ledgerRunId, boolean full) {
		List<Long> staleSessions = jdbc.sql(full ? ALL_SESSIONS_SQL : STALE_SESSIONS_SQL)
				.param("grace", FREEZE_GRACE)
				.query(Long.class)
				.list();
		List<String> staleMarkets = jdbc.sql(full ? ALL_MARKETS_SQL : STALE_MARKETS_SQL)
				.param("grace", FREEZE_GRACE)
				.query(String.class)
				.list();

		if (full) {
			// The rebuild's own reason to exist: rows whose recipe changed have to
			// go even when their inputs did not, and a stale session no longer in
			// raw at all would otherwise survive forever.
			jdbc.sql("delete from ledger.capture_session").update();
			jdbc.sql("delete from ledger.market_scope").update();
		}
		jdbc.sql("delete from ledger.capture_gap").update();

		long sessions = 0;
		if (!staleSessions.isEmpty()) {
			if (!full) {
				jdbc.sql("delete from ledger.capture_session where session_id in (:sessionIds)")
						.param("sessionIds", staleSessions)
						.update();
			}
			sessions = jdbc.sql(PROJECT_SESSIONS_SQL)
					.param("ledgerRunId", ledgerRunId)
					.param("sessionIds", staleSessions)
					.update();
		}

		long scoped = 0;
		if (!staleMarkets.isEmpty()) {
			if (!full) {
				jdbc.sql("delete from ledger.market_scope where market_id in (:marketIds)")
						.param("marketIds", staleMarkets)
						.update();
			}
			scoped = jdbc.sql(PROJECT_SCOPE_SQL)
					.param("ledgerRunId", ledgerRunId)
					.param("marketIds", staleMarkets)
					.update();
		}

		long gaps = jdbc.sql(PROJECT_GAPS_SQL).param("ledgerRunId", ledgerRunId).update();
		return new Written(sessions, scoped, gaps);
	}

	record Written(long sessions, long scopedMarkets, long gaps) {}

	/** Opens a projection run, returning its id. */
	long begin(String jobName, String partitionKey, long jobExecutionId) {
		return jdbc.sql("""
						insert into ledger.ledger_run (job_name, partition_key, job_execution_id)
						values (?, ?, ?)
						returning id""")
				.params(jobName, partitionKey, jobExecutionId)
				.query(Long.class)
				.single();
	}

	/**
	 * Closes the projection run.
	 *
	 * <p>{@code markets}, {@code ticks} and {@code transitions} are left null.
	 * They are shaped for a projection whose product is markets, and a ledger's
	 * product is sessions; writing the session count into {@code markets} would
	 * make that column mean two different things depending on which job wrote the
	 * row, which is the kind of overload that is free to add and expensive to
	 * read. What this run produced is countable directly from
	 * {@code ledger.capture_session}.
	 */
	void complete(long ledgerRunId) {
		jdbc.sql("""
						update ledger.ledger_run
						set completed_at = clock_timestamp()
						where id = ?""")
				.param(ledgerRunId)
				.update();
	}
}
