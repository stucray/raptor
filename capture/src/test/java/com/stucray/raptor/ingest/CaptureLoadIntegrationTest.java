package com.stucray.raptor.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The capture-corpus load, against a real PostgreSQL.
 *
 * <p>Points the scanner at the committed sample rather than the custody root, so
 * the test stays hermetic and runs in CI, where no corpus exists. The sample is
 * synthetic and held to the real wire's shape by {@code SyntheticCaptureShapeTest}
 * (#303); the first two files receive before 15:00Z and the last two after, which
 * is what the session assignment below depends on.
 *
 * <p>Every expectation is derived by reading the files directly. The extractor
 * under test is never the oracle for its own output.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, CaptureLoadIntegrationTest.SampleCaptures.class})
class CaptureLoadIntegrationTest {

	/** The window each sample falls in — the first two before 15:00Z, the last two after. */
	private static final Instant FIRST_START = Instant.parse("2026-08-29T11:00:00Z");
	private static final Instant SECOND_START = Instant.parse("2026-08-29T15:00:00Z");

	@Autowired JobOperator jobOperator;
	@Autowired Job loadCaptureCorpusJob;
	// The owner's client: these assertions read `raw`, which the read identity is
	// refused on outright. That refusal is the boundary, not a test problem.
	@Autowired @Acquisition JdbcClient jdbc;
	@Autowired CaptureCorpusScanner scanner;
	@Autowired CaptureMessageExtractor extractor;

	private long firstSession;
	private long secondSession;

	@TestConfiguration(proxyBeanMethods = false)
	static class SampleCaptures {
		@Bean
		DynamicPropertyRegistrar captureRoot() {
			return registry -> registry.add("raptor.corpus.capture-root",
					() -> Path.of("src/test/resources/capture-sample").toAbsolutePath().toString());
		}
	}

	/**
	 * V12 seeds the imported sessions from paddock's capture-run ledger, which a
	 * test database does not have — correctly, since a fresh database has no
	 * Python era to carry across. The sessions the sample's messages belong to are
	 * therefore stood up here, in the same shape the seed produces.
	 */
	@BeforeEach
	void seedSessions() {
		firstSession = openSession("run-a", FIRST_START, SECOND_START, "SCHEDULED");
		secondSession = openSession("run-b", SECOND_START, null, "SCHEDULED");
	}

	@Test
	void loadsEveryCaptureAndRecordsItsProvenance() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(count("select count(*) from raw.capture_file")).isEqualTo(4);
		assertThat(count("select count(*) from raw.capture_file where status = 'LOADED'"))
				.isEqualTo(4);

		// Session provenance, never file provenance: a capture is ours and
		// unreplayable, and the check constraint permits exactly one of the two.
		assertThat(count("""
				select count(*) from raw.stream_message
				where session_id is null or file_id is not null"""))
				.as("every imported capture message belongs to a session")
				.isZero();

