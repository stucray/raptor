package com.stucray.raptor.archive;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What to fetch, from where, and how politely.
 *
 * @param baseUrl the archive root. Each file is {@code <baseUrl>/<season>/<div>.csv}.
 *     <b>The apex, not {@code www.}</b> (#286): the site 302s {@code www.} to
 *     it, and while {@code ArchiveFetchClient} will now follow that, doing so
 *     costs a second request per file. A full sweep is 748 files against a
 *     published {@code x-ws-ratelimit-limit} of 1000, so routing every one
 *     through a redirect would put a full sweep at 1496 — over the limit. The
 *     redirect-following is the safety net for the site moving again, not the
 *     route.
 * @param custodyRoot the source's custody directory. Files land under
 *     {@code raw/<div>/} within it, which is where the data repo's scripts and
 *     the symlinks under {@code football/data/} already resolve them.
 * @param divisions the division codes to sweep. Configuration rather than a
 *     directory listing: deriving the list from what is already on disk means a
 *     division added upstream can never be discovered, and a fresh clone sweeps
 *     nothing at all.
 * @param firstSeasonYear the first season in the archive, 1993/94.
 * @param userAgent identify ourselves. This is a free service.
 * @param requestDelay between requests, for the same reason.
 * @param timeout per request.
 * @param retries transport-level retries per request, doubling from
 *     {@code backoff}. Deliberately small: this is for one dropped connection on
 *     an otherwise healthy host, not for riding out an outage. {@code fetch.py}
 *     learnt this the expensive way — one {@code ConnectionResetError} 37 minutes
 *     into an unattended sweep took the whole run with it.
 * @param backoff the base of that exponential backoff.
 * @param sweep the current-season check the nightly close-out runs.
 */
@ConfigurationProperties("raptor.football-archive")
public record ArchiveProperties(
		@DefaultValue("https://football-data.co.uk/mmz4281") String baseUrl,
		@DefaultValue("${RAPTOR_DATA_ROOT:${user.home}/raptor/data}/football-data.co.uk")
		Path custodyRoot,
		@DefaultValue({"B1", "D1", "D2", "E0", "E1", "E2", "E3", "EC", "F1", "F2", "G1",
				"I1", "I2", "N1", "P1", "SC0", "SC1", "SC2", "SC3", "SP1", "SP2", "T1"})
		List<String> divisions,
		@DefaultValue("1993") int firstSeasonYear,
		@DefaultValue("paddock-archive/1.0 (personal research; incremental refresh)")
		String userAgent,
		@DefaultValue("300ms") Duration requestDelay,
		@DefaultValue("30s") Duration timeout,
		@DefaultValue("3") int retries,
		@DefaultValue("500ms") Duration backoff,
		Sweep sweep) {

	/**
	 * The close-out's sweep, which is the <em>current season only</em> — 22
	 * conditional requests rather than 748.
	 *
	 * <p>Completed seasons are static apart from rare retro corrections, and the
	 * server publishes an {@code x-ws-ratelimit-limit} of 1000: a nightly full
	 * sweep would spend three quarters of that budget to catch a correction a
	 * deliberate sweep catches whenever anyone asks. Full sweeps stay deliberate.
	 *
	 * <p><b>It had a cron until #200</b>, when the fetch became a step of the
	 * nightly close-out rather than a schedule of its own. What is left is the
	 * switch, which is what keeps a test context from reaching the live archive.
	 *
	 * @param enabled off leaves the archive refreshing only when asked
	 */
	public record Sweep(@DefaultValue("true") boolean enabled) {}

	public ArchiveProperties {
		if (divisions.isEmpty()) {
			throw new IllegalArgumentException("at least one division must be configured");
		}
		if (retries < 0 || backoff.isNegative() || requestDelay.isNegative()) {
			throw new IllegalArgumentException("retries, backoff and delay must not be negative");
		}
	}

	/** Where a file belongs on disk. */
	Path fileIn(ArchiveTarget target) {
		return custodyRoot.resolve(target.path());
	}

	String urlFor(ArchiveTarget target) {
		return "%s/%s/%s.csv".formatted(baseUrl, target.season(), target.division());
	}
}
