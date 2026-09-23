package com.stucray.raptor.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The committed capture sample, read independently of the code under test.
 *
 * <p>Every expectation in this package comes from here — from the bytes of the
 * sample files — rather than from the framer's own output. A fixture and the
 * code that reads it must not come from the same head; that is how PRD #95
 * shipped three broken mappers with green tests.
 *
 * <p><b>The sample is synthetic</b> (#303): Betfair's captures may not be
 * committed, so {@code tools/samples/make_synthetic_capture_sample.py} generates
 * it. Its values are invented, which is safe because every test here compares
 * two readings of the same bytes. Its shape is not: {@link SyntheticCaptureShapeTest}
 * holds it inside the key paths, types and values the real corpus has, and the
 * real corpus itself runs through the write path in {@link FullCorpusReplayIT}.
 */
final class CaptureSampleFiles {

	static final Path ROOT = Path.of("src/test/resources/capture-sample");

	private CaptureSampleFiles() {}

	static List<Path> all() throws IOException {
		try (var stream = Files.list(ROOT)) {
			return stream.filter(p -> p.getFileName().toString().endsWith(".ndjson.gz"))
					.sorted(Comparator.comparing(p -> p.getFileName().toString()))
					.toList();
		}
	}

	static Path file(String marketId) {
		return ROOT.resolve(marketId + ".ndjson.gz");
	}

	/** Every line of a capture, decompressed. */
	static List<String> lines(Path file) throws IOException {
		List<String> lines = new ArrayList<>();
		try (InputStream in = Files.newInputStream(file);
				GZIPInputStream gz = new GZIPInputStream(in)) {
			String text = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
			for (String line : text.split("\n", -1)) {
				if (!line.isBlank()) {
					lines.add(line);
				}
			}
		}
		return lines;
	}

	/** The message lines: everything that is not the `_meta` header. */
	static List<String> messageLines(Path file) throws IOException {
		return lines(file).stream().filter(line -> !line.contains("\"_meta\"")).toList();
	}

	/** The market id a capture is named for. */
	static String marketId(Path file) {
		String name = file.getFileName().toString();
		return name.substring(0, name.indexOf(".ndjson.gz"));
	}

	static long totalMessageLines() throws IOException {
		long total = 0;
		for (Path file : all()) {
			total += messageLines(file).size();
		}
		return total;
	}
}
