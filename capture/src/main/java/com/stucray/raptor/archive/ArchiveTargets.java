package com.stucray.raptor.archive;

import com.stucray.raptor.datasource.Acquisition;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which division×season pairs a sweep asks about.
 *
 * <p>The full grid is 22 divisions × 34 seasons = 748 pairs, and most of it is
 * empty: leagues joined the archive at different times, so probing every pair
 * every sweep would ask the server thirty times a year about files that have
 * never existed. So a pair is skipped when it is <b>absent from both custody and
 * the system of record</b> and is not the current season — the rule
 * {@code fetch.py} arrived at, with {@code raw.football_file} standing in for
 * its {@code fetch-state.csv}. The current season is never skipped: not existing
 * yet is exactly the state it is expected to leave.
 */
@Component
class ArchiveTargets {

	private final ArchiveProperties properties;
	private final ArchiveCustody custody;
	private final JdbcClient jdbc;

	ArchiveTargets(ArchiveProperties properties, ArchiveCustody custody,
			@Acquisition JdbcClient jdbc) {
		this.properties = properties;
		this.custody = custody;
		this.jdbc = jdbc;
	}

	/**
	 * @param division limit to one division, or null for all
	 * @param season limit to one season code, or null for all
	 * @param currentOnly only the season in progress — the scheduled sweep's shape
	 */
	List<ArchiveTarget> forSweep(@Nullable String division, @Nullable String season,
			boolean currentOnly, Clock clock) {
		List<String> divisions = properties.divisions().stream()
				.filter(d -> division == null || d.equals(division))
				.toList();
		List<String> seasons = (currentOnly
				? List.of(ArchiveSeasons.currentSeason(clock))
				: ArchiveSeasons.through(properties.firstSeasonYear(), clock)).stream()
				.filter(s -> season == null || s.equals(season))
				.toList();
		String current = ArchiveSeasons.currentSeason(clock);
		Set<String> known = Set.copyOf(jdbc.sql("select distinct path from raw.football_file")
				.query(String.class).list());

		return divisions.stream()
				.flatMap(d -> seasons.stream().map(s -> new ArchiveTarget(d, s)))
				.filter(t -> t.season().equals(current) || known.contains(t.path())
						|| Files.exists(properties.fileIn(t)))
				.toList();
	}

	/** Where the files land, for the log line that says what a sweep is about to do. */
	String custodyRoot() {
		return custody.root().toString();
	}
}
