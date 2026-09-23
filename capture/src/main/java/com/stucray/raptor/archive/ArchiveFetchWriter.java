package com.stucray.raptor.archive;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * The system of record first, then custody.
 *
 * <p>That order is deliberate. The insert runs in the chunk's transaction and
 * the file write does not, so writing the file second means an {@code IOException}
 * rolls the row back and the sweep retries the whole file next time. The other
 * order would leave a row claiming custody holds bytes that never landed.
 *
 * <p>A verdict that transferred nothing still does something here: an
 * {@code UNCHANGED} moves {@code checked_at} and the validators forward, which
 * is the only record that anyone looked.
 */
class ArchiveFetchWriter implements ItemWriter<ArchiveOutcome> {

	private static final Logger log = LoggerFactory.getLogger(ArchiveFetchWriter.class);

	/** Prefix of the per-verdict counters in the job's execution context. */
	static final String TALLY_PREFIX = "archive.";

	private final FootballFiles files;
	private final ArchiveCustody custody;
	private final ExecutionContext tally;

	ArchiveFetchWriter(FootballFiles files, ArchiveCustody custody, ExecutionContext tally) {
		this.files = files;
		this.custody = custody;
		this.tally = tally;
	}

	@Override
	public void write(Chunk<? extends ArchiveOutcome> chunk) throws IOException {
		for (ArchiveOutcome outcome : chunk) {
			apply(outcome);
			tally(outcome.verdict());
		}
	}

	private void apply(ArchiveOutcome outcome) throws IOException {
		switch (outcome.verdict()) {
			// No custody write for an adoption: these bytes came from custody, and
			// writing them back would rewrite a file nothing asked to change.
			case ADOPTED -> store(outcome, false);
			case NEW, UPDATED -> store(outcome, true);
			case UNCHANGED -> files.confirm(outcome.target().path(), outcome.validators());
			case NOT_PUBLISHED, FAILED -> {
				// Nothing to record: a season that does not exist yet is not a
				// fact about our archive, and a failed request is not evidence
				// about the file it failed to fetch.
			}
		}
	}

	private void store(ArchiveOutcome outcome, boolean toCustody) throws IOException {
		byte[] content = outcome.content();
		byte[] sha256 = outcome.sha256();
		if (content == null || sha256 == null) {
			throw new IllegalStateException(
					outcome.target() + " reached the writer as " + outcome.verdict() + " with no bytes");
		}
		files.insert(outcome.target(), sha256, content, outcome.validators());
		if (toCustody) {
			custody.write(outcome.target(), content);
		}
		log.info("{} {} ({} bytes)", outcome.verdict(), outcome.target(), content.length);
	}

	/**
	 * Count the verdict into the job's execution context, so the ops response and
	 * the scheduled sweep's log line can say what actually happened rather than
	 * "748 read, 748 written" — which is the same sentence for a quiet Tuesday
	 * and for a sweep where every request failed.
	 *
	 * <p>Plain longs under one key each, rather than a map: the context is
	 * serialised into the job repository, and a counter that cannot be persisted
	 * would fail the step it is only supposed to describe.
	 */
	private void tally(ArchiveVerdict verdict) {
		String key = TALLY_PREFIX + verdict.name();
		tally.putLong(key, tally.getLong(key, 0) + 1);
	}
}
