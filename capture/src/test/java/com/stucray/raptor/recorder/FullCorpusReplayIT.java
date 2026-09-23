package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The slice's real bar: every capture on disk, through the real write path.
 *
 * <p>Not part of {@code verify} — CI has no corpus, exactly as the parity gate
 * has none. Run it against the custody root:
 *
 * <pre>./mvnw -pl acquisition -Preplay verify</pre>
 *
 * <p>The acceptance is a count taken from the files themselves, per market:
 * 541 captures, 5,485,830 message lines and 528 {@code _meta} headers as
 * surveyed on 2026-09-01. Counting them here rather than asserting that constant
 * is deliberate — the corpus grows every night the recorder runs, and a bar that
 * has to be edited to stay green is a bar that gets edited.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIfSystemProperty(named = FullCorpusReplayIT.CORPUS, matches = ".+")
class FullCorpusReplayIT {

	static final String CORPUS = "raptor.replay.corpus";

	private static final Logger log = LoggerFactory.getLogger(FullCorpusReplayIT.class);

	@Autowired RecorderPipeline pipeline;
	@Autowired Clock clock;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	void replaysTheWholeCaptureCorpus() throws Exception {
		Path root = Path.of(System.getProperty(CORPUS));
		ReplayStreamSource source = ReplayStreamSource.of(root, clock);
		assertThat(source.files()).as("captures under %s", root).isNotEmpty();

		Map<String, Long> expected = messageLinesPerMarket(source.files());
		long expectedTotal = expected.values().stream().mapToLong(Long::longValue).sum();

		long startedAt = System.nanoTime();
		long sessionId;
		long written;
		try (Recording recording = pipeline.start(source, CaptureOrigin.MANUAL)) {
			recording.awaitSource();
			recording.close();
			sessionId = recording.sessionId();
			written = recording.written();
			assertThat(recording.framed()).isEqualTo(written);
		}
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		log.info("replayed {} files / {} messages in {}s ({} msg/s)", expected.size(), written,
				elapsed.toSeconds(), written * 1000 / Math.max(1, elapsed.toMillis()));

		assertThat(written).isEqualTo(expectedTotal);

		Map<String, Long> stored = new HashMap<>();
		jdbc.sql("select market_id, count(*) as rows from raw.stream_message "
						+ "where session_id = ? group by market_id")
				.param(sessionId)
				.query((rs, rowNum) -> stored.put(rs.getString("market_id"), rs.getLong("rows")))
				.list();

		assertThat(stored).isEqualTo(expected);

		Long holes = jdbc.sql("""
						select count(*) from (
							select seq, lag(seq) over (order by seq) as previous
							from raw.stream_message where session_id = ?
						) ordered where previous is not null and seq <> previous + 1""")
				.param(sessionId).query(Long.class).single();
		assertThat(holes).as("seq must be dense across the session").isZero();
	}

	/** Read straight off the files, with none of the code under test involved. */
	private static Map<String, Long> messageLinesPerMarket(Iterable<Path> files) throws Exception {
		Map<String, Long> counts = new HashMap<>();
		for (Path file : files) {
			long messages = 0;
			try (InputStream in = Files.newInputStream(file);
					GZIPInputStream gz = new GZIPInputStream(in)) {
				for (String line : new String(gz.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
					if (!line.isBlank() && !line.contains("\"_meta\"")) {
						messages++;
					}
				}
			}
			String name = file.getFileName().toString();
			counts.put(name.substring(0, name.indexOf(".ndjson.gz")), messages);
		}
		return counts;
	}
}
