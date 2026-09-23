package com.stucray.raptor.rawstore;

import com.stucray.raptor.copy.CopyBuffer;
import com.stucray.raptor.datasource.Acquisition;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

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
			copy raw.stream_message (session_id, file_id, market_id, pt, received_at, seq, payload)
			from stdin with (format text)""";

	private final DataSource dataSource;

	RawMessageWriter(@Acquisition DataSource acquisitionDataSource) {
		this.dataSource = acquisitionDataSource;
	}

	@Override
	public long write(List<RawMessage> messages) throws SQLException, IOException {
		if (messages.isEmpty()) {
			return 0;
		}
		CopyBuffer buffer = new CopyBuffer(messages.size());
		for (RawMessage message : messages) {
			buffer.add(message.sessionId())
					.add(message.fileId())
					.add(message.marketId())
					.add(message.pt())
					.add(message.receivedAt())
					.add(message.seq())
					.add(message.payload())
					.endRow();
		}
		return buffer.copyInto(dataSource, COPY_SQL);
	}

	/** Exposed for the historic load, which needs pt as an Instant from epoch millis. */
	static Instant ptFromEpochMillis(long millis) {
		return Instant.ofEpochMilli(millis);
	}
}
