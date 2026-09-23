package com.stucray.raptor.ingest;

import java.nio.file.Path;

/**
 * A vendor BASIC file and the evidence that it landed intact.
 *
 * @param relativePath path under the corpus root, which is the natural key
 * @param sha256 of the compressed bytes exactly as they sit on disk. This is
 *     the only possible proof that the load was faithful: {@code jsonb} does not
 *     preserve bytes, normalising away key order, whitespace and duplicate keys,
 *     so nothing inside the database can serve as that evidence.
 */
public record HistoricFile(Path absolutePath, String relativePath, byte[] sha256, long bytes) {}
