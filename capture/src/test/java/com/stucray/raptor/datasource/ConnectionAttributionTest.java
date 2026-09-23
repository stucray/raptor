package com.stucray.raptor.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Each identity names itself to the server, so a refused statement says who.
 *
 * <p>Two identities against one database is what replaced the two-process split
 * (PRD #73), and the plan for when the boundary trips was that the log would
 * name the offender. It could not: {@code HikariConfig.setPoolName} labels the
 * pool's own threads and metrics and never reaches PostgreSQL, so both pools
 * arrived as pgjdbc's default {@code PostgreSQL JDBC Driver} and nothing
 * server-side could tell the owner's connections from the read identity's
 * (#156).
 *
 * <p>Asserted through the connection rather than off the configuration, because
 * reading the property back is exactly the test that passed while the setting
 * did nothing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("Each identity names itself in pg_stat_activity")
class ConnectionAttributionTest {

	/** The restricted identity: Spring's {@code @Primary}, what the read side gets. */
	@Autowired JdbcClient read;

	@Autowired @Acquisition JdbcClient owner;

	@Test
	void theOwnersConnectionsSayWhoTheyAre() {
		assertThat(applicationNameOf(owner)).isEqualTo("raptor-capture");
	}

	@Test
	void theReadIdentitysConnectionsSayWhoTheyAre() {
		assertThat(applicationNameOf(read)).isEqualTo("paddock-read");
	}

	/**
	 * The name this very connection is known by on the server — not the name the
	 * pool believes it set.
	 */
	private static String applicationNameOf(JdbcClient jdbc) {
		return jdbc.sql("select application_name from pg_stat_activity where pid = pg_backend_pid()")
				.query(String.class).single();
	}
}
