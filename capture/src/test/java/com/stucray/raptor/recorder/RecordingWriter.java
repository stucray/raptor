package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import com.stucray.raptor.rawstore.RawWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** A {@link RawWriter} that records the batches it was given, and can refuse them. */
final class RecordingWriter implements RawWriter {

	private final List<List<RawMessage>> batches = new CopyOnWriteArrayList<>();
	private final AtomicInteger failuresRemaining = new AtomicInteger();
	private volatile @Nullable Predicate<RawMessage> refuses;
	private volatile String refusalState = "23514";
	private final AtomicInteger refusals = new AtomicInteger();

	@Override
	public long write(List<RawMessage> messages) throws SQLException {
		if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			throw new SQLException("connection refused (simulated)", "08006");
		}
		Predicate<RawMessage> refused = this.refuses;
		if (refused != null && messages.stream().anyMatch(refused)) {
			// COPY is all-or-nothing: one unacceptable row takes the statement with
			// it, however many good ones it was travelling with. That is the whole
			// reason RawWriteLoop has to bisect rather than condemn the batch.
			refusals.incrementAndGet();
			throw new SQLException("rejected by the server (simulated)", refusalState);
		}
		batches.add(List.copyOf(messages));
		return messages.size();
	}

	/**
	 * Refuse any batch containing a message this matches, with {@code sqlState}.
	 *
	 * <p>Models a row the server will not have — a missing partition, a null in a
	 * not-null column, or a payload carrying a NUL escape that {@code jsonb}
	 * refuses. Unlike {@link #failNext}, it never stops: the same bytes are
	 * refused every time they are offered, which is the property that makes
	 * spilling them a permanent retry loop.
	 */
	void refuse(Predicate<RawMessage> message, String sqlState) {
		this.refuses = message;
		this.refusalState = sqlState;
	}

	/** How many COPY attempts were refused — the cost of isolating the bad row. */
	int refusals() {
		return refusals.get();
	}

	/** Make the next {@code n} writes fail the way a restarting Postgres does. */
	void failNext(int n) {
		failuresRemaining.set(n);
	}

	List<List<RawMessage>> batches() {
		return List.copyOf(batches);
	}

	List<RawMessage> written() {
		List<RawMessage> all = new ArrayList<>();
		batches.forEach(all::addAll);
		return all;
	}
}
