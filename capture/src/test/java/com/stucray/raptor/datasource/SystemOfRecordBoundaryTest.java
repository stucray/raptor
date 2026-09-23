package com.stucray.raptor.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.TestcontainersConfiguration;
import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The boundary, observed refusing something.
 *
 * <p>When acquisition and the read side were two processes this was free:
 * paddock's connection had no grant on {@code raw}, so the read side could not
 * reach the system of record however wrong the code was. One process means one
 * program that both writes {@code raw} and serves {@code query}, and the only
 * thing holding those apart now is which identity a component asked for.
 *
 * <p>So these assert the refusals. Reading a grant table would prove the
 * migration ran; it would not prove PostgreSQL stops anybody.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("The read identity is refused on raw, and cannot write query")
class SystemOfRecordBoundaryTest {

	/** What every read-side component gets by default. */
	@Autowired JdbcClient readSide;

	/** What ingest and projection ask for explicitly. */
	@Autowired @Acquisition JdbcClient acquisition;

	@Test
	@DisplayName("the read identity cannot read the system of record")
	void readIdentityIsRefusedOnRaw() {
		assertRefused("if this ever passes, the read side can read raw and the boundary is gone",
				() -> readSide.sql("select count(*) from raw.stream_message")
						.query(Long.class)
						.single());
	}

	@Test
	@DisplayName("the read identity cannot reach the run ledger either")
	void readIdentityIsRefusedOnBatch() {
		assertRefused("the run ledger is the write path's own bookkeeping",
				() -> readSide.sql("select count(*) from batch.batch_job_execution")
						.query(Long.class)
						.single());
	}

	@Test
	@DisplayName("the read identity can read the projection")
	void readIdentityCanSelectFromQuery() {
		assertThat(readSide.sql("select count(*) from query.projection").query(Long.class).single())
				.as("query is the contract; being unable to read it would be the opposite bug")
				.isNotNull();
	}

	@Test
	@DisplayName("the read identity cannot write the projection")
	void readIdentityCannotWriteQuery() {
		// A projection is derived from raw and rebuilt from it. The read side
		// writing one would be a fact with no source.
		assertRefused("a projection the read side wrote would be a fact with no source",
				() -> readSide.sql(
								"insert into query.projection (job_name, partition_key, job_execution_id) "
										+ "values ('forged', '1999-01', 1)")
						.update());
	}

	@Test
	@DisplayName("a projection table added later is readable without revisiting the grant")
	void defaultPrivilegesCoverNewProjectionTables() {
		acquisition.sql("create table query.later_projection (id int)").update();
		try {
			assertThat(readSide.sql("select count(*) from query.later_projection")
					.query(Long.class)
					.single())
					.as("default privileges must carry the grant forward, or every projection "
							+ "a later migration adds silently starts unreadable")
					.isZero();
		} finally {
			acquisition.sql("drop table query.later_projection").update();
		}
	}

	/**
	 * Asserts PostgreSQL refused the statement for lack of privilege.
	 *
	 * <p>On the SQLSTATE, not on the exception type. {@code 42501} is
	 * {@code insufficient_privilege}, but Spring's
	 * {@code SQLStateSQLExceptionTranslator} maps the whole {@code 42} class —
	 * "syntax error or access rule violation" — to {@code BadSqlGrammarException},
	 * which a mistyped table name also produces. Asserting the wrapper type would
	 * let a typo stand in for a refusal and quietly pass forever.
	 */
	private static void assertRefused(String why, ThrowingCallable statement) {
		assertThatThrownBy(statement)
				.as(why)
				.isInstanceOf(DataAccessException.class)
				.satisfies(thrown -> {
					SQLException cause = findSqlException(thrown);
					// Cast: SQLException implements Iterable<Throwable>, so a bare
					// assertThat(cause) is ambiguous against AssertJ's overloads.
					assertThat((Throwable) cause).as("a refusal must carry a SQLException").isNotNull();
					assertThat(cause.getSQLState())
							.as("42501 is insufficient_privilege; anything else means the "
									+ "statement failed for some other reason and proves nothing")
							.isEqualTo("42501");
				});
	}

	private static SQLException findSqlException(Throwable thrown) {
		for (Throwable t = thrown; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql) {
				return sql;
			}
		}
		return null;
	}

	@Test
	@DisplayName("the acquisition identity can do what the read identity cannot")
	void acquisitionIdentityReachesRaw() {
		assertThat(acquisition.sql("select count(*) from raw.stream_message")
				.query(Long.class)
				.single())
				.as("the boundary must separate the identities, not disable the write path")
				.isNotNull();
	}
}