		// The count on the provenance row must be the rows that actually landed.
		// One file is one market, so the market is what ties them together.
		for (Path file : scanner.files()) {
			CapturedFile described = scanner.describe(file);
			long stored = count("""
					select count(*) from raw.stream_message
					where market_id = '%s' and session_id is not null"""
					.formatted(described.marketId()));
			long recorded = count(
					"select messages from raw.capture_file where path = '%s'"
							.formatted(described.relativePath()));
			assertThat(stored).as("messages stored for %s", described.relativePath())
					.isEqualTo(recorded)
					.isEqualTo(extractor.extract(file, ImportedSessions.load(jdbc)).messages().size());
		}
	}

	/**
	 * The instants stored must be the instants on the wire.
	 *
	 * <p>COPY parses a bare timestamp using the session's TimeZone, which pgjdbc
	 * takes from the JVM default — so a value rendered in UTC without an offset
	 * lands shifted by the developer's own offset, with nothing in a row count or
	 * a digest to show it. The bug is invisible in CI, which runs UTC, so it has
	 * to be asserted where it would be made.
	 */
	@Test
	void storesTheWiresOwnInstants() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		for (Path file : scanner.files()) {
			CapturedFile described = scanner.describe(file);
			List<Instant> expected = extractor.extract(file, ImportedSessions.load(jdbc))
					.messages().stream().map(m -> m.pt()).distinct().sorted().toList();
			List<Instant> stored = jdbc.sql("""
							select distinct pt from raw.stream_message
							where market_id = ? and session_id is not null order by pt""")
					.param(described.marketId())
					.query((rs, rowNum) -> rs.getObject("pt", OffsetDateTime.class).toInstant())
					.list();

			assertThat(stored).as("publish times stored for %s", described.relativePath())
					.isEqualTo(expected);
		}
	}

	/**
	 * A message belongs to the session that was <em>receiving</em> when it
	 * arrived, which is what makes the imported era's provenance mean the same
	 * thing as the resident era's.
	 */
	@Test
	void assignsEachMessageToTheSessionThatReceivedIt() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		Map<String, Long> sessionByMarket = jdbc.sql("""
						select market_id, min(session_id) as session_id
						from raw.stream_message where session_id is not null
						group by market_id""")
				.query((rs, rowNum) -> Map.entry(rs.getString("market_id"), rs.getLong("session_id")))
				.list().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(sessionByMarket).containsExactlyInAnyOrderEntriesOf(Map.of(
				"1.900000001", firstSession,
				"1.900000002", firstSession,
				"1.900000003", secondSession,
				"1.900000004", secondSession));

		// And no market straddles the boundary, since none of these did.
		assertThat(count("""
				select count(*) from (
					select market_id from raw.stream_message where session_id is not null
					group by market_id having count(distinct session_id) > 1) straddling"""))
				.isZero();
	}

	/** The `_meta` header is kept — it is the only record of the resolved names. */
	@Test
	void keepsTheHeaderThatIsNotAMessage() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(count("select count(*) from raw.capture_file where meta_json is not null"))
				.as("all four samples carry a _meta line")
				.isEqualTo(4);
		String eventName = jdbc.sql("""
						select meta_json ->> 'event_name' from raw.capture_file
						where path = '1.900000002.ndjson.gz'""")
				.query(String.class).single();
		assertThat(eventName).isEqualTo("Home FC v Away FC");

		// The header must not have become a message. It has no pt, so a framer that
		// treated it as one would either fail or invent a timestamp.
		assertThat(count("""
				select count(*) from raw.stream_message
				where jsonb_exists(payload, '_meta')"""))
				.isZero();
	}

	/**
	 * A market the resident recorder captured for itself is not loaded again.
	 *
	 * <p>Both observations in one system of record would be spliced by the
	 * projection, which reads a market's messages by market id alone — and the
	 * shadow diff established that two connections receive differently packaged
	 * envelopes of the same changes. The file is still recorded, as superseded,
	 * so the decision is legible rather than a silent omission.
	 */
	@Test
	void doesNotLoadAMarketTheResidentRecorderAlreadyHolds() throws Exception {
		long resident = openSession(null, FIRST_START, null, "RESIDENT");
		jdbc.sql("""
						insert into raw.stream_message
							(session_id, market_id, pt, received_at, seq, payload)
						values (?, '1.900000002', ?::timestamptz, ?::timestamptz, 0, '{"id":"1.900000002"}')""")
				.params(resident, "2026-08-29T11:30:44Z", "2026-08-29T11:30:44Z")
				.update();

		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(jdbc.sql("select status from raw.capture_file where path = '1.900000002.ndjson.gz'")
				.query(String.class).single())
				.isEqualTo("SUPERSEDED");
		assertThat(count("""
				select count(*) from raw.stream_message where market_id = '1.900000002'"""))
				.as("only the resident recorder's own message")
				.isEqualTo(1);
		// The header is kept even so: paddock's own capture has none.
		assertThat(jdbc.sql("""
						select meta_json ->> 'event_name' from raw.capture_file
						where path = '1.900000002.ndjson.gz'""")
				.query(String.class).single())
				.isEqualTo("Home FC v Away FC");
	}

	/** A second run must be a no-op, not a duplicate. */
	@Test
	void isIdempotent() throws Exception {
		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);
		long afterFirst = count("select count(*) from raw.stream_message");

		assertThat(run().getStatus()).isEqualTo(BatchStatus.COMPLETED);

		assertThat(count("select count(*) from raw.stream_message"))
				.as("re-running a loaded corpus must not duplicate messages")
				.isEqualTo(afterFirst);
		assertThat(count("select count(*) from raw.capture_file")).isEqualTo(4);
	}

	private long openSession(@Nullable String sourceKey, Instant started,
			@Nullable Instant ended, String origin) {
		Long id = jdbc.sql("""
						insert into raw.capture_session
							(source_key, started_at, ended_at, origin, config_json, build_version)
						values (?, ?, ?, ?, '{}'::jsonb, 'test') returning id""")
				.params(java.util.Arrays.asList(sourceKey,
						started.atOffset(java.time.ZoneOffset.UTC),
						ended == null ? null : ended.atOffset(java.time.ZoneOffset.UTC), origin))
				.query(Long.class).single();
		return id;
	}

	private long count(String sql) {
		Long value = jdbc.sql(sql).query(Long.class).single();
		return value == null ? 0 : value;
	}

	private JobExecution run() throws Exception {
		return jobOperator.start(loadCaptureCorpusJob, new JobParametersBuilder()
				.addLong(CaptureLoadJobConfig.RUN_PARAM, System.nanoTime())
				.toJobParameters());
	}
}
