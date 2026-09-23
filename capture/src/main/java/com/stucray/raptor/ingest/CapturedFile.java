package com.stucray.raptor.ingest;

import java.nio.file.Path;

/**
 * A Python-era capture file and the evidence that it landed intact.
 *
 * @param relativePath path under the capture root, which is the natural key —
 *     one file per market, named for it
 * @param sha256 of the compressed bytes exactly as they sit on disk. This is
 *     the only possible proof that the load was faithful: {@code jsonb} does not
 *     preserve bytes, normalising away key order, whitespace and duplicate keys,
 *     so nothing inside the database can serve as that evidence.
 */
record CapturedFile(Path absolutePath, String relativePath, byte[] sha256, long bytes) {

	/** The market a capture is named for: {@code <marketId>.ndjson.gz}. */
	String marketId() {
		String name = absolutePath.getFileName().toString();
		return name.substring(0, name.length() - CaptureCorpusScanner.SUFFIX.length());
	}
}
