package com.stucray.raptor.archive;

/**
 * What a sweep did about one file — the vocabulary of its report.
 *
 * <p>Five outcomes rather than "ok" and "error", because the two that look like
 * failures are not. {@link #NOT_PUBLISHED} is the normal state of most divisions
 * for most of an August, and {@link #FAILED} is bounded to its own file: a sweep
 * of 748 pairs reports what broke and finishes.
 */
enum ArchiveVerdict {

	/**
	 * A file already in custody, taken into the system of record as it stands.
	 *
	 * <p>Only ever happens for a path {@code raw.football_file} does not hold —
	 * the 711 CSVs that were on disk before S9, and any left by a fetch whose
	 * database write did not land. Adoption is what makes the version an earlier
	 * analysis actually ran against survive the sweep that follows it: upstream
	 * may have corrected the file since, and downloading first would store the
	 * correction and overwrite the original in the same pass.
	 */
	ADOPTED,
	/** A file the archive did not have before. */
	NEW,
	/** Upstream republished a file we already held: a corrected result, usually. */
	UPDATED,
	/** Revalidated and identical — the overwhelmingly common outcome, and free. */
	UNCHANGED,
	/** {@code 300}: that season of that division does not exist yet. */
	NOT_PUBLISHED,
	/** This file, and only this file, could not be fetched. */
	FAILED
}
