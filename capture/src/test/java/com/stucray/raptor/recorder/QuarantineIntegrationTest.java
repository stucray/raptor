package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import com.stucray.raptor.rawstore.RawMessage;
import com.stucray.raptor.rawstore.RawWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The refusal, against a real PostgreSQL, with the server supplying the SQLSTATE.
 *
 * <p>{@code RawWriteLoopTest} drives the same branching against a writer that
 * throws a {@code SQLException} this file made up. That proves the logic and
 * nothing about the premise — so this one hands the real
 * {@code RawMessageWriter} a real COPY into the real {@code raw.stream_message}
 * and lets the server decide.
 *
 * <p><b>The refusal is staged, and no refusal like it has ever occurred.</b>
 * Worth saying plainly, because a fixture that reads as though it came from
 * captured data is how invented fixtures ship. Nothing in this project has ever
 * had a message refused, and the corpus was searched before this was written —
 * 221,780 files with no unicode escape of any form in them. What is being
 * defended against is a class of failure with realistic schema-shaped causes: a
 * migration that adds a constraint under a running recorder, a partition set
 * that ran out (#267), or the one staged here.
 *
 * <p><b>A foreign key, deliberately, and not the alternatives.</b> A
 * {@code session_id} with no {@code raw.capture_session} row is refused with
 * 23503 straight out of COPY. It needs no DDL, which matters: this module shares
 * one static PostgreSQL for the whole JVM and {@code DatabaseReset} truncates
 * rows without undoing schema changes, so a test that altered
 * {@code raw.stream_message} and died before dropping the column would poison
 * every test that ran after it — in filesystem order, which is not the same on
 * macOS as on the CI runner. A not-null violation would have needed exactly that
 * alter, because {@code RawMessage}'s own types already forbid the nulls.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("A row PostgreSQL refuses is quarantined, and its batch-mates are captured")
class QuarantineIntegrationTest {

	/** No capture session has this id, and the foreign key says so. */
	private static final long ABSENT_SESSION = 999_999_999L;

	@Autowired RawWriter writer;
	@Autowired Quarantine quarantine;
	@Autowired @Acquisition JdbcClient jdbc;
	@Autowired @Acquisition PlatformTransactionManager transactionManager;

	@Test
	void theRefusedMessageIsHeldAndTheRestAreCaptured() {
		long sessionId = jdbc.sql("""
				insert into raw.capture_session (started_at, origin, config_json, build_version)
				values (now(), 'RESIDENT', '{}'::jsonb, 'test') returning id""")
				.query(Long.class).single();

		List<RawMessage> batch = new ArrayList<>();
		for (int seq = 0; seq < 8; seq++) {
			batch.add(new RawMessage(seq == 5 ? ABSENT_SESSION : sessionId, null, "1.234",
					Instant.parse("2026-09-14T12:00:00Z").plusMillis(seq), null, seq,
					"{\"id\":\"1.234\"}"));
		}

		// The witness: without it the assertions below would pass just as well
		// against a batch the server was perfectly happy with, which is the
		// tautology this whole test exists to avoid.
		assertThat(copyRefuses(batch))
				.as("the premise — PostgreSQL must actually refuse this payload")
				.isTrue();

		RawWriteLoop loop = new RawWriteLoop(new java.util.concurrent.ArrayBlockingQueue<>(64),
				writer, new TransactionTemplate(transactionManager), new CollectingSpillSink(),
				quarantine, 1024, 1_000_000L);
		// One batch directly rather than through the queue: the queue is the part
		// RawWriteLoopTest covers, and this test is about what the server says.
		loop.write(batch);

		assertThat(jdbc.sql("select seq from raw.stream_message where session_id = ? order by seq")
				.param(sessionId).query(Long.class).list())
				.as("every message but the refused one reaches the system of record")
				.containsExactly(0L, 1L, 2L, 3L, 4L, 6L, 7L);

		List<String> states = jdbc.sql(
						"select sqlstate from raw.rejected_message where session_id = ?")
				.param(ABSENT_SESSION).query(String.class).list();
		assertThat(states).as("held, with the reason the SERVER gave").hasSize(1);
		assertThat(states.get(0))
				.as("23503 is foreign_key_violation, and the server is what said so")
				.isEqualTo("23503");

		assertThat(jdbc.sql("select seq, payload from raw.rejected_message where session_id = ?")
				.param(ABSENT_SESSION)
				.query((rs, n) -> rs.getLong("seq") + ":" + rs.getString("payload")).single())
				.as("held WHOLE, so the cause can be fixed and the row replayed")
				.isEqualTo("5:{\"id\":\"1.234\"}");
	}

	/** Does the server really refuse this batch? Asked, not assumed. */
	private boolean copyRefuses(List<RawMessage> batch) {
		try {
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				status.setRollbackOnly();
				try {
					writer.write(batch);
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
			return false;
		}
		catch (RuntimeException e) {
			return true;
		}
	}
}
