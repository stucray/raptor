package com.stucray.raptor.recorder;

import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@link Quarantine} over {@code raw.rejected_message}.
 *
 * <p><b>Writing to the same database that just refused the row is the right
 * fallback here, and only here.</b> The server is up — that is what
 * distinguishes this from a spill — and V29's table is built so that nothing
 * about it can refuse what {@code raw.stream_message} refused: {@code payload}
 * is {@code text} rather than {@code jsonb}, there are no foreign keys, no
 * check constraints and no partitions, and every column that can be null in a
 * rejection is nullable. The migration header sets out each one against the
 * SQLSTATE it answers.
 *
 * <p><b>INSERT rather than COPY, deliberately.</b> The write path uses COPY for
 * throughput against a ~200 msg/s stream; this path runs only when something
 * has already gone wrong, and an ordinary parameterised insert keeps the
 * failure surface as small as it can be. It also means a single bad row fails
 * alone rather than taking the statement with it.
 *
 * <p>If it still fails, it says so and returns {@code false}: the caller spills
 * instead, and the operator is no worse off than before #271.
 */
@Component
class RejectedMessages implements Quarantine {

	private static final Logger log = LoggerFactory.getLogger(RejectedMessages.class);

	private static final String INSERT = """
			insert into raw.rejected_message
				(sqlstate, failure, session_id, file_id, market_id, pt, received_at, seq, payload)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

	/**
	 * Held since this process started. The table is the durable record; this is
	 * what the log line quotes, so a burst reads as a burst rather than as N
	 * unrelated lines.
	 */
	private final AtomicLong held = new AtomicLong();

	private final JdbcClient jdbc;

	RejectedMessages(@Acquisition JdbcClient acquisitionJdbcClient) {
		this.jdbc = acquisitionJdbcClient;
	}

	@Override
	public boolean hold(List<RawMessage> messages, String sqlState, String failure) {
		if (messages.isEmpty()) {
			return true;
		}
		try {
			for (RawMessage message : messages) {
				// params(List) rather than params(Object...): the varargs form is
				// declared non-null and NullAway fails the build on it, and almost
				// every column here is legitimately null — that is the point of the
				// table.
				jdbc.sql(INSERT).params(Arrays.asList(
						sqlState,
						failure,
						message.sessionId(),
						message.fileId(),
						message.marketId(),
						offset(message.pt()),
						offset(message.receivedAt()),
						message.seq(),
						message.payload())).update();
			}
		}
		catch (RuntimeException e) {
			// Loud, and then the caller spills. A quarantine that cannot hold the
			// row is the one case this design did not plan for, and it must not be
			// the case where the message quietly disappears.
			log.error("could not quarantine {} message(s) rejected with SQLSTATE {}; "
					+ "falling back to the spill", messages.size(), sqlState, e);
			return false;
		}
		long total = held.addAndGet(messages.size());
		// ERROR because a quarantined message is NOT in the system of record, and
		// nothing downstream can tell a row that never arrived from one that was
		// never sent. The payload is logged at DEBUG only: it is the evidence, and
		// it is already in the table.
		log.error("quarantined {} message(s) rejected with SQLSTATE {} ({}); {} held this "
				+ "session. These are NOT captured — fix the cause and replay "
				+ "raw.rejected_message", messages.size(), sqlState, failure, total);
		if (log.isDebugEnabled()) {
			List<String> markets = new ArrayList<>();
			for (RawMessage message : messages) {
				markets.add(message.marketId() + "@" + message.pt());
			}
			log.debug("quarantined: {}", markets);
		}
		return true;
	}

	/**
	 * pgjdbc cannot infer a SQL type for an {@code Instant} parameter and throws;
	 * an {@code OffsetDateTime} binds. Note the asymmetry with the COPY path,
	 * which takes the instant as formatted text.
	 */
	private static @Nullable OffsetDateTime offset(@Nullable Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	/**
	 * A count against a table that should be empty, on the index V29 adds for
	 * exactly this read.
	 *
	 * <p>It is on the health path, so it must never become the expensive thing
	 * {@code projectCaptureLedgerJob} became (#261) — and it cannot, because a
	 * non-empty quarantine is already a finding somebody is acting on.
	 *
	 * <p>Returns -1 rather than throwing if the table cannot be read: an
	 * indicator that fails because of its own diagnostic is worse than one that
	 * says it does not know.
	 */
	@Override
	public long quarantined() {
		try {
			Long count = jdbc.sql("select count(*) from raw.rejected_message")
					.query(Long.class).single();
			return count == null ? -1 : count;
		}
		catch (RuntimeException e) {
			log.warn("could not count raw.rejected_message: {}", e.toString());
			return -1;
		}
	}

	/** Held by THIS process, for the log line. The table is the durable record. */
	long held() {
		return held.get();
	}
}
