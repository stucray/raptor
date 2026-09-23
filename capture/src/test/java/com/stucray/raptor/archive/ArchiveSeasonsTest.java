package com.stucray.raptor.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Season arithmetic, and the one date that decides a sweep's target.
 *
 * <p>The rule that is easy to get wrong: <b>a season rolls in July</b>. A sweep
 * run in August that asked for the season the calendar year started in would
 * revalidate a finished archive every night and never fetch a single result of
 * the season actually being played.
 */
class ArchiveSeasonsTest {

	private static Clock on(String date) {
		return Clock.fixed(Instant.parse(date), ZoneOffset.UTC);
	}

	@Test
	void spellsSeasonsTheWayTheArchivesUrlsDo() {
		assertThat(ArchiveSeasons.code(1993)).isEqualTo("9394");
		assertThat(ArchiveSeasons.code(2025)).isEqualTo("2526");
		// The century boundary, where a naive `year - 1900` breaks.
		assertThat(ArchiveSeasons.code(1999)).isEqualTo("9900");
		assertThat(ArchiveSeasons.code(2000)).isEqualTo("0001");
		assertThat(ArchiveSeasons.code(2009)).isEqualTo("0910");
	}

	@Test
	void augustBelongsToTheNewSeasonAndJuneToTheOld() {
		assertThat(ArchiveSeasons.currentSeason(on("2026-08-15T12:00:00Z"))).isEqualTo("2627");
		assertThat(ArchiveSeasons.currentSeason(on("2026-06-30T12:00:00Z"))).isEqualTo("2526");
		// The boundary itself: 1 July is the new season's first day.
		assertThat(ArchiveSeasons.currentSeason(on("2026-07-01T00:00:00Z"))).isEqualTo("2627");
	}

	@Test
	void sweepsEverySeasonFromTheArchivesFirstToTheCurrentOne() {
		var seasons = ArchiveSeasons.through(1993, on("2026-09-04T00:00:00Z"));

		assertThat(seasons).startsWith("9394", "9495").endsWith("2526", "2627");
		// 1993/94 through 2026/27 inclusive — the 34 seasons the archive holds.
		assertThat(seasons).hasSize(34).doesNotHaveDuplicates();
	}
}
