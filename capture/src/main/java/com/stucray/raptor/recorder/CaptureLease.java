package com.stucray.raptor.recorder;

import com.stucray.raptor.datasource.Acquisition;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single-writer, held as a PostgreSQL session-level advisory lock.
 *
 * <p>Two recorders writing one capture is the failure this prevents, and it used
 * to be an OS guarantee: launchd will not start a second instance of a job. A
 * resident service has no such backstop, so the guarantee has to be rebuilt —
 * and the database is the only thing both instances can agree on.
 *
 * <p><b>Not a Batch {@code JobInstance}.</b> Batch's uniqueness is
 * {@code (job name, identifying parameters)}; a resident recorder has no natural
 * identifying parameter, and the obvious invention is a date — which puts back
 * the fixed nightly window this whole design deletes. Worse, a crashed JVM
 * leaves {@code BATCH_JOB_EXECUTION} in {@code STARTED} and blocks restart until
 * someone abandons it by hand, at night, under exactly the conditions where
 * nobody is available to. Batch's {@code SERIALIZABLE}-on-create protection is
 * still the right tool for the finite jobs; it is the wrong one here.
 *
 * <p><b>Why session-level, and why its own connection.</b> A session lock is
 * released by PostgreSQL when the connection ends, so a {@code kill -9} frees it
 * within seconds without anything having to clean up — the property that makes
 * this safe to depend on. That only holds if the connection is ours for the
 * lease's whole life: a pooled connection returned between statements would take
 * the lock with it. So the lease checks one connection out and keeps it.
 *
 * <p>A second instance is <b>inert, not fatal</b>. It reports {@code standby},
 * runs everything else it is for, and takes over on the next start if the holder
 * is gone. Refusing to boot would make a stray development JVM an outage.
 */
@Component
class CaptureLease implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CaptureLease.class);

	/** Short: this runs on the health path, and a hung database is its own signal. */
	private static final int VALIDATION_TIMEOUT_SECONDS = 2;

	private final DataSource dataSource;
	private final long key;

	private @Nullable Connection connection;

	CaptureLease(@Acquisition DataSource acquisitionDataSource, RecorderProperties properties) {
		this.dataSource = acquisitionDataSource;
		this.key = properties.leaseKey();
	}

	/**
	 * Try to become the writer.
	 *
	 * @return whether this instance now holds the lease. False means another
	 *     instance holds it, or the database could not be reached to ask — both
	 *     are reasons to stand by rather than to record.
	 */
	synchronized boolean acquire() {
		if (connection != null) {
			return true;
		}
		Connection candidate = null;
		try {
			candidate = dataSource.getConnection();
			candidate.setAutoCommit(true);
			if (tryLock(candidate)) {
				connection = candidate;
				log.info("capture lease {} acquired; this instance is the writer", key);
				return true;
			}
			candidate.close();
			log.info("capture lease {} is held elsewhere; this instance is on standby", key);
			return false;
		} catch (SQLException e) {
			closeQuietly(candidate);
			log.warn("could not ask for the capture lease ({}); standing by", e.getMessage());
			return false;
		}
	}

	/**
	 * Whether this instance is the writer right now.
	 *
	 * <p><b>{@code isClosed()} is not enough</b>, and the difference is the whole
	 * point of the check. It reports a client-side flag: after the server has
	 * terminated the backend — a crash, a {@code pg_terminate_backend}, an
	 * administrator restarting the database — a JDBC connection goes on saying it
	 * is open until something tries to use it. An instance in that state has
	 * already lost the lock (PostgreSQL released it when the session ended) while
	 * still believing it is the single writer, which is exactly the state that
	 * lets two recorders write one capture. {@code isValid} costs a round trip and
	 * asks the server, which is the only party that knows.
	 */
	synchronized boolean held() {
		Connection held = connection;
		if (held == null) {
			return false;
		}
		try {
			if (!held.isClosed() && held.isValid(VALIDATION_TIMEOUT_SECONDS)) {
				return true;
			}
		} catch (SQLException e) {
			log.warn("capture lease connection could not be checked ({}); treating it as lost",
					e.getMessage());
		}
		// The lock went with the connection. Say so, rather than let the supervisor
		// believe it is still the single writer.
		connection = null;
		return false;
	}

	/**
	 * Give the lease up.
	 *
	 * <p>Closing the connection alone would do it — that is the property this
	 * design rests on — but unlocking explicitly first means an orderly shutdown
	 * hands over immediately rather than at whatever pace the pool closes sockets.
	 */
	@Override
	public synchronized void close() {
		Connection held = connection;
		connection = null;
		if (held == null) {
			return;
		}
		try (held; PreparedStatement unlock = held.prepareStatement("select pg_advisory_unlock(?)")) {
			unlock.setLong(1, key);
			unlock.execute();
			log.info("capture lease {} released", key);
		} catch (SQLException e) {
			// The connection is closing regardless, and that is what actually frees
			// the lock. Nothing to do but say what happened.
			log.warn("capture lease {} could not be unlocked cleanly ({}); closing the "
					+ "connection releases it anyway", key, e.getMessage());
		}
	}

	private boolean tryLock(Connection candidate) throws SQLException {
		try (PreparedStatement lock =
				candidate.prepareStatement("select pg_try_advisory_lock(?)")) {
			lock.setLong(1, key);
			try (ResultSet result = lock.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}

	private static void closeQuietly(@Nullable Connection candidate) {
		if (candidate == null) {
			return;
		}
		try {
			candidate.close();
		} catch (SQLException ignored) {
			// Nothing useful to do: we are already reporting the failure that got here.
		}
	}
}
