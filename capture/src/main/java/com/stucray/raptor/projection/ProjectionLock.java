package com.stucray.raptor.projection;

import com.stucray.raptor.datasource.Acquisition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * One projection of one unit at a time, held for the length of its transaction.
 *
 * <p>This replaces a guarantee that used to be a side effect. Batch's
 * {@code (JOB_NAME, JOB_KEY)} uniqueness kept two launches off the same month,
 * but only by keeping the <em>second</em> launch off it permanently — and a
 * projection whose entire purpose is to be rebuilt cannot be a thing that runs
 * once per database and never again (#113). Uniqueness and mutual exclusion are
 * separate requirements; only the second one was ever wanted here.
 *
 * <p><b>Transaction-scoped, not session-scoped.</b> {@code pg_advisory_xact_lock}
 * is released by PostgreSQL on commit or rollback, so there is nothing to close,
 * nothing to leak, and no state to reconcile after a crashed JVM — where
 * {@link com.stucray.raptor.recorder.CaptureLease} has to keep a connection
 * checked out for the lease's whole life because a recorder's lease outlives
 * every transaction it takes. The projection's does not: the unit of work and
 * the unit of exclusion are the same step.
 *
 * <p><b>It depends on #110 and would have been theatre before it.</b> A
 * transaction-scoped lock is mutual exclusion only if there is a transaction,
 * on the connection the writes actually go out through. Until the step asked for
 * the {@code @Acquisition} transaction manager, the tasklet's statements ran on
 * autocommit and this lock would have been taken and dropped again before the
 * first row was written.
 *
 * <p><b>The two-integer key space, deliberately.</b> PostgreSQL keeps
 * {@code (int, int)} advisory locks in a different space from single-{@code
 * bigint} ones, and the recorder's capture lease lives in the latter. Choosing
 * this form means a projection can never collide with the lease that decides
 * which instance is the single writer — a collision that would present as a
 * recorder mysteriously standing by during a re-projection.
 */
@Component
class ProjectionLock {

	/**
	 * "PC" — the capture ledger, whose unit is the whole table. ASCII, which is
	 * what it reads as in {@code pg_locks.classid}.
	 *
	 * <p>The only namespace left here since #316. "PH", "PL" and "PF" — historic
	 * month, live market, football archive — went to overround-analysis with the
	 * projections they guarded, and are its keys now, unchanged, so a rollback
	 * that brought paddock's projection back would serialise against it rather
	 * than double-write.
	 */
	static final int CAPTURE_LEDGER = 0x5043;

	private final JdbcClient jdbc;

	ProjectionLock(@Acquisition JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Wait for exclusive use of one projection unit until this transaction ends.
	 *
	 * <p>Waits rather than refuses. A second launch of the same month is a
	 * re-projection, and a re-projection that is asked for should happen — it just
	 * must not happen at the same time as the one in flight, because both would be
	 * deleting and re-inserting the same markets. Failing fast would turn "you
	 * asked twice" into an error an operator has to interpret; waiting turns it
	 * into the second run they asked for.
	 *
	 * <p>Called through the {@code @Acquisition} {@code JdbcClient}, so it joins
	 * the step's transaction by way of {@code DataSourceUtils} exactly as
	 * {@code CopyBuffer} does. Taking it on any other connection would lock
	 * something nothing else is writing.
	 */
	void await(int namespace, int unit) {
		jdbc.sql("select pg_advisory_xact_lock(?, ?)").params(namespace, unit).query().singleValue();
	}
}
