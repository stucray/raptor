package com.stucray.raptor.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/**
 * The committed historic sample, {@code src/test/resources/sample-corpus}.
 *
 * <p><b>The sample is synthetic</b> (#304). Betfair's BASIC files may not be
 * committed, so {@code tools/samples/make_synthetic_basic_sample.py} generates it
 * in the corpus's own layout ({@code <year>/<Mon>/<day>/<event id>/<market id>.bz2}).
 * Its values are invented, which is safe because the tests that use it compare
 * two readings of the same bytes: the file parsed directly, and the file loaded
 * into {@code raw} and projected. Its shape is not invented:
 * {@link SyntheticBasicShapeTest} holds it inside {@code basic-shape.json}, which
 * {@link BasicShapeManifestIT} reads off the real corpus.
 */
final class BasicSampleFiles {

	static final Path ROOT = Path.of("src/test/resources/sample-corpus");

	private BasicSampleFiles() {}

	static List<Path> all() throws IOException {
		return under(ROOT);
	}

	static List<Path> under(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.getFileName().toString().endsWith(".bz2")).sorted().toList();
		}
	}

	/** Every line of a BASIC file, decompressed. */
	static List<String> lines(Path file) {
		List<String> lines = new ArrayList<>();
		try (InputStream raw = Files.newInputStream(file);
				BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw, true)) {
			String text = new String(bz.readAllBytes(), StandardCharsets.UTF_8);
			for (String line : text.split("\n", -1)) {
				if (!line.isBlank()) {
					lines.add(line);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(file.toString(), ex);
		}
		return lines;
	}
}
