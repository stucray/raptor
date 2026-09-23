package com.stucray.raptor.recorder;

/**
 * Why a batch left the normal path.
 *
 * <p>Both values are the same root cause — the database not accepting writes —
 * seen from the two ends of the pipeline. That is worth stating, because the
 * design comment they replace listed a GC pause as a second trigger and the
 * arithmetic does not support it: 50,000 slots at a ~200 msg/s peak is about
 * 250 seconds of book, so overflowing the queue takes a stall of minutes, not
 * the tens of milliseconds a collector costs.
 */
public enum SpillCause {

	/** The writer's COPY failed: Postgres restarting, connection dead. Fails fast. */
	DB_UNAVAILABLE,

	/**
	 * The queue filled and the read loop refused to wait.
	 *
	 * <p>The quiet half of the same failure. A wedged connection or a partition to
	 * Postgres means COPY neither returns nor throws; the writer stops draining,
	 * and the queue fills over the following minutes. A COPY with no timeout never
	 * reaches {@link #DB_UNAVAILABLE} at all, which is why this exists as a
	 * separate signal rather than a redundancy.
	 */
	QUEUE_FULL
}
