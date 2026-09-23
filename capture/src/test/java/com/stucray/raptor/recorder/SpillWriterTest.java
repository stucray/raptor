package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.rawstore.RawMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class SpillWriterTest {

	private static final Clock CLOCK =
			Clock.fixed(Instant.parse("2026-09-01T18:00:00Z"), ZoneOffset.UTC);

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	@Test
	void writesABatchAndLeavesNoPartialBehind(@TempDir Path directory) throws Exception {
		writer(directory).spill(messages(3), SpillCause.DB_UNAVAILABLE);

		List<Path> files = list(directory);
		assertThat(files).hasSize(1);
		assertThat(files.getFirst().getFileName().toString()).endsWith(".ndjson");
		assertThat(SpillFile.read(files.getFirst()).messages()).hasSize(3);
		assertThat(meters.get("raptor.recorder.messages.spilled")
				.tag("cause", "DB_UNAVAILABLE").counter().count()).isEqualTo(3);
	}

	/** Every batch is its own file: no rotation state to be wrong during an outage. */
	@Test
	void writesOneFilePerBatch(@TempDir Path directory) throws Exception {
		SpillSink writer = writer(directory);
		writer.spill(messages(2), SpillCause.DB_UNAVAILABLE);
		writer.spill(messages(2), SpillCause.QUEUE_FULL);

		assertThat(list(directory)).hasSize(2);
	}

	/**
	 * A failure to spill is loss, and it must be counted rather than thrown.
	 *
	 * <p>This is the fallback for the case where the normal path has already
	 * failed. An exception escaping here would propagate into the writer loop —
	 * or, for a queue overflow, into the thread draining the socket.
	 */
	@Test
	void neverThrowsWhenTheDirectoryCannotBeWritten(@TempDir Path directory) throws Exception {
		// A regular file where the directory should be: createDirectories fails.
		Path blocked = directory.resolve("blocked");
		Files.writeString(blocked, "not a directory");

		writer(blocked.resolve("spill")).spill(messages(4), SpillCause.DB_UNAVAILABLE);

		assertThat(meters.get("raptor.recorder.messages.lost")
				.tag("cause", "DB_UNAVAILABLE").counter().count()).isEqualTo(4);
	}

	private SpillSink writer(Path directory) {
		return new SpillWriter(new RecorderProperties(true, 50_000, 1024, Duration.ofMillis(200),
				Duration.ofSeconds(5), Duration.ofSeconds(60), 1L, 3, Duration.ofMinutes(10),
				new RecorderProperties.Spill(true, directory, DataSize.ofGigabytes(1),
						Duration.ofMinutes(1), 200, Duration.ofMinutes(15), 3),
				new RecorderProperties.Watchdog(Duration.ofSeconds(10), Duration.ofSeconds(30),
						Duration.ofSeconds(5))), CLOCK, meters);
	}

	private static List<Path> list(Path directory) throws Exception {
		try (var stream = Files.list(directory)) {
			return stream.toList();
		}
	}

	private static List<RawMessage> messages(int count) {
		return java.util.stream.IntStream.range(0, count)
				.mapToObj(i -> new RawMessage(5L, null, "1.234",
						Instant.ofEpochMilli(1787490204789L + i), Instant.ofEpochMilli(1787490204700L + i),
						i, "{\"id\":\"1.234\"}"))
				.toList();
	}
}
