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
	 * What the read identity can see of `raw` and `batch`, asserted as a SET.
	 *
	 * <p>It saw nothing there until V35 (paddock#321): the operational screens
	 * read capture's file and run records, and nothing else. A set, not a
	 * membership check, because the hazard is an EXTRA table — above all
	 * {@code raw.stream_message}, whose payloads no screen should reach — and a
	 * test that asserts each wanted table is readable cannot see one.
	 */
	@Test
	void readerSeesCapturesRecordsButNoPayload() {
		List<String> readable = jdbc.sql("""
				select n.nspname || '.' || c.relname
				from pg_class c
				join pg_namespace n on n.oid = c.relnamespace
				where n.nspname in ('raw', 'batch') and c.relkind in ('r', 'p', 'v')
				  and has_any_column_privilege('paddock_reader', c.oid, 'select')""")
				.query(String.class)
				.list();

		assertThat(readable).containsExactlyInAnyOrder(
				"raw.historic_file", "raw.capture_file", "raw.football_file",
				"batch.batch_job_instance", "batch.batch_job_execution");
	}

	/**
	 * {@code raw.football_file} keeps each CSV's bytes, so it is a payload table
	 * as well as a file ledger, and V35 grants it by column. The witness is that
	 * a column beside {@code content} IS readable: without it, this would pass
	 * just as well against a table the reader could not read at all.
	 */
	@Test
	void readerSeesTheFootballFileLedgerButNotItsContent() {
		String privileges = jdbc.sql("""
				select has_column_privilege('paddock_reader', 'raw.football_file', 'fetched_at', 'select')
				    || '/' || has_column_privilege('paddock_reader', 'raw.football_file', 'content', 'select')""")
				.query(String.class)
				.single();

		assertThat(privileges).isEqualTo("true/false");
	}

	/** And it writes none of it: V35 grants select, and select is all. */
	@Test
	void readerWritesNothingInRawOrBatch() {
		Long writable = jdbc.sql("""
				select count(*)
				from pg_class c
				join pg_namespace n on n.oid = c.relnamespace
				where n.nspname in ('raw', 'batch') and c.relkind in ('r', 'p')
				  and (has_table_privilege('paddock_reader', c.oid, 'insert')
				    or has_table_privilege('paddock_reader', c.oid, 'update')
				    or has_table_privilege('paddock_reader', c.oid, 'delete')
				    or has_table_privilege('paddock_reader', c.oid, 'truncate'))""")
				.query(Long.class)
				.single();

		assertThat(writable).isZero();
	}
}
