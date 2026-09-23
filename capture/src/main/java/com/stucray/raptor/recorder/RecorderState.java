package com.stucray.raptor.recorder;

/** What the recorder is doing, as one word for a health endpoint to report. */
public enum RecorderState {

	/** Switched off by configuration. */
	DISABLED,

	/**
	 * On, but with nothing to connect to.
	 *
	 * <p>The honest state of this application until S6 lands the live source. It
	 * is not an error and not standby: no other instance is recording either.
	 */
	NO_SOURCE,

	/**
	 * Another instance holds the capture lease.
	 *
	 * <p>Inert, not fatal, and not degraded: single-writer is working. A second
	 * instance that refused to boot would make a stray development JVM an outage.
	 */
	STANDBY,

	/** Holding the lease and receiving. */
	RECORDING,

	/** Holding the lease, between streams. */
	RECONNECTING,

	/**
	 * Holding the lease, with nothing in scope to connect to.
	 *
	 * <p>Distinct from {@link #RECONNECTING} because the two are opposite news
	 * told in the same word. Between streams means a connection was lost and is
	 * being rebuilt — transient, and alarming if it persists. Idle means no
	 * fixture is inside the horizon, which at 03:00 on a Tuesday is correct and
	 * is expected to persist for hours. A single word for both is how a watch
	 * either cries wolf every night or misses a real outage (#144).
	 *
	 * <p>Also distinct from {@link #NO_SOURCE}, which is about configuration:
	 * nothing to connect <em>with</em>, rather than nothing to connect
	 * <em>to</em>.
	 */
	IDLE,

	/** Shut down. */
	STOPPED
}
