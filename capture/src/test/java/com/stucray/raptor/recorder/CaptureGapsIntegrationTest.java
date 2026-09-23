package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The gap ledger, against the real table.
 *
 * <p>Today a gap is <em>inferred</em>: {@code parse_captures.py} compares
 * message timestamps against a 60-second threshold and
 * {@code gap_simultaneity.py} cross-checks the candidates against other markets
 * to guess whether a quiet book, a network drop or a sleeping laptop produced
 * them — which is why the existing ledger carries four different gap columns
 * that routinely disagree. A resident recorder knows the answer; these tests are
 * about it writing that answer down where the answer can be trusted.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CaptureGapsIntegrationTest {

	@Autowired CaptureGaps gaps;
	@Autowired CaptureSessions sessions;
	@Autowired Clock clock;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	void writesTheSpanAndTheCauseAgainstTheSession() {
		long sessionId = sessions.begin(CaptureOrigin.RESIDENT, "{}", "test");
		Instant from = clock.instant().minus(Duration.ofMinutes(12));
		Instant to = clock.instant();

		gaps.record(sessionId, from, to, GapCause.SLEEP, "framed=17 written=17");

		Map<String, Object> row = row(sessionId);
		assertThat(row).containsEntry("cause", "SLEEP")
				.containsEntry("detail", "framed=17 written=17");
		assertThat(seconds(row)).isEqualTo(720L);
	}

	/** A gap with no detail is still a gap; absence has one spelling, and it is NULL. */
	@Test
	void recordsAGapWithNoDetail() {
		long sessionId = sessions.begin(CaptureOrigin.RESIDENT, "{}", "test");

		gaps.record(sessionId, clock.instant().minusSeconds(30), clock.instant(),
				GapCause.SILENCE, null);

		assertThat(row(sessionId)).containsEntry("cause", "SILENCE").containsEntry("detail", null);
	}

	/**
	 * A gap that ends before it starts is a clock bug, and the table refuses it —
	 * so the recorder must not offer one.
	 *
	 * <p>Reachable in practice: the watchdog's {@code since} comes from the wall
	 * clock, and the thing it detects is that same clock jumping. The whole value
	 * of this ledger is that its spans can be trusted arithmetic, so an inverted
	 * pair is clamped to a zero-length gap at the earlier instant rather than
	 * thrown away or written as nonsense.
	 */
	@Test
	void clampsAnInvertedSpanRatherThanLosingTheGap() {
		long sessionId = sessions.begin(CaptureOrigin.RESIDENT, "{}", "test");
		Instant later = clock.instant();

		gaps.record(sessionId, later, later.minusSeconds(5), GapCause.SLEEP, "clock went backwards");

		assertThat(seconds(row(sessionId))).isZero();
	}

	/**
	 * The bookkeeping never throws.
	 *
	 * <p>Every caller is already handling a failure and about to reconnect.
	 * Throwing here would abandon the reconnect in order to preserve the note
	 * about why it was needed — a lost gap row costs one line of provenance, a
	 * lost reconnect costs the rest of the match.
	 */
	@Test
	void survivesAGapItCannotWrite() {
		gaps.record(-1L, clock.instant().minusSeconds(5), clock.instant(),
				GapCause.DISCONNECT, "no such session");

		assertThat(jdbc.sql("select count(*) from raw.capture_gap where session_id = -1")
				.query(Long.class).single()).isZero();
	}

	private Map<String, Object> row(long sessionId) {
		return jdbc.sql("""
						select cause, detail,
						       extract(epoch from (ended_at - started_at))::bigint as span_seconds
						from raw.capture_gap where session_id = ?""")
				.param(sessionId)
				.query()
				.singleRow();
	}

	private static long seconds(Map<String, Object> row) {
		return ((Number) row.get("span_seconds")).longValue();
	}
}
