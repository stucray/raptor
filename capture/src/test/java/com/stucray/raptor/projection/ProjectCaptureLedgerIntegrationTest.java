package com.stucray.raptor.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The capture ledger, against a real PostgreSQL.
 *
 * <p>Every expectation is written against rows this test inserts itself, so the
 * aggregates are checked against arithmetic done by hand rather than against
 * what the projection happens to compute.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProjectCaptureLedgerIntegrationTest {

	private static final Instant START = Instant.parse("2026-09-02T14:00:00Z");

	@Autowired JobOperator jobOperator;
	@Autowired Job projectCaptureLedgerJob;
	@Autowired @Acquisition JdbcClient jdbc;

	private long recorded;
	private long empty;

	@BeforeEach
	void seed() {
		// A session that received, with three gaps of 1s / 2s / 4s, one of each cause.
		recorded = session(null, START, START.plusSeconds(3600), "RESIDENT", "COMPLETED",
				"framed=99999 written=99999");
		message(recorded, "1.1", START.plusSeconds(10));
		message(recorded, "1.1", START.plusSeconds(20), true);
		message(recorded, "1.2", START.plusSeconds(30));
		gap(recorded, START.plusSeconds(100), 1, "SLEEP");
		gap(recorded, START.plusSeconds(200), 2, "SILENCE");
		gap(recorded, START.plusSeconds(300), 4, "DISCONNECT");

		// A session that connected and received nothing, which is a real outcome
		// and not an absence: it is what an idle scope looks like.
		empty = session("run-x", START.plusSeconds(7200), null, "SCHEDULED", null, null);
	}

	/**
	 * A market that was subscribed and produced nothing is the lost fixture, and
	 * the signal that replaces "no run has started since the last due fire hour".
	 *
	 * <p>The old rule could only say a RUN had not started. This says a FIXTURE
	 * was not captured, which is the thing anybody actually cares about and which
	 * keeps meaning something once nothing fires at any hour.
	 */
	@Test
	void countsWhatEachScopedMarketProduced() throws Exception {
		scoped("1.1", true, "DONE", "CLOSED");
		scoped("1.9", true, "DONE", "KICKOFF_ELAPSED");
		scoped("1.5", false, "LIVE", null);

		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(scopeMessages("1.1")).as("received two messages").isEqualTo(2);
		assertThat(scopeMessages("1.9")).as("subscribed and silent: a lost fixture").isZero();
		assertThat(count("""
				select count(*) from ledger.market_scope
				where state = 'DONE' and requested and messages = 0"""))
				.isEqualTo(1);
		assertThat(count("select count(*) from ledger.market_scope")).isEqualTo(3);
	}

	@Test
	void countsWhatEachSessionReceivedAndLost() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(one("markets", recorded)).isEqualTo(2);
		assertThat(one("messages", recorded)).isEqualTo(3);
		// Conflation is not loss and leaves no gap row: the message arrived, on
		// time and complete, having merged away the states in between. Counted
		// here because nothing else on the ledger can see it (#132).
		assertThat(one("conflated", recorded)).isEqualTo(1);
		assertThat(one("gap_count", recorded)).isEqualTo(3);
		assertThat(one("gap_total_ms", recorded)).isEqualTo(7000);
		assertThat(one("max_gap_ms", recorded)).isEqualTo(4000);
		assertThat(one("gaps_sleep", recorded)).isEqualTo(1);
		assertThat(one("gaps_silence", recorded)).isEqualTo(1);
		assertThat(one("gaps_disconnect", recorded)).isEqualTo(1);

		// The session's span is measured by OUR clock at receipt, not Betfair's.
		assertThat(instant("first_message_at", recorded)).isEqualTo(START.plusSeconds(10));
		assertThat(instant("last_message_at", recorded)).isEqualTo(START.plusSeconds(30));
	}

	/**
	 * A session that received nothing is a row, not a missing row.
	 *
	 * <p>Its ancestor could not say this: {@code capture_runs.py} read the
	 * {@code captured/} directory, so a night that produced no files and a night
	 * that never ran looked identical — which is how a 391-market night went
	 * missing from the ledger (#65).
	 */
	@Test
	void aSessionThatReceivedNothingIsStillARow() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(one("markets", empty)).isZero();
		assertThat(one("messages", empty)).isZero();
		assertThat(one("gap_count", empty)).isZero();
		assertThat(instant("first_message_at", empty)).isNull();
		assertThat(jdbc.sql("select source_key from ledger.capture_session where session_id = ?")
				.param(empty).query(String.class).single())
				.as("the era is legible: an imported session kept its run key")
				.isEqualTo("run-x");
	}

	/**
	 * The counts come from the messages, never from what the recorder claimed.
	 *
	 * <p>The seeded session's {@code exit_detail} says it wrote 99,999. Three
	 * rows are actually there. A ledger that took the self-report would agree
	 * with the recorder about a number the database can disprove.
	 */
	@Test
	void doesNotBelieveTheRecordersOwnCount() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(jdbc.sql("select exit_detail from ledger.capture_session where session_id = ?")
				.param(recorded).query(String.class).single())
				.contains("99999");
		assertThat(one("messages", recorded)).isEqualTo(3);
	}

	/** Re-projecting replaces, and leaves one row per session. */
	@Test
	void reProjectingReplaces() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(count("select count(*) from ledger.capture_session")).isEqualTo(2);
		assertThat(count("select count(*) from ledger.ledger_run where job_name = '"
				+ ProjectCaptureLedgerJobConfig.JOB_NAME + "' and completed_at is not null"))
				.as("both runs are in the ledger of projections")
				.isEqualTo(2);
	}

	/**
	 * The second run re-derives the open session and leaves the closed one alone
	 * (#261).
	 *
	 * <p>Asserted on {@code ledger_run_id} rather than on timing or row counts,
	 * because those cannot tell "skipped it" from "recomputed it to the same
	 * value" — and an incremental projection that silently still scans everything
	 * is precisely the regression worth catching. {@code recorded} closed ten days
	 * before this test's clock, so it is outside the freeze grace; {@code empty} is
	 * open and can always change.
	 */
	@Test
	void anIncrementalRunLeavesAClosedSessionsRowAlone() throws Exception {
		run();
		long firstRun = projectionOf(recorded);
		assertThat(projectionOf(empty))
				.as("both sessions were derived by the first run")
				.isEqualTo(firstRun);

		run();

		assertThat(projectionOf(recorded))
				.as("the closed session was not touched, so it keeps the run that derived it")
				.isEqualTo(firstRun);
		assertThat(projectionOf(empty))
				.as("the open session was re-derived, so it carries the newer run")
				.isGreaterThan(firstRun);
	}

	/**
	 * What the incremental pass gives up, stated as a test rather than left to be
	 * discovered: a message appearing against a long-closed session is not picked
	 * up until a full rebuild.
	 *
	 * <p>This cannot happen in production — {@code raw} is append-only and the
	 * recorder never writes a closed session's id again, with {@code FREEZE_GRACE}
	 * covering the batch still draining when the row is closed. The test exists so
	 * that the assumption is visible and so that {@code full=true} is known to be
	 * the remedy, not so that the situation is expected.
	 */
	@Test
	void aFullRebuildPicksUpWhatAnIncrementalRunDeliberatelyMisses() throws Exception {
		run();
		assertThat(one("messages", recorded)).isEqualTo(3);

		message(recorded, "1.3", START.plusSeconds(40));

		run();
		assertThat(one("messages", recorded))
				.as("the closed session is frozen, so an incremental run does not see it")
				.isEqualTo(3);

		run(true);
		assertThat(one("messages", recorded))
				.as("a full rebuild re-derives from raw and finds it")
				.isEqualTo(4);
	}

	/**
	 * A spill replayed long after its session closed is picked up without a
	 * rebuild.
	 *
	 * <p>The shape is taken from what really happened: {@code raw.spill_file} id 1,
	 * session 45, spilled two seconds after the session closed and ingested
	 * <b>18.8 hours later</b> when the database came back. A freeze that trusted
	 * {@code ended_at} plus any fixed window would have dropped that message from
	 * the session's count permanently, and the count is the one number this ledger
	 * exists to be trusted on. So the guard is the spill record rather than a
	 * clock, and this test is the reason to keep it that way.
	 */
	@Test
	void aSpillIngestedLongAfterTheSessionClosedIsStillCounted() throws Exception {
		run();
		assertThat(one("messages", recorded)).isEqualTo(3);

		// What SpillDrain does when the database comes back: the message lands
		// against a session that ended ten days ago, and the file records when.
		message(recorded, "1.1", START.plusSeconds(50));
		spillFile(recorded, 1);

		run();

		assertThat(one("messages", recorded))
				.as("the spill's ingested_at is newer than the row's projection, so it is stale")
				.isEqualTo(4);
	}

	/**
	 * A session that ended while nothing refreshed the ledger is still seen to
	 * have ended (paddock#337).
	 *
	 * <p>The shape is what happened seven times on the live database: the ledger
	 * derived the session while it was open, then the recorder stopped — which is
	 * exactly when this in-process refresh stops too — and the next refresh came
	 * more than FREEZE_GRACE after {@code ended_at}. The row was not missing and
	 * no spill arrived, so nothing called it stale, and the screen said "still
	 * running" for days. Ended weeks before the test's clock here, so no window
	 * can be what rescues it: only comparing the row with its source can.
	 */
	@Test
	void aSessionThatEndedWhileNothingRefreshedIsSeenToHaveEnded() throws Exception {
		run();
		assertThat(instant("ended_at", empty)).as("derived while open").isNull();

		Instant ended = START.plusSeconds(7300);
		jdbc.sql("update raw.capture_session set ended_at = ?, exit_status = 'COMPLETED' where id = ?")
				.params(at(ended), empty)
				.update();

		run();

		assertThat(instant("ended_at", empty)).isEqualTo(ended);
		assertThat(text("select exit_status from ledger.capture_session where session_id = ?", empty))
				.isEqualTo("COMPLETED");
	}

	/**
	 * The same freeze for a scoped market: derived while LIVE, left scope, and no
	 * refresh inside the grace. Not yet seen on the live database (0 of 2,798 on
	 * 2026-09-24), but the predicate had the same shape, so it had the same hole.
	 */
	@Test
	void aMarketThatLeftScopeWhileNothingRefreshedIsSeenToHaveLeft() throws Exception {
		scoped("1.5", true, "LIVE", null);
		run();
		assertThat(text("select state from ledger.market_scope where market_id = ?", "1.5"))
				.isEqualTo("LIVE");

		jdbc.sql("""
						update raw.market_scope
						set state = 'DONE', exit_reason = 'CLOSED', state_changed_at = ?
						where market_id = '1.5'""")
				.params(at(START.plusSeconds(3600)))
				.update();

		run();

		assertThat(text("select state from ledger.market_scope where market_id = ?", "1.5"))
				.isEqualTo("DONE");
		assertThat(text("select exit_reason from ledger.market_scope where market_id = ?", "1.5"))
				.isEqualTo("CLOSED");
	}

	/**
	 * A message the exchange published just before the recorder opened the session
	 * still counts.
	 *
	 * <p>The partition floor is derived from {@code started_at}, and {@code pt} is
	 * Betfair's clock rather than ours — so a bound of {@code started_at} itself
	 * would drop this row silently. Truncating to the month is what makes it safe,
	 * and an evening-boundary case is chosen deliberately: a floor bug that only
	 * loses rows in the first seconds of a session is invisible to a fixture whose
	 * messages all sit comfortably inside it.
	 */
	@Test
	void countsAMessagePublishedJustBeforeTheSessionOpened() throws Exception {
		long borderline = session("run-y", START.plusSeconds(10_000), null, "RESIDENT", null, null);
		message(borderline, "1.9", START.plusSeconds(10_000).minusSeconds(1));

		run();

		assertThat(one("messages", borderline))
				.as("pt one second before started_at is inside the session's month")
				.isEqualTo(1);
	}

	private void scoped(String marketId, boolean requested, String state, String exitReason) {
		jdbc.sql("""
						insert into raw.market_scope
							(market_id, market_type, requested, state, exit_reason, kickoff)
						values (?, 'MATCH_ODDS', ?, ?, ?, ?)""")
				.params(Arrays.asList(marketId, requested, state, exitReason, at(START)))
				.update();
	}

	private long scopeMessages(String marketId) {
		Long value = jdbc.sql("select messages from ledger.market_scope where market_id = ?")
				.param(marketId).query(Long.class).single();
		return value == null ? 0 : value;
	}

	private long session(String sourceKey, Instant started, Instant ended, String origin,
			String exitStatus, String exitDetail) {
		Long id = jdbc.sql("""
						insert into raw.capture_session
							(source_key, started_at, ended_at, origin, exit_status, exit_detail,
							 config_json, build_version)
						values (?, ?, ?, ?, ?, ?, '{}'::jsonb, 'test') returning id""")
				.params(Arrays.asList(sourceKey, at(started), ended == null ? null : at(ended),
						origin, exitStatus, exitDetail))
				.query(Long.class).single();
		return id;
	}

	private void message(long sessionId, String marketId, Instant at) {
		message(sessionId, marketId, at, false);
	}

	/**
	 * A message as the wire delivered it.
	 *
	 * <p>{@code con} is the exchange's own statement that it merged several
	 * changes into this envelope. The payload here is otherwise empty because
	 * nothing in this ledger reads the book — see
	 * {@code CaptureLedgerWriter}'s javadoc.
	 */
	private void message(long sessionId, String marketId, Instant at, boolean conflated) {
		jdbc.sql("""
						insert into raw.stream_message
							(session_id, market_id, pt, received_at, seq, payload)
						values (?, ?, ?, ?, ?, cast(? as jsonb))""")
				.params(sessionId, marketId, at(at), at(at), at.getEpochSecond(),
						conflated ? "{\"con\": true}" : "{}")
				.update();
	}

	/**
	 * A spill file replayed now, for a session that closed long ago.
	 *
	 * <p>{@code ingested_at} defaults to {@code now()}, which is the fact the
	 * staleness check reads: the replay is recent even though everything about the
	 * session is not.
	 */
	private void spillFile(long sessionId, int messages) {
		jdbc.sql("""
						insert into raw.spill_file
							(name, session_id, cause, messages, bytes, spilled_at)
						values (?, ?, 'DB_UNAVAILABLE', ?, 1024, ?)""")
				.params(Arrays.asList("spill-" + sessionId + "-" + System.nanoTime(), sessionId,
						messages, at(START.plusSeconds(3602))))
				.update();
	}

	private void gap(long sessionId, Instant from, int seconds, String cause) {
		jdbc.sql("""
						insert into raw.capture_gap (session_id, started_at, ended_at, cause)
						values (?, ?, ?, ?)""")
				.params(sessionId, at(from), at(from.plusSeconds(seconds)), cause)
				.update();
	}

	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private long one(String column, long sessionId) {
		Long value = jdbc.sql("select " + column + " from ledger.capture_session where session_id = ?")
				.param(sessionId).query(Long.class).single();
		return value == null ? 0 : value;
	}

	private Instant instant(String column, long sessionId) {
		OffsetDateTime value = jdbc.sql(
						"select " + column + " from ledger.capture_session where session_id = ?")
				.param(sessionId).query(OffsetDateTime.class).optional().orElse(null);
		return value == null ? null : value.toInstant();
	}

	private String text(String sql, Object key) {
		return jdbc.sql(sql).param(key).query(String.class).optional().orElse(null);
	}

	private long count(String sql) {
		Long value = jdbc.sql(sql).query(Long.class).single();
		return value == null ? 0 : value;
	}

	/** The projection run that last derived this session's row. */
	private long projectionOf(long sessionId) {
		return one("ledger_run_id", sessionId);
	}

	private JobExecution run() throws Exception {
		return run(false);
	}

	private JobExecution run(boolean full) throws Exception {
		return jobOperator.start(projectCaptureLedgerJob, new JobParametersBuilder()
				.addLong(ProjectionRun.PARAM, System.nanoTime())
				.addString(ProjectionRun.FULL_PARAM, Boolean.toString(full))
				.toJobParameters());
	}
}
