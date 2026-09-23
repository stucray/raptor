package com.stucray.raptor.recorder;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The gap ledger: intervals the recorder knows it was not receiving.
 *
 * <p>Recording a gap is <b>best-effort, and must stay that way</b>. Every caller
 * here is already handling a failure — a disconnect, a suspended machine, a
 * silent socket — and the response to that failure is to reconnect. Throwing
 * out of the bookkeeping would abandon the reconnect to preserve the note about
 * why it was needed, which is exactly backwards. A lost gap row costs one line
 * of provenance; a lost reconnect costs the rest of the match.
 */
@Component
class CaptureGaps {

	private static final Logger log = LoggerFactory.getLogger(CaptureGaps.class);

	private final JdbcClient jdbc;

	CaptureGaps(@Acquisition JdbcClient acquisitionJdbcClient) {
		this.jdbc = acquisitionJdbcClient;
	}

	/**
	 * Write one gap, or say in the log why it could not be written.
	 *
	 * @param sessionId the session that was running when the gap happened
	 * @param from when the stream was last known to be arriving
	 * @param to when the recorder noticed
	 * @param detail free text for a reader of the ledger; the numbers that made
	 *     the call, in the units they were measured in
	 */
	void record(long sessionId, Instant from, Instant to, GapCause cause, @Nullable String detail) {
		Instant end = to.isBefore(from) ? from : to;
		try {
			jdbc.sql("""
							insert into raw.capture_gap (session_id, started_at, ended_at, cause, detail)
							values (?, ?, ?, ?, ?)""")
					// params(List) rather than the varargs form: detail is legitimately
					// absent and JdbcClient.params(Object...) is declared non-null.
					.params(Arrays.asList(sessionId, utc(from), utc(end), cause.name(), detail))
					.update();
			log.warn("session {}: {} gap of {}s recorded ({} -> {}){}", sessionId, cause,
					java.time.Duration.between(from, end).toSeconds(), from, end,
					detail == null ? "" : ": " + detail);
		} catch (RuntimeException e) {
			// The database being unreachable is itself a plausible reason a gap
			// exists, so this failing is not a surprise and must not be fatal.
			log.error("session {}: could not record the {} gap {} -> {}; it is in this log "
					+ "and nowhere else", sessionId, cause, from, end, e);
		}
	}

	/** {@code OffsetDateTime}: pgjdbc cannot infer a SQL type for an {@code Instant}. */
	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
