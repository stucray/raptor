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

	/**
	 * Put back messages whose earlier write had an unknown outcome (#40): append
	 * those not already stored, and count those that are.
	 *
	 * <p>The recorder spills a batch when it cannot tell whether the write
	 * committed, and "cannot tell" includes a COMMIT that reached the server while
	 * its reply never reached the recorder. So a replayed message may already be
	 * stored. One that is, IDENTICALLY, is counted and not written again. One whose
	 * capture-session key is stored with different content is not the same message,
	 * and the whole replay is refused, because skipping it would hide exactly the
	 * case the key exists to catch.
	 *
	 * <p>Capture-session rows only (spill files hold nothing else), and only inside
	 * a transaction: the comparison and the append must see the same table.
	 *
	 * @return how many were written and how many were already stored
	 * @throws IllegalStateException if a stored row shares a message's key and not
	 *     its content, or there is no transaction
	 */
	Replayed replay(List<RawMessage> messages) throws SQLException, IOException;

	/**
	 * What a replay did.
	 *
	 * @param written messages appended
	 * @param alreadyPresent messages that were already stored, identically
	 */
	record Replayed(long written, long alreadyPresent) {}
}
