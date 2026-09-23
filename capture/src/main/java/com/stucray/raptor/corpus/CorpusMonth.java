package com.stucray.raptor.corpus;

/**
 * The month key of the Betfair BASIC corpus, and its two spellings.
 *
 * <p>Betfair lays the corpus out as {@code <YYYY>/<Mon>/<D>/<eventId>/<marketId>.bz2}
 * with three-letter English month abbreviations; the jobs partition on
 * {@code YYYY-MM}. Both the load (which walks the filesystem) and the projection
 * (which must never touch it, and matches on the stored path instead) need the
 * conversion, so it lives here rather than being written twice — a second copy
 * of a lookup table is exactly how the two sides come to disagree about which
 * files a month contains.
 */
public final class CorpusMonth {

	/**
	 * The name of the job parameter that carries a month key.
	 *
	 * <p>Lives here rather than on either job's configuration because both jobs
	 * and both ops endpoints need it, and the configurations are package-private
	 * (paddock requires that of every {@code @Configuration}). The month key is
	 * what the parameter <em>is</em>, so this is its home rather than a shared
	 * constants class invented to hold it.
	 */
	public static final String PARAM = "month";

	/** Betfair's corpus directories use three-letter English month abbreviations. */
	private static final String MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec";

	private CorpusMonth() {}

	/**
	 * @param year corpus year directory, e.g. {@code 2020}
	 * @param monthName corpus month directory, e.g. {@code Mar}
	 * @return the month key, e.g. {@code 2020-03}
	 */
	public static String key(String year, String monthName) {
		// Length check matters: MONTHS.indexOf("Ma") finds the "Ma" of "Mar" at a
		// multiple of three and would silently pass as March.
		int index = monthName.length() == 3 ? MONTHS.indexOf(monthName) : -1;
		if (index < 0 || index % 3 != 0) {
			throw new IllegalArgumentException("unrecognised month directory '" + monthName + "'");
		}
		return "%s-%02d".formatted(year, index / 3 + 1);
	}

	/**
	 * The inverse: the relative-path prefix of every file in a month, e.g.
	 * {@code 2020-03} to {@code 2020/Mar/}. Used to select a month's files from
	 * {@code raw.historic_file.path} without going anywhere near a filesystem.
	 */
	public static String pathPrefix(String monthKey) {
		if (monthKey.length() != 7 || monthKey.charAt(4) != '-') {
			throw new IllegalArgumentException("month key must be YYYY-MM, got '" + monthKey + "'");
		}
		int month;
		try {
			month = Integer.parseInt(monthKey.substring(5));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("month key must be YYYY-MM, got '" + monthKey + "'", e);
		}
		if (month < 1 || month > 12) {
			throw new IllegalArgumentException("month out of range in '" + monthKey + "'");
		}
		return monthKey.substring(0, 4) + "/" + MONTHS.substring((month - 1) * 3, month * 3) + "/";
	}
}
