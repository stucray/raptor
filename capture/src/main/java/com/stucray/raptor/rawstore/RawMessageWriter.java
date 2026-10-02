package com.stucray.raptor.rawstore;

import com.stucray.raptor.copy.CopyBuffer;
import com.stucray.raptor.datasource.Acquisition;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes {@link RawMessage} rows into {@code raw.stream_message} via PostgreSQL
 * COPY.
 *
 * <p>COPY rather than batched INSERT for the same reason paddock's
 * {@code BulkCopyImporter} uses it: it sustains &gt;100k rows/s against a live
 * peak of ~200 msg/s, and that two-orders-of-magnitude headroom is what lets the
 * live write path commit on a 200 ms timer rather than when a buffer happens to
 * fill. The historic load reuses the same writer so the bulk path and the
 * capture path cannot drift apart.
 */
@Component
class RawMessageWriter implements RawWriter {

	private static final String COPY_SQL = """
			copy raw.stream_message (session_id, file_id, market_id, pt, received_at, seq, payload,
				segment_type, change_type)
			from stdin with (format text)""";

	/**
	 * The replay's staging table: the stream's columns, nothing else, gone at
	 * commit. Created per replay because {@code on commit drop} ends it with the
	 * transaction that needed it.
	 */
	private static final String STAGE_SQL = """
			create temp table spill_replay (
				session_id   bigint,
				file_id      bigint,
				market_id    text,
				pt           timestamptz,
				received_at  timestamptz,
				seq          bigint,
				payload      jsonb,
				segment_type text,
				change_type  text
			) on commit drop""";

	private static final String STAGE_COPY_SQL = """
			copy spill_replay (session_id, file_id, market_id, pt, received_at, seq, payload,
				segment_type, change_type)
			from stdin with (format text)""";

	/**
	 * Stored rows sharing a staged message's session key: as many as are identical
	 * to it, and as many as are not. The key is unique, so each message matches at
	 * most one row.
	 */
	private static final String MATCHED_SQL = """
			select count(*) filter (where same) as identical,
			       count(*) filter (where not same) as different
			from (
				select (m.file_id, m.pt, m.received_at, m.payload, m.segment_type, m.change_type)
				       is not distinct from
				       (r.file_id, r.pt, r.received_at, r.payload, r.segment_type, r.change_type) as same
				from spill_replay r
				join raw.stream_message m
				  on m.session_id = r.session_id and m.seq = r.seq and m.market_id = r.market_id
			) matched""";

	private static final String APPEND_SQL = """
			insert into raw.stream_message (session_id, file_id, market_id, pt, received_at, seq,
				payload, segment_type, change_type)
			select session_id, file_id, market_id, pt, received_at, seq, payload, segment_type,
				change_type
			from spill_replay
			on conflict on constraint stream_message_session_seq_market_key do nothing""";

	private final DataSource dataSource;
	private final JdbcClient jdbc;

	RawMessageWriter(@Acquisition DataSource acquisitionDataSource) {
		this.dataSource = acquisitionDataSource;
		// Over the same DataSource, so its statements join the caller's
		// transaction and connection exactly as the COPY does.
		this.jdbc = JdbcClient.create(acquisitionDataSource);
	}

	@Override
	public long write(List<RawMessage> messages) throws SQLException, IOException {
		if (messages.isEmpty()) {
			return 0;
		}
		return rows(messages).copyInto(dataSource, COPY_SQL);
	}

	@Override
	public Replayed replay(List<RawMessage> messages) throws SQLException, IOException {
		if (messages.isEmpty()) {
			return new Replayed(0, 0);
		}
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("a replay compares and appends in one transaction; "
					+ "there is none");
		}
		if (messages.stream().anyMatch(m -> m.sessionId() == null)) {
			throw new IllegalArgumentException("a replay puts back capture-session rows only");
		}
		jdbc.sql(STAGE_SQL).update();
		rows(messages).copyInto(dataSource, STAGE_COPY_SQL);
		Matched matched = jdbc.sql(MATCHED_SQL)
				.query((rs, n) -> new Matched(rs.getLong("identical"), rs.getLong("different")))
				.single();
		if (matched.different() > 0) {
			throw new IllegalStateException(matched.different() + " of " + messages.size()
					+ " replayed message(s) share a stored message's session key but not its "
					+ "content; nothing replayed");
		}
		long written = jdbc.sql(APPEND_SQL).update();
		return new Replayed(written, matched.identical());
	}

	private record Matched(long identical, long different) {}

	private static CopyBuffer rows(List<RawMessage> messages) {
		CopyBuffer buffer = new CopyBuffer(messages.size());
		for (RawMessage message : messages) {
			buffer.add(message.sessionId())
					.add(message.fileId())
					.add(message.marketId())
					.add(message.pt())
					.add(message.receivedAt())
					.add(message.seq())
					.add(message.payload())
					.add(message.segmentType())
					.add(message.changeType())
					.endRow();
		}
		return buffer;
	}

	/** Exposed for the historic load, which needs pt as an Instant from epoch millis. */
	static Instant ptFromEpochMillis(long millis) {
		return Instant.ofEpochMilli(millis);
	}
}
