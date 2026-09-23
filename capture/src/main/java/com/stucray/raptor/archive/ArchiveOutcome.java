package com.stucray.raptor.archive;

import org.jspecify.annotations.Nullable;

/**
 * One target, after its request: the verdict and whatever the writer needs.
 *
 * <p>Every target produces one of these, including the ones that transferred
 * nothing. Filtering the uninteresting ones out in the processor would make the
 * step's write count meaningless and lose the {@code checked_at} update that is
 * the only evidence a sweep looked at a file at all.
 *
 * @param content the bytes as served, present only for {@link ArchiveVerdict#NEW}
 *     and {@link ArchiveVerdict#UPDATED}
 * @param sha256 their digest, present with the content
 * @param detail why, for a failure
 */
record ArchiveOutcome(
		ArchiveTarget target,
		ArchiveVerdict verdict,
		byte @Nullable [] content,
		byte @Nullable [] sha256,
		Validators validators,
		@Nullable String detail) {

	static ArchiveOutcome stored(ArchiveTarget target, ArchiveVerdict verdict, byte[] content,
			byte[] sha256, Validators validators) {
		return new ArchiveOutcome(target, verdict, content, sha256, validators, null);
	}

	static ArchiveOutcome adopted(ArchiveTarget target, byte[] content, byte[] sha256) {
		return new ArchiveOutcome(target, ArchiveVerdict.ADOPTED, content, sha256,
				new Validators(null, null), null);
	}

	static ArchiveOutcome unchanged(ArchiveTarget target, Validators validators) {
		return new ArchiveOutcome(target, ArchiveVerdict.UNCHANGED, null, null, validators, null);
	}

	static ArchiveOutcome notPublished(ArchiveTarget target) {
		return new ArchiveOutcome(target, ArchiveVerdict.NOT_PUBLISHED, null, null,
				new Validators(null, null), null);
	}

	static ArchiveOutcome failed(ArchiveTarget target, String detail) {
		return new ArchiveOutcome(target, ArchiveVerdict.FAILED, null, null,
				new Validators(null, null), detail);
	}
}
