package com.stucray.raptor.ingest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The Python era's capture sessions, in time order, so that every imported
 * message can be told which run received it.
 *
 * <p>The sessions are seeded by V12 from paddock's own capture-run ledger and
 * are back-to-back: each run's {@code ended_at} is the next one's
 * {@code started_at}, because the launchd log they were parsed from is a single
 * appended stream of invocations. So the session that had started most recently
 * when a message arrived is the session that received it, and a floor lookup is
 * the whole rule.
 *
 * <p>A message earlier than the first session is possible in principle — a
 * capture whose first invocation predates the oldest surviving log — and is
 * given to the earliest session rather than dropped. {@link #outOfWindow()}
 * counts those, so an assumption that stopped holding is visible in the load's
 * own log instead of silently reshaping provenance.
 */
final class ImportedSessions {

	private final NavigableMap<Instant, Long> byStart;
	private final Instant earliest;
	private final long earliestId;
	private int outOfWindow;

	private ImportedSessions(NavigableMap<Instant, Long> byStart) {
		if (byStart.isEmpty()) {
			throw new IllegalStateException(
					"no imported capture sessions in raw.capture_session: the Python era's "
							+ "run ledger has not been carried across, so its messages have no "
							+ "session to belong to");
		}
		this.byStart = byStart;
		this.earliest = byStart.firstKey();
		this.earliestId = byStart.firstEntry().getValue();
	}

	static ImportedSessions load(JdbcClient jdbc) {
		NavigableMap<Instant, Long> byStart = new TreeMap<>();
		jdbc.sql("""
						select id, started_at from raw.capture_session
						where source_key is not null
						order by started_at""")
				.query((rs, rowNum) -> Map.entry(
						rs.getObject("started_at", OffsetDateTime.class).toInstant(),
						rs.getLong("id")))
				.list()
				.forEach(entry -> byStart.put(entry.getKey(), entry.getValue()));
		return new ImportedSessions(byStart);
	}

	/** The session that was running when a message was received. */
	long at(Instant received) {
		if (received.isBefore(earliest)) {
			outOfWindow++;
			return earliestId;
		}
		// Non-null by the guard above: the map is never empty and `received` is at
		// or after its first key, so a floor entry exists.
		return Objects.requireNonNull(byStart.floorEntry(received)).getValue();
	}

	/** How many messages arrived before any known session had started. */
	int outOfWindow() {
		return outOfWindow;
	}

	int size() {
		return byStart.size();
	}
}
