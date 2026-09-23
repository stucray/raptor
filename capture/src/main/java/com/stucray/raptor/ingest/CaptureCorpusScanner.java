package com.stucray.raptor.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Walks the Python recorder's capture files under the custody root and digests
 * each one.
 *
 * <p>Read-only, always — the same rule {@link CorpusScanner} keeps for the
 * vendor corpus, and for a stronger reason. A vendor file can be downloaded from
 * Betfair again; these were captured once from a stream that no longer exists.
 */
@Component
class CaptureCorpusScanner {

	static final String SUFFIX = ".ndjson.gz";

	private final Path captureRoot;

	CaptureCorpusScanner(
			@Value("${raptor.corpus.capture-root:${RAPTOR_DATA_ROOT:${user.home}/raptor/data}/betfair-live/captured}")
			Path captureRoot) {
		this.captureRoot = captureRoot;
	}

	Path root() {
		return captureRoot;
	}

	/** Every capture under the root, in stable path order. */
	List<Path> files() throws IOException {
		if (!Files.isDirectory(captureRoot)) {
			return List.of();
		}
		try (Stream<Path> walk = Files.walk(captureRoot)) {
			return walk.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
		}
	}

	CapturedFile describe(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		long bytes = 0;
		byte[] buffer = new byte[64 * 1024];
		try (InputStream in = Files.newInputStream(file)) {
			int read;
			while ((read = in.read(buffer)) != -1) {
				digest.update(buffer, 0, read);
				bytes += read;
			}
		}
		return new CapturedFile(file, captureRoot.relativize(file).toString(), digest.digest(), bytes);
	}
}
