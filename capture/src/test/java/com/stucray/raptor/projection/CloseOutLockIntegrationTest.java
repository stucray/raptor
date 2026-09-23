package com.stucray.raptor.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The close-out lock against a real PostgreSQL (#334): a launchd catch-up on wake
 * and a hand-run POST must not both sweep the archive into {@code raw}.
 *
 * <p><b>The rival holder is a connection OUTSIDE the pool.</b> The test pool is
 * two connections and the shared context may already have one checked out, so a
 * second pooled hold can simply wait for a connection that never comes. A
 * separate session is also the more honest rival: it is what another JVM is.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("The close-out lock admits one run at a time and frees itself when the run ends")
class CloseOutLockIntegrationTest {

	@Autowired CloseOutLock lock;
	@Autowired @Acquisition DataSource dataSource;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	@DisplayName("a hold is refused while another session holds the lock, and granted after")
	void refusedWhileHeldElsewhere() throws SQLException {
		try (Connection rival = unpooled()) {
			assertThat(advisory(rival, "pg_try_advisory_lock")).isTrue();

			assertThat(lock.tryHold()).as("held by another session, so refused").isNull();

			assertThat(advisory(rival, "pg_advisory_unlock")).isTrue();
		}

		CloseOutLock.Held held = lock.tryHold();
		assertThat(held).as("free again, so granted").isNotNull();
		held.close();
	}

	@Test
	@DisplayName("closing a hold releases it, not merely returns the connection to the pool")
	void closeReleases() throws SQLException {
		CloseOutLock.Held held = lock.tryHold();
		assertThat(held).isNotNull();
		held.close();

		// Asked from another session rather than by holding again: the pooled
		// connection just returned may be the one handed out next, and a session
		// lock is re-entrant on its own connection — so a second hold would
		// succeed even if close() had never unlocked, and prove nothing.
		try (Connection rival = unpooled()) {
			assertThat(advisory(rival, "pg_try_advisory_lock"))
					.as("released on close, not left on a connection that merely went back "
							+ "to the pool, where it would refuse the next night's run")
					.isTrue();
			advisory(rival, "pg_advisory_unlock");
		}
		assertThat(jdbc.sql("""
						select count(*) from pg_locks
						where locktype = 'advisory' and classid = ?""")
				.param(CloseOutLock.CLOSE_OUT).query(Long.class).single())
				.isZero();
	}

	private Connection unpooled() throws SQLException {
		HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
		return DriverManager.getConnection(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
	}

	private static boolean advisory(Connection connection, String function) throws SQLException {
		try (PreparedStatement statement =
				connection.prepareStatement("select " + function + "(?, 0)")) {
			statement.setInt(1, CloseOutLock.CLOSE_OUT);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() && result.getBoolean(1);
			}
		}
	}
}
