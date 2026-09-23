package com.stucray.raptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.datasource.Acquisition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S0's acceptance: the app boots and Flyway lands the schema split on a real
 * PostgreSQL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

	// information_schema is filtered by privilege, so as the read identity this
	// would see `query` and nothing else — which is itself the boundary holding,
	// but not what this test is about.
	@Autowired
	@Acquisition
	JdbcClient jdbc;

	@Test
	void createsTheThreeSchemas() {
		List<String> schemas = jdbc
				.sql("select schema_name from information_schema.schemata where schema_name in ('raw', 'query', 'batch')")
				.query(String.class)
				.list();

		assertThat(schemas).containsExactlyInAnyOrder("raw", "query", "batch");
	}

	@Test
	void landsBatchTablesInTheBatchSchema() {
		List<String> tables = jdbc
				.sql("select table_name from information_schema.tables where table_schema = 'batch'")
				.query(String.class)
				.list();

		// Lower-cased by PostgreSQL: the DDL's unquoted identifiers fold down.
		assertThat(tables).contains(
				"batch_job_instance",
				"batch_job_execution",
				"batch_job_execution_params",
				"batch_step_execution",
				"batch_step_execution_context",
				"batch_job_execution_context");
	}

	/**
	 * The SET LOCAL in V2 must not leak: later migrations default to `query`.
	 */
	@Test
	void batchSearchPathDidNotLeakToOtherSchemas() {
		List<String> strays = jdbc
				.sql("""
						select table_name from information_schema.tables
						where table_schema in ('raw', 'query')
						  and table_name like 'batch%'
						""")
				.query(String.class)
				.list();

		assertThat(strays).isEmpty();
	}

	/**
	 * The read side can see `query` and cannot see `raw` at all. This is the
	 * contract between the two services, and it is a grant, not a convention.
	 */
	@Test
	void readerRoleReachesQueryButNotRaw() {
		assertThat(jdbc.sql("select has_schema_privilege('paddock_reader', 'query', 'usage')")
				.query(Boolean.class).single())
				.as("paddock_reader must be able to read the query contract")
				.isTrue();

		assertThat(jdbc.sql("select has_schema_privilege('paddock_reader', 'raw', 'usage')")
				.query(Boolean.class).single())
				.as("paddock_reader must have no access to the system of record — "
						+ "if the read side needs something from raw, that is a missing "
						+ "projection, not a missing grant")
				.isFalse();
	}
}
