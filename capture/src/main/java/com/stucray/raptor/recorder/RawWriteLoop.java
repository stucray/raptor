package com.stucray.raptor.recorder;

import com.stucray.raptor.rawstore.RawMessage;
import com.stucray.raptor.rawstore.RawWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the queue into {@code raw.stream_message}, one COPY per batch.
 *
 * <p><b>Time-bounded first.</b> A batch ends at {@code flushInterval} or
 * {@code batchSize}, whichever comes first, and in normal operation it is always
 * the timer: COPY sustains &gt;100k rows/s against a ~200 msg/s peak, so the
 * batch size is a ceiling that protects memory rather than a throughput knob.
 * The interval is the durability window and nothing else.
 *
 * <p><b>A dedicated platform thread, and the usual reason for that is wrong.</b>
 * It is not that blocking JDBC must not be unmounted — unmounting is exactly what
 * a virtual thread blocked on socket I/O is for, it is invisible to the driver,
 * and since JEP 491 even {@code synchronized} no longer pins. The real reason is
 * CPU occupancy: building a COPY buffer for a thousand messages is real work, and
 * a virtual thread doing it occupies its carrier for the duration, delaying every
 * other virtual thread sharing that carrier — including the read loop, the one
 * thread in this system that must never be starved. The writer population is also
 * exactly one, permanently, and virtual threads buy nothing at one.
 */
