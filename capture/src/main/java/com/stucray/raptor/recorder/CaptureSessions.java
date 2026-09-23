package com.stucray.raptor.recorder;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The session ledger: what ran, when, under what configuration.
 *
 * <p>This row is what replaces paddock's {@code capture_run} and the 539-line
 * launchd-log parser behind it. A resident recorder knows what it did; having it
 * write that down is strictly better than reconstructing it afterwards from logs
 * that were never designed to be evidence.
 */
@Component
class CaptureSessions {

	private final JdbcClient jdbc;
	private final Clock clock;

	CaptureSessions(@Acquisition JdbcClient acquisitionJdbcClient, Clock clock) {
		this.jdbc = acquisitionJdbcClient;
		this.clock = clock;
	}

	long begin(CaptureOrigin origin, String configJson, String buildVersion) {
		Long id = jdbc.sql("""
						insert into raw.capture_session (started_at, origin, config_json, build_version)
						values (?, ?, cast(? as jsonb), ?)
						returning id""")
				.params(now(), origin.name(), configJson, buildVersion)
				.query(Long.class)
				.single();
		return id;
	}

	/**
	 * Say that this session is still alive, at this moment.
	 *
	 * <p>Cheap on purpose — one row, one column — because it runs on the
	 * watchdog's timer for as long as a capture lasts. What it buys is that
	 * {@code ended_at is null} stops meaning two opposite things at once: a
	 * capture in progress, and a capture whose JVM was killed with nothing left
	 * to stamp an ending (#129).
	 */
	void seen(long sessionId) {
		jdbc.sql("update raw.capture_session set last_seen_at = ? where id = ?")
				.params(now(), sessionId)
				.update();
	}

	/**
	 * Close every session that was left open, and say it was not a clean ending.
	 *
	 * <p>Called by the instance that has just taken the capture lease, which is
	 * what makes it sound: the lease is a session-level advisory lock, so
	 * PostgreSQL released the previous holder's the moment its connection died.
	 * Holding it therefore proves no other recorder is writing, and any row still
	 * open belongs to a process that is gone.
	 *
	 * <p>{@code ABANDONED}, never {@code COMPLETED}: a session that was killed
	 * did not finish, and a ledger that says it did is worse than one that says
	 * nothing. The ending is the last heartbeat, falling back to the start for a
	 * session that never recorded one — a floor, not an observation, which is
	 * what the detail says.
	 *
	 * @return the ids closed, so the caller can say so out loud
	 */
	List<Long> abandonOpenSessions(String detail) {
		return jdbc.sql("""
						update raw.capture_session
						set ended_at = coalesce(last_seen_at, started_at),
								exit_status = 'ABANDONED',
								exit_detail = ?
						where ended_at is null
						returning id""")
				.params(detail)
				.query(Long.class)
				.list();
	}

	void end(long sessionId, String status, @Nullable String detail) {
		jdbc.sql("update raw.capture_session set ended_at = ?, exit_status = ?, exit_detail = ? where id = ?")
				// params(List) rather than the varargs form: `detail` is legitimately
				// absent and JdbcClient.params(Object...) is declared non-null.
				.params(java.util.Arrays.asList(now(), status, detail, sessionId))
				.update();
	}

	/**
	 * {@code OffsetDateTime}, not {@code Instant}: pgjdbc cannot infer a SQL type
	 * for an {@code Instant} parameter and throws. The COPY path needs the opposite
	 * spelling — an instant rendered as text with an explicit offset — so the same
	 * value has two forms in this codebase, both deliberate.
	 */
	private OffsetDateTime now() {
		return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
	}
}
