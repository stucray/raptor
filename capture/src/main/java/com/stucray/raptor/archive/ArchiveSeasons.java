package com.stucray.raptor.archive;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Season codes, and which season "now" is in.
 *
 * <p>The one rule worth stating: <b>football seasons roll in July</b>. August is
 * the new season, not the old one, so a sweep run in August must ask for the
 * season that has barely started rather than the one that finished in May.
 */
final class ArchiveSeasons {

	private static final int SEASON_ROLLS_IN_MONTH = 7;

	private ArchiveSeasons() {}

	/** {@code 1993 -> "9394"}. Two-digit years, as the archive's URLs spell them. */
	static String code(int startYear) {
		return "%02d%02d".formatted(startYear % 100, (startYear + 1) % 100);
	}

	static int currentSeasonYear(Clock clock) {
		LocalDate today = LocalDate.now(clock);
		return today.getMonthValue() >= SEASON_ROLLS_IN_MONTH ? today.getYear() : today.getYear() - 1;
	}

	static String currentSeason(Clock clock) {
		return code(currentSeasonYear(clock));
	}

	/** Every season code from the archive's first to the current one, in order. */
	static List<String> through(int firstYear, Clock clock) {
		return IntStream.rangeClosed(firstYear, currentSeasonYear(clock))
				.mapToObj(ArchiveSeasons::code)
				.toList();
	}
}
