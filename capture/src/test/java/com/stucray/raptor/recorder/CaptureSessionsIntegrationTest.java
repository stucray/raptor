package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The session ledger against the real table, and the one thing it could not say.
 *
 * <p>{@code ended_at is null} meant two opposite things: a capture in progress,
 * and a capture whose JVM was killed with nothing left to stamp an ending. Rows
 * 7 and 8 said "in progress" for two days (#129). A recorder cannot write its
 * own ending after a SIGKILL, so the next one to take the lease writes it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("The session ledger closes what a killed recorder could not")
class CaptureSessionsIntegrationTest {

	@Autowired CaptureSessions sessions;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	void aSessionLeftOpenIsClosedAtItsLastHeartbeat() {
		long killed = sessions.begin(CaptureOrigin.RESIDENT, "{}", "test");
		sessions.seen(killed);
		OffsetDateTime heartbeat = lastSeen(killed);

		List<Long> abandoned = sessions.abandonOpenSessions("killed");

		assertThat(abandoned).contains(killed);
		// ABANDONED, never COMPLETED: it did not finish, and a ledger claiming it
		// did would be worse than one saying nothing.
		assertThat(row(killed)).containsEntry("exit_status", "ABANDONED")
				.containsEntry("exit_detail", "killed");
		// The ending is the last moment it was demonstrably alive — a floor, not an
		// observation, because nothing observed the kill. Read back as an
		// OffsetDateTime: the generic row map hands back a java.sql.Timestamp,
		// which compares equal to nothing a test would write.
		assertThat(instant(killed, "ended_at")).isEqualTo(heartbeat.toInstant());
	}

	/** A session killed before its first heartbeat still gets an honest floor. */
	@Test
	void aSessionThatNeverBeatIsClosedAtItsStart() {
		long killed = sessions.begin(CaptureOrigin.RESIDENT, "{}", "test");

		sessions.abandonOpenSessions("killed");

		Map<String, Object> row = row(killed);
		assertThat(row).containsEntry("exit_status", "ABANDONED")
				.containsEntry("ended_at", row.get("started_at"));
	}

	/** A session that ended properly is not reopened or relabelled. */
	@Test
	void aSessionThatEndedCleanlyIsLeftAlone() {
		long finished = sessions.begin(CaptureOrigin.RESIDENT, "{}", "test");
		sessions.end(finished, "COMPLETED", "framed=17 written=17");

		List<Long> abandoned = sessions.abandonOpenSessions("killed");

		assertThat(abandoned).doesNotContain(finished);
		assertThat(row(finished)).containsEntry("exit_status", "COMPLETED")
				.containsEntry("exit_detail", "framed=17 written=17");
	}

	private Map<String, Object> row(long sessionId) {
		return jdbc.sql("""
						select started_at, ended_at, exit_status, exit_detail, last_seen_at
						from raw.capture_session where id = ?""")
				.param(sessionId)
				.query()
				.singleRow();
	}

	private OffsetDateTime lastSeen(long sessionId) {
		return jdbc.sql("select last_seen_at from raw.capture_session where id = ?")
				.param(sessionId)
				.query(OffsetDateTime.class)
				.single();
	}

	private Instant instant(long sessionId, String column) {
		return jdbc.sql("select " + column + " from raw.capture_session where id = ?")
				.param(sessionId)
				.query(OffsetDateTime.class)
				.single()
				.toInstant();
	}
}