final class RawWriteLoop implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(RawWriteLoop.class);

	/** How far {@link #sqlStateOf} will walk. See its javadoc for why it is bounded. */
	private static final int MAX_CAUSE_DEPTH = 16;

	private final BlockingQueue<RawMessage> queue;
	private final RawWriter writer;
	private final TransactionTemplate transactions;
	private final SpillSink spill;
	private final Quarantine quarantine;
	private final int batchSize;
	private final long flushNanos;

	private final AtomicLong written = new AtomicLong();
	private final AtomicLong batches = new AtomicLong();
	private volatile boolean running = true;

	RawWriteLoop(BlockingQueue<RawMessage> queue, RawWriter writer, TransactionTemplate transactions,
			SpillSink spill, Quarantine quarantine, int batchSize, long flushNanos) {
		this.queue = queue;
		this.writer = writer;
		this.transactions = transactions;
		this.spill = spill;
		this.quarantine = quarantine;
		this.batchSize = batchSize;
		this.flushNanos = flushNanos;
	}

	@Override
	public void run() {
		// Note the exit condition: not `running`, but running-or-anything-left.
		// Shutdown must commit what was already accepted from the socket, or a
		// clean stop costs the tail of a match — which is the one loss this whole
		// design is built to make impossible.
		while (running || !queue.isEmpty()) {
			List<RawMessage> batch = nextBatch();
			if (batch.isEmpty()) {
				continue;
			}
			write(batch);
		}
	}

	/**
	 * Write one batch, and deal with the two quite different ways that fails.
	 *
	 * <p><b>Unavailable and refused are not the same thing</b>, and treating them
	 * alike is what #271 fixes. A database that cannot answer wants the batch put
	 * somewhere safe until it can — that is the spill, and retrying is exactly
	 * right. A database that answered "no" to <em>these bytes</em> will answer
	 * "no" to them forever: spilling them means {@code SpillDrain} replays the
	 * same doomed file every minute, {@code SpillIngest} never reaches the commit
	 * that would unlink it, and files accumulate at capture rate behind the one
	 * that cannot succeed.
	 *
	 * <p>The classification is the SQLSTATE, and the default is the safe one:
	 * only classes 22 and 23 are treated as refusals, everything else spills as
	 * before. So an unrecognised state, a wrapped exception this cannot see
	 * inside, a timeout, a disk-full 53100 — all still take the path that keeps
	 * the messages on disk.
	 */
	// Package-private so the integration test can hand it one batch against a
	// real PostgreSQL without going through the queue.
	void write(List<RawMessage> batch) {
		try {
			transactions.executeWithoutResult(status -> {
				try {
					writer.write(batch);
				} catch (Exception e) {
					throw new WriteFailed(e);
				}
			});
			written.addAndGet(batch.size());
			batches.incrementAndGet();
		} catch (RuntimeException e) {
			String sqlState = sqlStateOf(e);
			if (sqlState == null || !refusal(sqlState)) {
				// The batch is handed on intact rather than retried in place, because
				// retrying holds the queue open behind a database that may be down for
				// ten minutes.
				log.error("batch of {} message(s) not accepted by the database (SQLSTATE {}); "
						+ "spilling", batch.size(), sqlState, e);
				spill.spill(batch, SpillCause.DB_UNAVAILABLE);
				return;
			}
			refused(batch, sqlState, e);
		}
	}

	/**
	 * A refusal names ONE bad message, and the batch around it is almost always
	 * fine — so find it rather than condemning its thousand neighbours.
	 *
	 * <p>COPY is all-or-nothing, so one unacceptable row takes the whole batch
	 * down with it. Quarantining all of it would be safe (nothing
	 * is lost; the rows are held whole) and badly wrong in practice: up to
	 * {@code batchSize} perfectly good messages would sit outside the system of
	 * record, invisible to every projection, until somebody replayed them by
	 * hand. Halving costs about 2·log2(n) COPYs to isolate one bad row — roughly
	 * twenty for a batch of 1,024 — and only ever runs when something has already
	 * gone wrong.
	 *
	 * <p>Recursion depth is log2(batchSize), so ten at the configured 1,024 and
	 * bounded by the queue's own capacity in any case.
	 */
	private void refused(List<RawMessage> batch, String sqlState, RuntimeException failure) {
		if (batch.size() == 1) {
			String detail = failure.getCause() == null
					? failure.toString() : failure.getCause().toString();
			if (!quarantine.hold(batch, sqlState, detail)) {
				// The quarantine could not take it either. Keep it on disk: the
				// drain will churn on it, which is the old behaviour and is still
				// better than dropping a message from the system of record.
				spill.spill(batch, SpillCause.DB_UNAVAILABLE);
			}
			return;
		}
		int half = batch.size() / 2;
		write(batch.subList(0, half));
		write(batch.subList(half, batch.size()));
	}

	/**
	 * SQLSTATE class 22 is a data exception and 23 an integrity constraint
	 * violation. Both mean the server read the row and would not have it.
	 *
	 * <p>Measured against this server rather than recalled: 23503 is a session id
	 * with no {@code raw.capture_session} row, 23514 a {@code pt} outside every
	 * partition range and also the provenance check, 23502 a null in a not-null
	 * column, 22P02 a payload that is not valid JSON.
	 *
	 * <p>None of them has ever fired here, and the realistic causes are
	 * schema-shaped rather than payload-shaped — a migration adding a constraint
	 * under a running recorder, a partition set that ran out (#267). That is
	 * still worth defending: those are deploy-time events, and the recorder
	 * should survive one rather than wedge on it.
	 *
	 * <p>Deliberately NOT included: class 08 (connection), 53 (insufficient
	 * resources, which is disk-full and out-of-memory), 57P01 (admin shutdown),
	 * 40001 (serialization failure). Every one of those is a retry that can work,
	 * and the spill is what it is for.
	 */
	private static boolean refusal(String sqlState) {
		return sqlState.startsWith("22") || sqlState.startsWith("23");
	}

	/**
	 * The first SQLSTATE in the cause chain, or null if nothing carries one.
	 *
	 * <p>Bounded rather than walked to the end. The exception arrives wrapped
	 * several deep — {@code WriteFailed} around Spring's translation around
	 * pgjdbc's {@code PSQLException} — and a cycle anywhere in that chain on the
	 * write path would hang the one thread that must never stop draining the
	 * queue. A self-referencing cause is the case that is usually guarded, and it
	 * is not the only shape a cycle takes; a depth limit covers all of them and
	 * needs no reference comparison to do it. Sixteen is far past any real chain
	 * here.
	 */
	private static @Nullable String sqlStateOf(Throwable thrown) {
		Throwable t = thrown;
		for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
			if (t instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
		}
		return null;
	}

	/** Up to {@code batchSize} messages, or whatever has arrived within the window. */
	private List<RawMessage> nextBatch() {
		List<RawMessage> batch = new ArrayList<>(Math.min(batchSize, 1024));
		long deadline = System.nanoTime() + flushNanos;
		while (batch.size() < batchSize) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				break;
			}
			RawMessage message;
			try {
				message = queue.poll(remaining, TimeUnit.NANOSECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				running = false;
				break;
			}
			if (message == null) {
				break;
			}
			batch.add(message);
			queue.drainTo(batch, batchSize - batch.size());
		}
		return batch;
	}

	/** Stop once the queue is empty. */
	void stop() {
		running = false;
	}

	long written() {
		return written.get();
	}

	long batches() {
		return batches.get();
	}

	/** Carries a checked write failure out of the transaction callback. */
	static final class WriteFailed extends RuntimeException {
		WriteFailed(Throwable cause) {
			super(cause);
		}
	}
}
