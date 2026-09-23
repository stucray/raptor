package com.stucray.raptor.projection;

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
 * One close-out at a time, across every JVM pointed at this database (#334).
 *
 * <p><b>Needed since the close-out became a request.</b> While its only trigger
 * was an in-process cron, a second run could not start while the first was in
 * flight. Now launchd fires it over HTTP, and so can anyone with a shell — and a
 * launchd catch-up on wake landing beside a hand-run POST would run two archive
 * sweeps into {@code raw} at once, each opening its own ledger row.
 *
 * <p><b>Session-scoped and held on its own connection</b>, for the same reason as
 * {@link com.stucray.raptor.recorder.CaptureLease}: a run outlives every
 * transaction it takes, so a transaction-scoped lock like {@link ProjectionLock}'s
 * would be dropped before the sweep had started. A killed JVM's connection closes
 * and PostgreSQL frees the lock with it, so there is no stale state to clear — the
 * reason this is not a "RUNNING row exists" check on the ledger, which a killed
 * run leaves behind forever.
 *
 * <p><b>Refuses rather than waits</b>, unlike {@link ProjectionLock}. A second
 * close-out asked for while one is running wants the same thing the running one
 * is already doing; queueing it would sweep the archive twice for nothing.
 *
 * <p>The {@code (int, int)} key space, like the projection's, so it can never meet
 * the capture lease's single-{@code bigint} key.
 */
@Component
class CloseOutLock {

	/** "CO". ASCII, which is what it reads as in {@code pg_locks.classid}. */
	static final int CLOSE_OUT = 0x434F;

	private static final Logger log = LoggerFactory.getLogger(CloseOutLock.class);

	private final DataSource dataSource;

	CloseOutLock(@Acquisition DataSource acquisitionDataSource) {
		this.dataSource = acquisitionDataSource;
	}

	/**
	 * Take the lock, or return null if another run holds it.
	 *
	 * @throws IllegalStateException if the database cannot be asked at all, which
	 *     is a failure to report rather than a run in progress to defer to
	 */
	@Nullable Held tryHold() {
		Connection candidate = null;
		try {
			candidate = dataSource.getConnection();
			try (PreparedStatement lock =
					candidate.prepareStatement("select pg_try_advisory_lock(?, 0)")) {
				lock.setInt(1, CLOSE_OUT);
				try (ResultSet result = lock.executeQuery()) {
					if (result.next() && result.getBoolean(1)) {
						Held held = new Held(candidate);
						candidate = null;
						return held;
					}
				}
			}
			return null;
		} catch (SQLException e) {
			throw new IllegalStateException("could not take the close-out lock", e);
		} finally {
			closeQuietly(candidate);
		}
	}

	/** The lock, released by closing it. */
	static final class Held implements AutoCloseable {

		private final Connection connection;

		private Held(Connection connection) {
			this.connection = connection;
		}

		/**
		 * Unlock, then return the connection. Closing alone would free the lock,
		 * but a pooled connection is not closed, only returned — and a session
		 * lock left on it would follow the connection to whatever borrows it next.
		 */
		@Override
		public void close() {
			try (connection;
					PreparedStatement unlock =
							connection.prepareStatement("select pg_advisory_unlock(?, 0)")) {
				unlock.setInt(1, CLOSE_OUT);
				unlock.execute();
			} catch (SQLException e) {
				log.warn("close-out lock could not be released cleanly ({})", e.getMessage());
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
			// Already failing or already answered; nothing more to say.
		}
	}
}
