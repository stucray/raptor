package com.stucray.raptor.rawstore;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * The only way into the system of record.
 *
 * <p>The interface exists because the implementation is a {@code @Component} and
 * paddock requires those to be package-private, while ingest legitimately needs
 * to write. Narrowing the exposed surface to this one method is the point: a
 * caller can append to {@code raw} and can do nothing else to it — no update, no
 * delete, no read — which is the append-only rule expressed in Java rather than
 * left to review.
 */
public interface RawWriter {

	/**
	 * Append messages to {@code raw.stream_message}.
	 *
	 * @return rows written
	 */
	long write(List<RawMessage> messages) throws SQLException, IOException;
}
