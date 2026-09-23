package com.stucray.raptor.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * overround-analysis's role, observed from inside a login as it (#247).
 *
 * <p>Same stance as {@link SystemOfRecordBoundaryTest}: these connect as the role
 * and watch PostgreSQL refuse, rather than read {@code has_table_privilege},
 * which would prove only that the migration ran. The contract is {@code query}
 * and {@code raw} read-only and an {@code analysis} schema of its own, which
 * since #315 includes the {@code team_alias} it masters. That it holds nothing in
 * {@code public} is the backend's {@code AnalysisRoleContractTest}, where that
 * schema is migrated.
 *
 * <p><b>{@code raw} became readable in V31 (#312)</b>, which reverses what V27's
 * header says. The reason is PRD #309: overround-analysis becomes the writer of
 * {@code query}, and a projection cannot be built from a schema it cannot read.
 * The boundary did not move — it was never "cannot see raw", it was "cannot
 * write raw", and {@link #cannotWriteRaw()} is where that now lives.
 *
 * <p><b>The projection tables in {@code query} became its own in V32 (#316)</b>:
 * owned, written, and migrated from its own Flyway history. The capture ledger in
 * the same schema did not move, and is still refused.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("overround-analysis's role reads raw, owns analysis and query's projection, and writes neither raw nor the ledger")
class AnalysisRoleBoundaryTest {

	static final String ROLE = "overround_analysis";

	@Autowired @Acquisition JdbcClient owner;

	@Autowired JdbcClient reader;

	private final JdbcClient analysis = JdbcClient.create(new DriverManagerDataSource(
			TestcontainersConfiguration.POSTGRES.getJdbcUrl(), ROLE,
			TestcontainersConfiguration.ANALYSIS_PASSWORD));

	@Test
	@DisplayName("every query table is readable")
	void readsEveryQueryTable() {
		List<String> tables = owner.sql("""
				select table_name from information_schema.tables
				where table_schema = 'query' and table_name not like 'flyway%'
				order by table_name
				""").query(String.class).list();

		// The witness: an empty list would make the loop below pass vacuously.
		assertThat(tables).as("the contract has tables to read").contains("price_tick", "market_runner");
		for (String table : tables) {
			assertThat(analysis.sql("select count(*) from query." + table).query(Long.class).single())
					.as("query.%s is part of the contract", table)
					.isNotNull();
		}
	}

	@Test
	@DisplayName("the system of record is readable, and the run ledger is not")
	void readsRawAndIsRefusedOnBatch() {
		// V31 (#312): analysis builds `query` itself now, which it cannot do
		// without reading what `query` is projected from. SELECT only — the half
		// that matters is asserted by cannotWriteRaw() below.
		assertThat(analysis.sql("select count(*) from raw.stream_message").query(Long.class).single())
				.as("analysis projects from raw, so it must be able to read it")
				.isNotNull();
		assertThat(analysis.sql("select count(*) from raw.football_file").query(Long.class).single())
				.as("the football archive is the first source it projects (#312)")
				.isNotNull();
		assertRefused("the run ledger is the write path's own bookkeeping",
				() -> analysis.sql("select count(*) from batch.batch_job_execution").query(Long.class).single());
	}

	@Test
	@DisplayName("raw cannot be written, which is the whole of the boundary")
	void cannotWriteRaw() {
		// `raw` is append-only, unreplayable and single-writer. V31 gave this
		// role SELECT; a reader cannot make the system of record wrong, and
		// these are the statements that would.
		assertRefused("a row analysis wrote into the system of record would have no capture behind it",
				() -> analysis.sql("insert into raw.football_file (path, sha256, bytes, content) "
						+ "values ('forged.csv', 'x', 0, '')").update());
		assertRefused("nor may it edit what was captured",
				() -> analysis.sql("update raw.football_file set path = 'forged.csv'").update());
		assertRefused("nor delete it",
				() -> analysis.sql("delete from raw.football_file").update());
	}

	@Test
	@DisplayName("a raw table added after the grant is readable without a new grant")
	void readsRawTableAddedLater() {
		// The default privileges in V31, asserted rather than assumed: a source
		// adapter's new table must be projectable on the day it lands. The
		// migration identity is the grantor, so the table is created as `owner`.
		owner.sql("create table raw.later_arrival (id int)").update();
		try {
			assertThat(analysis.sql("select count(*) from raw.later_arrival").query(Long.class).single())
					.as("V31's default privileges cover tables added after it ran")
					.isNotNull();
		} finally {
			owner.sql("drop table raw.later_arrival").update();
		}
	}

	@Test
	@DisplayName("it owns and writes query's projection tables (#316)")
	void ownsAndWritesTheProjection() {
		// V32: overround-analysis is the writer of the projection since #316, and
		// its owner, because its own Flyway history migrates these tables now.
		List<String> owned = owner.sql("""
				select c.relname from pg_class c
				where c.relnamespace = 'query'::regnamespace and c.relkind = 'r'
				  and pg_get_userbyid(c.relowner) = ?
				order by 1""").param(ROLE).query(String.class).list();
		assertThat(owned).containsExactly("football_match", "historic_market", "historic_price_tick",
				"historic_transition", "live_transition", "market", "market_runner", "market_span",
				"price_tick", "projection");

		long id = analysis.sql("insert into query.projection (job_name, partition_key, job_execution_id) "
				+ "values ('owned', '1999-01', 1) returning id").query(Long.class).single();
		assertThat(analysis.sql("delete from query.projection where id = ?").param(id).update()).isOne();
	}

	@Test
	@DisplayName("the capture ledger in query stays paddock's, and cannot be written")
	void cannotWriteTheCaptureLedger() {
		// capture_session, market_scope and capture_gap are capture operations,
		// refreshed by paddock, and leave for raptor's schema in #320 — not part
		// of what V32 handed over.
		assertRefused("the ledger records what capture did; analysis did none of it",
				() -> analysis.sql("delete from query.capture_gap").update());
		assertRefused("nor its sessions",
				() -> analysis.sql("delete from query.capture_session").update());
		assertRefused("nor its scope",
				() -> analysis.sql("delete from query.market_scope").update());
	}

	@Test
	@DisplayName("paddock still records the ledger's own projection runs")
	void paddockStillWritesProvenance() {
		// query.projection moved with the projection tables, but the ledger's rows
		// point at it too, so the migration identity keeps insert and update.
		long id = owner.sql("insert into query.projection (job_name, partition_key, job_execution_id) "
				+ "values ('ledger', 'probe', 1) returning id").query(Long.class).single();
		assertThat(owner.sql("update query.projection set partition_key = 'probe2' where id = ?")
				.param(id).update()).isOne();
	}

	@Test
	@DisplayName("a table it adds to query is readable by paddock's screens")
	void createsInQueryReadableByPaddockReader() {
		// V32's CREATE on the schema, plus the default privileges that keep
		// paddock_reader's screens working when the next query migration is
		// overround-analysis's rather than paddock's.
		analysis.sql("create table query.analysis_added (id int)").update();
		try {
			assertThat(reader.sql("select count(*) from query.analysis_added").query(Long.class).single())
					.isZero();
		} finally {
			analysis.sql("drop table query.analysis_added").update();
		}
	}

	@Test
	@DisplayName("nothing can be created in paddock's public")
	void cannotCreateInPublic() {
		assertRefused("paddock's public is not analysis's to add to",
				() -> analysis.sql("create table public.analysis_stray (id int)").update());
	}

	@Test
	@DisplayName("it owns the analysis schema and can build in it")
	void ownsAnalysisSchema() {
		assertThat(owner.sql("select nspowner::regrole::text from pg_namespace where nspname = 'analysis'")
				.query(String.class).single())
				.isEqualTo(ROLE);

		analysis.sql("create table analysis.owned_probe (id int)").update();
		try {
			analysis.sql("insert into analysis.owned_probe values (1)").update();
			assertThat(analysis.sql("select count(*) from analysis.owned_probe").query(Long.class).single())
					.isOne();
		} finally {
			analysis.sql("drop table analysis.owned_probe").update();
		}
	}

	@Test
	@DisplayName("a query table added by a later migration is readable without a new grant")
	void defaultPrivilegesCoverLaterQueryTables() {
		owner.sql("create table query.later_projection_for_analysis (id int)").update();
		try {
			assertThat(analysis.sql("select count(*) from query.later_projection_for_analysis")
					.query(Long.class).single())
					.as("default privileges must carry the grant forward, or every projection a "
							+ "later migration adds silently breaks overround-analysis")
					.isZero();
		} finally {
			owner.sql("drop table query.later_projection_for_analysis").update();
		}
	}

	/**
	 * The limit, observed refusing a login rather than read from {@code pg_roles}.
	 * {@code 53300} is {@code too_many_connections}, which is also what a server
	 * out of {@code max_connections} says — so the witness is that the owner can
	 * still connect while the role is being refused.
	 */
	@Test
	@DisplayName("connections are bounded, and the bound leaves the server usable")
	void connectionLimitRefusesTheNextLogin() throws SQLException {
		int limit = owner.sql("select rolconnlimit from pg_roles where rolname = ?")
				.param(ROLE).query(Integer.class).single();
		assertThat(limit).as("-1 is unlimited").isPositive();

		List<Connection> held = new ArrayList<>();
		try {
			for (int i = 0; i < limit; i++) {
				held.add(login());
			}
			assertThatThrownBy(this::login)
					.isInstanceOf(SQLException.class)
					.extracting(e -> ((SQLException) e).getSQLState())
					.isEqualTo("53300");
			assertThat(owner.sql("select 1").query(Integer.class).single())
					.as("the refusal is the role's limit, not the server's")
					.isOne();
		} finally {
			for (Connection c : held) {
				c.close();
			}
		}
	}

	private Connection login() throws SQLException {
		return DriverManager.getConnection(TestcontainersConfiguration.POSTGRES.getJdbcUrl(), ROLE,
				TestcontainersConfiguration.ANALYSIS_PASSWORD);
	}

	/** On the SQLSTATE; see {@link SystemOfRecordBoundaryTest} for why not the wrapper type. */
	private static void assertRefused(String why, ThrowingCallable statement) {
		assertThatThrownBy(statement)
				.as(why)
				.isInstanceOf(DataAccessException.class)
				.satisfies(thrown -> {
					SQLException cause = findSqlException(thrown);
					assertThat((Throwable) cause).as("a refusal must carry a SQLException").isNotNull();
					assertThat(cause.getSQLState()).as("42501 is insufficient_privilege").isEqualTo("42501");
				});
	}

	private static @Nullable SQLException findSqlException(Throwable thrown) {
		for (Throwable t = thrown; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql;
			}
		}
		return null;
	}
}
