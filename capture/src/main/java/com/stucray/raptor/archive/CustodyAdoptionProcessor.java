package com.stucray.raptor.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Bring a file already in custody into the system of record, if it is not there.
 *
 * <p>This runs <b>before</b> the sweep, and the order is the whole point. The
 * 711 CSVs on disk when S9 landed are the corpus every analysis in this project
 * has been run against, and the parity gate's oracle was emitted from them.
 * Upstream republishes a season's file whenever a result is corrected, so a
 * sweep that fetched first would store the correction, overwrite the file on
 * disk, and leave the version that produced those findings existing nowhere.
 * Adopting first costs one read per file and makes that impossible.
 *
 * <p>Idempotent, and quiet after the first run: a path whose bytes are already
 * held is filtered out, which for a settled archive is all of them.
 */
class CustodyAdoptionProcessor implements ItemProcessor<Path, ArchiveOutcome> {

	private final ArchiveCustody custody;
	private final FootballFiles files;

	CustodyAdoptionProcessor(ArchiveCustody custody, FootballFiles files) {
		this.custody = custody;
		this.files = files;
	}

	@Override
	public @Nullable ArchiveOutcome process(Path file) throws IOException {
		ArchiveTarget target = custody.targetOf(file);
		if (target == null) {
			return null;
		}
		byte[] content = Files.readAllBytes(file);
		byte[] sha256 = ArchiveCustody.sha256(content);
		return files.classify(target.path(), sha256) == FootballFiles.Held.SAME_BYTES
				? null
				: ArchiveOutcome.adopted(target, content, sha256);
	}
}
