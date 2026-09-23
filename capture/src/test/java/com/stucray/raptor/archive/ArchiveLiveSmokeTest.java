package com.stucray.raptor.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * The three behaviours this whole design rests on, against the real archive.
 *
 * <p>Skipped unless {@code FOOTBALL_ARCHIVE_LIVE} is set, so CI and an ordinary
 * {@code mvn verify} never see it:
 *
 * <pre>
 * FOOTBALL_ARCHIVE_LIVE=1 ./mvnw -pl acquisition -Dtest=ArchiveLiveSmokeTest test
 * </pre>
 *
 * <p>Everything else here is asserted against a fake upstream, which can only
 * ever confirm that the code agrees with the test author's idea of the server.
 * These three are claims about football-data.co.uk itself — that it serves an
 * {@code ETag}, that it honours {@code If-None-Match}, and that a season it has
 * not published answers {@code 300} rather than {@code 404} — and only the
 * server can settle them. All three confirmed green on 2026-09-16; if one stops
 * being true, a sweep silently re-downloads ~90 MB or reports a healthy archive
 * as broken, and this is what says so.
 *
 * <p><b>Running it is the whole point, and nothing makes anyone.</b> It was this
 * test that reproduced #286 in one command after the nightly close-out had been
 * failing for twelve days — the archive 302ing `www.` to its apex, which no
 * mock-backed test can see because a mock answers exactly what its author
 * imagined. Run it whenever the archive's behaviour is in question, and after
 * any change to the fetch client.
 *
 * <p>Read-only: three GETs, one of which transfers a body. It writes nothing.
 */
@EnabledIfEnvironmentVariable(named = "FOOTBALL_ARCHIVE_LIVE", matches = ".+",
		disabledReason = "does not touch the network unless asked")
class ArchiveLiveSmokeTest {

	private final ArchiveProperties properties = new ArchiveProperties(
			"https://www.football-data.co.uk/mmz4281", Path.of("/tmp/unused"),
			List.of("E0"), 1993, "paddock-archive/1.0 (personal research; smoke test)",
			Duration.ofMillis(300), Duration.ofSeconds(30), 1, Duration.ofMillis(500),
			new ArchiveProperties.Sweep(false));

	private final ArchiveFetchClient client =
			new ArchiveFetchClient(RestClient.builder(), properties);

	@Test
	void theArchiveServesValidatorsHonoursThemAndSays300ForASeasonItDoesNotHave() {
		ArchiveTarget current = new ArchiveTarget("E0", ArchiveSeasons.currentSeason(Clock.systemUTC()));

		FetchResult first = client.fetch(current, null);

		assertThat(first).isInstanceOfSatisfying(FetchResult.Fetched.class, fetched -> {
			assertThat(fetched.etag()).as("an ETag is what makes the sweep exact").isNotBlank();
			// THE BOM IS EXPECTED, and this assertion used to deny it. The server
			// has served UTF-8-with-BOM for as long as there are files in custody
			// to check — the copy fetched on 2026-09-01 opens `ef bb bf` too — so
			// `startsWith("Div,")` on the raw bytes could never have passed, and
			// this test cannot have been green when #286's fix was reached for.
			// `FootballDataParser` strips U+FEFF deliberately; the contract this
			// smoke should hold the server to is "an optional BOM, then the
			// header", which is exactly what the parser is written against.
			assertThat(new String(fetched.body(), java.nio.charset.StandardCharsets.UTF_8))
					.as("the archive serves UTF-8 with a BOM, which the parser strips")
					.startsWith("﻿Div,");
		});

		// The same request, conditional. This is the difference between a few
		// hundred KB and ~90 MB per sweep.
		FetchResult.Fetched fetched = (FetchResult.Fetched) first;
		assertThat(client.fetch(current, new Validators(fetched.etag(), fetched.lastModified())))
				.isInstanceOf(FetchResult.Unchanged.class);

		// G1 (Greece) did not exist in 1993/94. Apache MultiViews answers 300.
		assertThat(client.fetch(new ArchiveTarget("G1", "9394"), null))
				.isInstanceOf(FetchResult.NotPublished.class);
	}
}
