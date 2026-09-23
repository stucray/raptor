package com.stucray.raptor.copy;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Builds one PostgreSQL text-format COPY payload and streams it in.
 *
 * <p>COPY rather than batched INSERT wherever the write path bulk-loads: it
 * sustains &gt;100k rows/s, and that headroom is what lets the live write path
 * commit on a timer rather than when a buffer happens to fill.
 *
 * <p>The escaping lives here and nowhere else. Every bulk writer in the codebase
 * emits JSON, text with tabs, or both, and a COPY payload that gets escaping
 * subtly wrong does not fail — it silently shifts columns. One implementation
 * cannot drift from itself.
 */
public final class CopyBuffer {

	/**
	 * Microsecond precision, matching PostgreSQL's own timestamp resolution — and
	 * an <em>explicit</em> UTC offset, which is load-bearing.
	 *
	 * <p>COPY parses a bare timestamp into a {@code timestamptz} using the
	 * session's {@code TimeZone}, and pgjdbc sets that from the JVM's default
	 * zone. Rendering the instant in UTC and then omitting the offset therefore
	 * silently shifts every row by the developer's own offset — seven hours here
	 * — with no error, no warning, and nothing in a row count or a digest to show
	 * it. It survived S2 because the load was verified on sha256 and counts,
	 * which a shifted timestamp does not disturb.
	 */
	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSXXX").withZone(ZoneOffset.UTC);

	private static final String NULL = "\\N";

	private final StringBuilder out;
	private boolean atRowStart = true;

	public CopyBuffer(int expectedRows) {
		this.out = new StringBuilder(Math.max(64, expectedRows * 64));
	}

	/** Append one field, null-safe, escaping text and rendering instants. */
	public CopyBuffer add(@Nullable Object value) {
		if (atRowStart) {
			atRowStart = false;
		} else {
			out.append('\t');
		}
		switch (value) {
			case null -> out.append(NULL);
			case Instant instant -> out.append(TIMESTAMP.format(instant));
			case CharSequence text -> escapeInto(text);
			default -> escapeInto(value.toString());
		}
		return this;
	}

	/** End the current row. */
	public CopyBuffer endRow() {
		out.append('\n');
		atRowStart = true;
		return this;
	}

	public boolean isEmpty() {
		return out.isEmpty();
	}

	/** The rendered payload. Package-private: the escaping is what tests assert on. */
	String payload() {
		return out.toString();
	}

	/**
	 * Stream the buffer into PostgreSQL.
	 *
	 * <p>{@link DataSourceUtils}, not {@code dataSource.getConnection()}: this
	 * must join the caller's transaction. A fresh connection would commit
	 * independently of the provenance row that vouches for these rows, so a
	 * failure mid-write could leave rows with no provenance, or provenance with
	 * no rows. Both are worse than a rollback.
	 *
	 * @return rows written
	 */
	public long copyInto(DataSource dataSource, String copySql) throws SQLException, IOException {
		if (out.isEmpty()) {
			return 0;
		}
		Connection connection = DataSourceUtils.getConnection(dataSource);
		try {
			return connection.unwrap(PGConnection.class).getCopyAPI()
					.copyIn(copySql, new StringReader(out.toString()));
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	/**
	 * PostgreSQL text-format COPY escaping. Payloads are JSON, which routinely
	 * contains backslashes in escaped strings and must never be allowed to
	 * introduce a field or row terminator.
	 */
	private void escapeInto(CharSequence value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '\\' -> out.append("\\\\");
				case '\t' -> out.append("\\t");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				default -> out.append(c);
			}
		}
	}
}
