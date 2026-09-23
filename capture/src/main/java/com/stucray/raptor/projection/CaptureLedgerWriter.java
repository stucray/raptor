package com.stucray.raptor.projection;

import com.stucray.raptor.datasource.Acquisition;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Derives {@code query.capture_session} from what the recorder wrote down.
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
 * session — 14.8M rows of a 34.1M-row, 13 GB, 103-partition table, growing with
 * every capture. On a five-minute timer against a pool whose {@code
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
 * <li><b>Partition pruning by {@code pt}, not a new index.</b> The table is
 * {@code range (pt)} by month and has no index on {@code session_id} at all, so
 * {@code where session_id in (…)} alone would still read everything. Bounding
 * {@code pt} below by the stale sessions' own month prunes to a partition or
 * two. Verified against all 83 sessions: the smallest gap between a session's
 * first message {@code pt} and its {@code started_at} is <b>+0.15s</b> — no
 * session has ever received a message published before it began — and truncating
 * to the month leaves a month of slack on top of that.
 * </ul>
 *
 * <p>The scope half needs the {@code pt} bound too, which is worth stating
 * because it reads as though it should not: {@code (market_id, pt)} is indexed,
 * and restricting to sixty market ids still got a parallel sequential scan over
 * all 103 partitions until the time bound was added. An index on the leading
 * column is not the same thing as the planner electing to use it.
 *
 * <p><b>The contract is unchanged where it matters.</b> Every row is still a
 * pure function of {@code raw}, and {@link #write(long, boolean)} with {@code
 * full} re-derives all of it from nothing — which is what to run when this class
 * changes, because an incremental pass by definition will not revisit a row
 * whose <em>recipe</em> moved. Rows keep the {@code projection_id} of the run
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
	 */
	private static final String FREEZE_GRACE = "15 minutes";

	/**
	 * The aggregate for one run's worth of sessions.
	 *
	 * <p>{@code :ptFloor} is what makes this cheap — see the class comment. It is
	 * a bind parameter rather than a scalar subquery so that pruning is decided
	 * from a value the executor has in hand.
	 */
	private static final String PROJECT_SESSIONS_SQL = """
			insert into query.capture_session (
				session_id, source_key, started_at, ended_at, origin, exit_status,
				exit_detail, build_version, config_json, markets, messages, conflated,
				first_message_at, last_message_at, gap_count, gap_total_ms, max_gap_ms,
				gaps_sleep, gaps_silence, gaps_disconnect, projection_id)
			select s.id, s.source_key, s.started_at, s.ended_at, s.origin, s.exit_status,
				s.exit_detail, s.build_version, s.config_json,
				coalesce(m.markets, 0), coalesce(m.messages, 0),
				coalesce(m.conflated, 0), m.first_message_at, m.last_message_at,
				coalesce(g.gap_count, 0), coalesce(g.gap_total_ms, 0),
				coalesce(g.max_gap_ms, 0), coalesce(g.gaps_sleep, 0),
				coalesce(g.gaps_silence, 0), coalesce(g.gaps_disconnect, 0),
				:projectionId
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
					-- Prunes the partitions. Without it this reads all 103 of them
					-- looking for a column no index covers; see the class comment
					-- for why the bound is safe.
					and pt >= :ptFloor
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
	 * <p>Bounded by {@code pt} for the same reason as the sessions aggregate, and
	 * the reason is worth recording because the obvious expectation is wrong:
	 * {@code (market_id, pt)} <em>is</em> indexed, but with sixty ids and no time
	 * bound the planner still chooses a parallel sequential scan across all 103
	 * partitions — measured at 1.69s and 523k buffers read. Adding the floor
	 * prunes it to a bitmap scan over the recent partitions: 602ms, 322k buffers.
	 * An index on the leading column is not the same thing as the planner electing
	 * to use it.
	 *
	 * <p>The floor comes from {@code first_seen_at}, which is {@code not null}, so
	 * it needs no fallback. The {@code session_id is not null} guard stays — it
	 * excludes the historic corpus, which shares the id space and is not what
	 * scope counts.
	 */
	private static final String PROJECT_SCOPE_SQL = """
			insert into query.market_scope (
				market_id, event_id, event_name, competition_id, competition_name,
				market_type, country_code, kickoff, requested, state, exit_reason,
				first_seen_at, state_changed_at, in_play_since, messages, projection_id)
			select s.market_id, s.event_id, s.event_name, s.competition_id,
				s.competition_name, s.market_type, s.country_code, s.kickoff,
				s.requested, s.state, s.exit_reason, s.first_seen_at,
				s.state_changed_at, s.in_play_since,
				coalesce(m.messages, 0), :projectionId
			from raw.market_scope s
			left join (
				select market_id, count(*) as messages
				from raw.stream_message
				where market_id in (:marketIds)
					and session_id is not null
					-- Not redundant with the index; see this constant's javadoc.
					and pt >= :ptFloor
				group by market_id
			) m on m.market_id = s.market_id
			where s.market_id in (:marketIds)""";

	/**
	 * The gap intervals, so the read side can ask whether a gap overlapped a
	 * market that was in play. It cannot reach {@code raw} to find out — it holds
	 * no grant there at all — and a count alone cannot answer it.
	 */
	private static final String PROJECT_GAPS_SQL = """
			insert into query.capture_gap
				(id, session_id, started_at, ended_at, cause, detail, projection_id)
			select g.id, g.session_id, g.started_at, g.ended_at, g.cause, g.detail,
				:projectionId
			from raw.capture_gap g""";

	/**
	 * The sessions whose aggregates can still change, with the {@code started_at}
	 * that sets the partition floor.
	 *
	 * <p>A session qualifies if it is open, if it closed recently enough to still
	 * be draining, or if it has no projected row at all — which covers both a new
	 * session and the first run after a full rebuild.
	 */
	private static final String STALE_SESSIONS_SQL = """
			select s.id, s.started_at
			from raw.capture_session s
			where s.ended_at is null
				or s.ended_at > now() - cast(:grace as interval)
				or not exists (
					select 1 from query.capture_session q where q.session_id = s.id)
				-- A spill replayed after this row was derived. THIS is the guard that
				-- matters; see FREEZE_GRACE.
				or exists (
					select 1
					from raw.spill_file f
					join query.capture_session q on q.session_id = s.id
					join query.projection p on p.id = q.projection_id
					where f.session_id = s.id
						and f.ingested_at > p.started_at)""";

	/** Every session, for a full rebuild. */
	private static final String ALL_SESSIONS_SQL =
			"select s.id, s.started_at from raw.capture_session s";

	/**
	 * The markets whose message count can still change.
	 *
	 * <p>{@code DONE} is the only terminal state, and the database enforces that
	 * it is exactly the states carrying an {@code exit_reason}, so this is the
	 * whole of "has left scope" rather than a guess at it.
	 */
	private static final String STALE_MARKETS_SQL = """
			select s.market_id, s.first_seen_at
			from raw.market_scope s
			where s.state <> 'DONE'
				or s.state_changed_at > now() - cast(:grace as interval)
				or not exists (
					select 1 from query.market_scope q where q.market_id = s.market_id)
				-- Any spill replayed after this row was derived. Not correlated to the
				-- market, because `raw.spill_file` records the session and one file can
				-- carry messages for every market that session held — so the honest
				-- answer is "this count may have moved", and being over-eager here
				-- costs one slow run after an outage rather than a wrong number
				-- forever.
				or exists (
					select 1
					from raw.spill_file f
					join query.market_scope q on q.market_id = s.market_id
					join query.projection p on p.id = q.projection_id
					where f.ingested_at > p.started_at)""";

	/** Every scoped market, for a full rebuild. */
	private static final String ALL_MARKETS_SQL =
			"select s.market_id, s.first_seen_at from raw.market_scope s";

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
	Written write(long projectionId, boolean full) {
		List<StaleSession> staleSessions = jdbc.sql(full ? ALL_SESSIONS_SQL : STALE_SESSIONS_SQL)
				.param("grace", FREEZE_GRACE)
				.query((rs, row) -> new StaleSession(
						rs.getLong("id"), rs.getObject("started_at", OffsetDateTime.class)))
				.list();
		List<StaleMarket> staleMarkets = jdbc.sql(full ? ALL_MARKETS_SQL : STALE_MARKETS_SQL)
				.param("grace", FREEZE_GRACE)
				.query((rs, row) -> new StaleMarket(rs.getString("market_id"),
						rs.getObject("first_seen_at", OffsetDateTime.class)))
				.list();

		if (full) {
			// The rebuild's own reason to exist: rows whose recipe changed have to
			// go even when their inputs did not, and a stale session no longer in
			// raw at all would otherwise survive forever.
			jdbc.sql("delete from query.capture_session").update();
			jdbc.sql("delete from query.market_scope").update();
		}
		jdbc.sql("delete from query.capture_gap").update();

		long sessions = 0;
		if (!staleSessions.isEmpty()) {
			List<Long> ids = staleSessions.stream().map(StaleSession::id).toList();
			if (!full) {
				jdbc.sql("delete from query.capture_session where session_id in (:sessionIds)")
						.param("sessionIds", ids)
						.update();
			}
			sessions = jdbc.sql(PROJECT_SESSIONS_SQL)
					.param("projectionId", projectionId)
					.param("sessionIds", ids)
					.param("ptFloor", monthFloor(
							staleSessions.stream().map(StaleSession::startedAt).toList()))
					.update();
		}

		long scoped = 0;
		if (!staleMarkets.isEmpty()) {
			List<String> ids = staleMarkets.stream().map(StaleMarket::marketId).toList();
			if (!full) {
				jdbc.sql("delete from query.market_scope where market_id in (:marketIds)")
						.param("marketIds", ids)
						.update();
			}
			scoped = jdbc.sql(PROJECT_SCOPE_SQL)
					.param("projectionId", projectionId)
					.param("marketIds", ids)
					.param("ptFloor", monthFloor(staleMarkets.stream()
							.map(StaleMarket::firstSeenAt).toList()))
					.update();
		}

		long gaps = jdbc.sql(PROJECT_GAPS_SQL).param("projectionId", projectionId).update();
		return new Written(sessions, scoped, gaps);
	}

	/**
	 * The lower bound on {@code pt} for the stale sessions' messages, truncated to
	 * the start of the earliest one's month.
	 *
	 * <p>Truncating rather than using {@code started_at} itself is the whole
	 * safety margin: a message's {@code pt} is Betfair's publish time and the
	 * session's start is ours, and while no session in the corpus has ever
	 * received a message published before it began — the smallest observed gap is
	 * +0.15s — a bound that depended on that staying true would undercount
	 * silently if it ever stopped. The partitions are monthly, so the slack is
	 * free: it admits at most one extra partition.
	 *
	 * <p>Returned as {@link OffsetDateTime} because pgjdbc cannot infer a SQL type
	 * for {@code Instant}.
	 */
	private static OffsetDateTime monthFloor(List<OffsetDateTime> starts) {
		return starts.stream()
				.min(OffsetDateTime::compareTo)
				.orElseThrow()
				.withOffsetSameInstant(ZoneOffset.UTC)
				.withDayOfMonth(1)
				.truncatedTo(ChronoUnit.DAYS);
	}

	/** A session to re-derive, with what its partition floor is computed from. */
	private record StaleSession(long id, OffsetDateTime startedAt) {}

	/** A scoped market to re-derive, likewise. */
	private record StaleMarket(String marketId, OffsetDateTime firstSeenAt) {}

	record Written(long sessions, long scopedMarkets, long gaps) {}

	/** Opens a projection run, returning its id. */
	long begin(String jobName, String partitionKey, long jobExecutionId) {
		return jdbc.sql("""
						insert into query.projection (job_name, partition_key, job_execution_id)
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
	 * {@code query.capture_session}.
	 */
	void complete(long projectionId) {
		jdbc.sql("""
						update query.projection
						set completed_at = clock_timestamp()
						where id = ?""")
				.param(projectionId)
				.update();
	}
}
