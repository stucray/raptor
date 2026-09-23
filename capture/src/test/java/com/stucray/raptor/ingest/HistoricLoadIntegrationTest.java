package com.stucray.raptor.ingest;

import com.stucray.raptor.datasource.Acquisition;
import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.corpus.CorpusMonth;
import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.rawstore.RawMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * S2's acceptance, against a real PostgreSQL and a sample in the corpus's layout.
 *
 * <p>Points the scanner at the committed sample rather than the custody root, so
 * the test stays hermetic and runs in CI where no corpus exists. The sample is
 * synthetic (#304): Betfair's files may not be committed. Every assertion here
 * compares what was stored against what the file itself says, so invented values
 * are enough, and {@link SyntheticBasicShapeTest} holds the files to the real
 * corpus's shape.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, HistoricLoadIntegrationTest.SampleCorpus.class})
class HistoricLoadIntegrationTest {

	@Autowired JobOperator jobOperator;
	@Autowired Job loadHistoricCorpusJob;
	// The owner's client: these assertions read `raw`, which the read identity
	// is refused on outright. That refusal is the boundary, not a test problem.
	@Autowired @Acquisition JdbcClient jdbc;
	@Autowired CorpusScanner scanner;

	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	static class SampleCorpus {
		@org.springframework.context.annotation.Bean
		DynamicPropertyRegistrar corpusRoot() {
			return registry -> registry.add("raptor.corpus.historic-root",
					() -> Path.of("src/test/resources/sample-corpus").toAbsolutePath().toString());
		}
	}

	@Test
	void loadsAMonthAndRecordsProvenance() throws Exception {
		JobExecution execution = run("2020-03");
		assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

		Long files = jdbc.sql("select count(*) from raw.historic_file").query(Long.class).single();
		Long messages = jdbc.sql("select count(*) from raw.stream_message").query(Long.class).single();
		assertThat(files).isPositive();
		assertThat(messages).isPositive();

		// Every message carries file provenance and no session provenance — the
		// check constraint permits exactly one, and this is the vendor side.
		Long orphaned = jdbc.sql(
						"select count(*) from raw.stream_message where file_id is null or session_id is not null")
				.query(Long.class).single();
		assertThat(orphaned).isZero();

		// historic_file.messages must equal what actually landed.
		Long disagreeing = jdbc.sql("""
						select count(*) from raw.historic_file f
						where f.messages <> (
							select count(*) from raw.stream_message m where m.file_id = f.id)
						""").query(Long.class).single();
		assertThat(disagreeing)
				.as("historic_file.messages must match the rows actually written")
				.isZero();
	}

	/**
	 * The instants stored must be the instants on the wire.
	 *
	 * <p>S2 verified the load on sha256 and row counts, and a timestamp silently
	 * shifted by the JVM's timezone disturbs neither — COPY parses a bare
	 * timestamp using the session's TimeZone, so writing UTC without an offset
	 * moves every row by the developer's own offset. S3 found it by round-tripping
	 * a market back out; this asserts it at the layer that gets it wrong.
	 */
	@Test
	void storesTheWiresOwnInstants() throws Exception {
		assertThat(run("2020-03").getStatus()).isEqualTo(BatchStatus.COMPLETED);

		RawMessageExtractor extractor = new RawMessageExtractor();
		for (Path file : scanner.files()) {
			if (!scanner.monthOf(file).equals("2020-03")) {
				continue;
			}
			String path = scanner.root().relativize(file).toString();
			List<Instant> expected = extractor.extract(file, 1L).stream()
					.map(RawMessage::pt).distinct().sorted().toList();
			List<Instant> stored = jdbc.sql("""
							select distinct m.pt from raw.stream_message m
							join raw.historic_file f on f.id = m.file_id
							where f.path = ? order by m.pt""")
					.param(path)
					.query((rs, rowNum) -> rs.getObject("pt", OffsetDateTime.class).toInstant())
					.list();

			assertThat(stored).as("publish times stored for %s", path).isEqualTo(expected);
		}
	}

	/** A second run must be a no-op, not a duplicate. */
	@Test
	void isIdempotent() throws Exception {
		assertThat(run("2024-03").getStatus()).isEqualTo(BatchStatus.COMPLETED);
		Long afterFirst = jdbc.sql("select count(*) from raw.stream_message").query(Long.class).single();

		// A fresh JobInstance for the same month — the natural key on
		// historic_file.path, not Batch's own dedup, is what must hold the line.
		assertThat(run("2024-03").getStatus()).isEqualTo(BatchStatus.COMPLETED);
		Long afterSecond = jdbc.sql("select count(*) from raw.stream_message").query(Long.class).single();

		assertThat(afterSecond)
				.as("re-running a loaded month must not duplicate messages")
				.isEqualTo(afterFirst);
	}

	private JobExecution run(String month) throws Exception {
		JobParameters parameters = new JobParametersBuilder()
				.addString(CorpusMonth.PARAM, month)
				.addLong("attempt", System.nanoTime())
				.toJobParameters();
		return jobOperator.start(loadHistoricCorpusJob, parameters);
	}
}
