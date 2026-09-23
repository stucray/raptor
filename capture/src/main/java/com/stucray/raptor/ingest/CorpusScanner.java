package com.stucray.raptor.ingest;

import com.stucray.raptor.corpus.CorpusMonth;
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
 * Walks the Betfair BASIC corpus under the custody root and digests each file.
 *
 * <p>Read-only, always. The custody root holds canonical, unreplayable data that
 * acquisition consumes and never writes — that rule survived the charter amendment
 * unchanged, and it is the reason the corpus can serve as evidence against the
 * database rather than merely agreeing with it.
 */
@Component
class CorpusScanner {

	private final Path corpusRoot;

	public CorpusScanner(
			@Value("${raptor.corpus.historic-root:${RAPTOR_DATA_ROOT:${user.home}/raptor/data}/betfair-historic/raw/xds_nfs/edp_processed/BASIC}")
			Path corpusRoot) {
		this.corpusRoot = corpusRoot;
	}

	public Path root() {
		return corpusRoot;
	}

	/** Every {@code .bz2} under the root, in stable path order. */
	public List<Path> files() throws IOException {
		try (Stream<Path> walk = Files.walk(corpusRoot)) {
			return walk.filter(p -> p.getFileName().toString().endsWith(".bz2")).sorted().toList();
		}
	}

	/**
	 * Month partition key of a corpus file, as {@code YYYY-MM}, derived from its
	 * path: {@code .../BASIC/<YYYY>/<Mon>/<D>/<eventId>/<marketId>.bz2}.
	 *
	 * <p>From the path rather than from the messages inside, because this is what
	 * the Batch job partitions on and it must be knowable without opening the
	 * file. The two can disagree — a market whose pt crosses midnight on the last
	 * of the month sits in the previous month's directory — which is exactly why
	 * the partition key here is a UNIT OF WORK, not the table partition. Rows are
	 * routed to table partitions by their own pt.
	 */
	public String monthOf(Path file) {
		Path relative = corpusRoot.relativize(file);
		try {
			return CorpusMonth.key(relative.getName(0).toString(), relative.getName(1).toString());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(e.getMessage() + " in " + file, e);
		}
	}

	public HistoricFile describe(Path file) throws IOException {
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
		return new HistoricFile(file, corpusRoot.relativize(file).toString(), digest.digest(), bytes);
	}
}
