package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayStreamSourceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T18:00:00Z"), ZoneOffset.UTC);

	@Test
	void deliversEveryLineOfEveryCapture() throws Exception {
		long expected = 0;
		for (Path file : CaptureSampleFiles.all()) {
			expected += CaptureSampleFiles.lines(file).size();
		}

		assertThat(drain(ReplayStreamSource.of(CaptureSampleFiles.ROOT, CLOCK))).hasSize((int) expected);
	}

	@Test
	void replaysFilesInNameOrder() throws Exception {
		ReplayStreamSource source = ReplayStreamSource.of(CaptureSampleFiles.ROOT, CLOCK);

		assertThat(source.files()).isEqualTo(CaptureSampleFiles.all());
	}

	/**
	 * A capture torn off mid-write still yields what it holds.
	 *
	 * <p>The trap S4 found on the parsing side, on the replay side: a
	 * {@code BufferedReader} over a {@code GZIPInputStream} fills 8 KB before
	 * returning anything, so a gzip member with no end-of-stream marker throws with
	 * every decoded line still inside the buffer — a whole capture reading as zero
	 * messages, silently. No file in the corpus is truncated, so only a deliberate
	 * trap can catch this.
	 */
	@Test
	void keepsWhatItReadFromATruncatedCapture(@TempDir Path directory) throws Exception {
		Path original = CaptureSampleFiles.file("1.900000001");
		byte[] whole = Files.readAllBytes(original);
		Files.write(directory.resolve("1.900000001.ndjson.gz"),
				java.util.Arrays.copyOf(whole, whole.length / 2));

		List<StreamFrame> frames = drain(ReplayStreamSource.of(directory, CLOCK));

		assertThat(frames).as("a torn capture must cost its tail, not the whole file")
				.hasSizeGreaterThan(1000);
		assertThat(frames).hasSizeLessThan(CaptureSampleFiles.lines(original).size());
	}

	@Test
	void stampsFramesWithTheReadClock() throws Exception {
		List<StreamFrame> frames = drain(new ReplayStreamSource(
				List.of(CaptureSampleFiles.file("1.900000004")), CLOCK));

		assertThat(frames).allSatisfy(
				frame -> assertThat(frame.receivedAt()).isEqualTo(CLOCK.instant()));
	}

	private static List<StreamFrame> drain(ReplayStreamSource source) throws IOException {
		List<StreamFrame> frames = new ArrayList<>();
		try (ReplayStreamSource open = source) {
			StreamFrame frame;
			while ((frame = open.next()) != null) {
				frames.add(frame);
			}
		}
		return frames;
	}
}
