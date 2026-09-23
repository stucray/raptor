package com.stucray.raptor.archive;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * One target's request, and what the answer means against what is already held.
 *
 * <p>Reads {@code raw.football_file} and writes nothing: the verdict is decided
 * here, and acting on it belongs to {@link ArchiveFetchWriter} inside the
 * chunk's transaction.
 *
 * <p><b>Bytes decide, not the status.</b> A {@code 200} is not by itself a new
 * version — an unconditional GET (no validators held) usually returns exactly
 * what is already stored, and calling that an update would fill the table with
 * duplicate versions of an unchanged archive. The digest settles it.
 */
class ArchiveFetchProcessor implements ItemProcessor<ArchiveTarget, ArchiveOutcome> {

	private static final Logger log = LoggerFactory.getLogger(ArchiveFetchProcessor.class);

	private final ArchiveFetchClient client;
	private final FootballFiles files;
	private final Duration delay;

	ArchiveFetchProcessor(ArchiveFetchClient client, FootballFiles files, Duration delay) {
		this.client = client;
		this.files = files;
		this.delay = delay;
	}

	@Override
	public ArchiveOutcome process(ArchiveTarget target) {
		Validators prior = files.validatorsFor(target.path()).orElse(null);
		FetchResult result = client.fetch(target, prior);
		pause();
		return switch (result) {
			case FetchResult.Unchanged ignored ->
					ArchiveOutcome.unchanged(target, prior == null ? new Validators(null, null) : prior);
			case FetchResult.NotPublished ignored -> ArchiveOutcome.notPublished(target);
			case FetchResult.Failed failed -> {
				log.warn("{} could not be fetched: {}", target, failed.detail());
				yield ArchiveOutcome.failed(target, failed.detail());
			}
			case FetchResult.Fetched fetched -> classify(target, fetched);
		};
	}

	private ArchiveOutcome classify(ArchiveTarget target, FetchResult.Fetched fetched) {
		byte[] sha256 = ArchiveCustody.sha256(fetched.body());
		Validators validators = new Validators(fetched.etag(), fetched.lastModified());
		return switch (files.classify(target.path(), sha256)) {
			case ABSENT -> ArchiveOutcome.stored(target, ArchiveVerdict.NEW, fetched.body(), sha256, validators);
			case DIFFERENT_BYTES ->
					ArchiveOutcome.stored(target, ArchiveVerdict.UPDATED, fetched.body(), sha256, validators);
			case SAME_BYTES -> ArchiveOutcome.unchanged(target, validators);
		};
	}

	/** Be a well-behaved client of a free service. */
	private void pause() {
		if (delay.isZero() || delay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted between requests", e);
		}
	}
}
