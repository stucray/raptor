package com.stucray.raptor.archive;

/**
 * One division in one season — the unit of everything here.
 *
 * @param division the archive's division code, {@code E0}, {@code SC1}, …
 * @param season the two-season code, {@code 9394} through {@code 2627}
 */
record ArchiveTarget(String division, String season) {

	/**
	 * The custody-relative path, which is also {@code raw.football_file.path} and
	 * the data repo's {@code manifest.csv} path. One spelling, three readers.
	 */
	String path() {
		return "raw/%s/%s-%s.csv".formatted(division, division, season);
	}

	@Override
	public String toString() {
		return division + "-" + season;
	}
}
