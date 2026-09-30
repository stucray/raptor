package com.stucray.raptor.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The migrations are not bound by the write path's socket timeout (#33).
 *
 * <p>V38 copies the whole system of record in one statement, which on live takes
 * minutes. The owner's pool gives up on a statement after {@code socketTimeout},
 * so run through it the migration would be abandoned part way and the backend
 * would never start. The timeout is shrunk to one second here so that a
 * two-second statement tells the two connections apart.
 */
@SpringBootTest(properties = "raptor.datasource.socket-timeout=1s")
@Import(TestcontainersConfiguration.class)
@DisplayName("A migration can outlast the write path's socket timeout")
class MigrationConnectionTest {

	private static final String SLOW = "select pg_sleep(2)::text";

	@Autowired AcquisitionMigrations migrations;
	@Autowired @Acquisition JdbcClient pool;

	@Test
	@DisplayName("a statement longer than the timeout completes on the migrations' connection")
	void aLongStatementCompletes() {
		assertThat(JdbcClient.create(migrations.dataSource()).sql(SLOW)
				.query(String.class).single()).isEmpty();
	}

	/** The witness: the same statement on the owner's pool is cut off. */
	@Test
	@DisplayName("the same statement is abandoned on the owner's pool")
	void theOwnersPoolAbandonsIt() {
		assertThatThrownBy(() -> pool.sql(SLOW).query(String.class).single())
				.hasStackTraceContaining("Read timed out");
	}
}
