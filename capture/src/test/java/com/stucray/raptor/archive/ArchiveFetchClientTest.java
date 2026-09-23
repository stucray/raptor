package com.stucray.raptor.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * What each of the archive's answers means.
 *
 * <p>Two of them are the reason this class exists. A <b>304</b> is the whole
 * economics of the sweep — 748 files for a few hundred KB instead of ~90 MB —
 * and a <b>300</b> is how this server (Apache MultiViews) says a season does not
 * exist yet, which in an August is the normal state of most of the archive. A
 * client that treated either as an error would either re-download everything or
 * report a healthy part-published season as broken.
 */
class ArchiveFetchClientTest {

	private static final String URL = "https://archive.invalid/2526/E0.csv";

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final ArchiveFetchClient client =
			new ArchiveFetchClient(builder.build(), properties());

	private static ArchiveProperties properties() {
		return new ArchiveProperties("https://archive.invalid", Path.of("/tmp/custody"),
				List.of("E0"), 1993, "test-agent", Duration.ZERO, Duration.ofSeconds(5), 0,
				Duration.ZERO, new ArchiveProperties.Sweep(false));
	}

	private static final ArchiveTarget E0 = new ArchiveTarget("E0", "2526");

	@Test
	void bodyAndValidatorsComeBackFromA200() {
		server.expect(requestTo(URL))
				.andExpect(header(HttpHeaders.USER_AGENT, "test-agent"))
				.andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
				.andRespond(withStatus(HttpStatus.OK)
						.header(HttpHeaders.ETAG, "\"31aae-652a8ffeb13c4\"")
						.header(HttpHeaders.LAST_MODIFIED, "Mon, 25 May 2026 19:01:01 GMT")
						.body("Div,Date\nE0,15/08/25\n"));

		FetchResult result = client.fetch(E0, null);

		assertThat(result).isInstanceOfSatisfying(FetchResult.Fetched.class, fetched -> {
			assertThat(new String(fetched.body(), StandardCharsets.UTF_8)).startsWith("Div,Date");
			assertThat(fetched.etag()).isEqualTo("\"31aae-652a8ffeb13c4\"");
			assertThat(fetched.lastModified()).isEqualTo("Mon, 25 May 2026 19:01:01 GMT");
		});
	}

	@Test
	void anEtagWeHoldIsSentBackAndA304MeansNothingChanged() {
		server.expect(requestTo(URL))
				.andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"held\""))
				.andRespond(withStatus(HttpStatus.NOT_MODIFIED));

		assertThat(client.fetch(E0, new Validators("\"held\"", "Mon, 25 May 2026 19:01:01 GMT")))
				.isInstanceOf(FetchResult.Unchanged.class);
	}

	/**
	 * An ETag is exact; a date comparison is only as good as the clock. So the
	 * date is the fallback, never sent alongside.
	 */
	@Test
	void lastModifiedIsUsedOnlyWhenThereIsNoEtag() {
		server.expect(requestTo(URL))
				.andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, "Mon, 25 May 2026 19:01:01 GMT"))
				.andExpect(headerDoesNotExist(HttpHeaders.IF_NONE_MATCH))
				.andRespond(withStatus(HttpStatus.NOT_MODIFIED));

		assertThat(client.fetch(E0, new Validators(null, "Mon, 25 May 2026 19:01:01 GMT")))
				.isInstanceOf(FetchResult.Unchanged.class);
	}

	@Test
	void a300IsNotYetPublishedRatherThanAFailure() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.MULTIPLE_CHOICES)
				.body("<html><title>300 Multiple Choices</title></html>"));

		assertThat(client.fetch(E0, null)).isInstanceOf(FetchResult.NotPublished.class);
	}

	/** One file's failure is one file's failure: nothing is thrown at the sweep. */
	@Test
	void aServerErrorIsReportedAgainstTheFileAndNotThrown() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		assertThat(client.fetch(E0, null))
				.isInstanceOfSatisfying(FetchResult.Failed.class,
						failed -> assertThat(failed.detail()).contains("500"));
	}

	// --- redirects (#286) ---------------------------------------------------
	// The archive began 302ing `www.` to its apex on 2026-09-04 and every sweep
	// failed for twelve days. The client is still Redirect.NEVER; what follows a
	// redirect is followed(), and only when it is provably the same resource.

	/** The exact shape that broke #286: same path, subdomain dropped. */
	@Test
	void aRedirectToTheSamePathOnTheApexIsFollowedOnce() {
		server.expect(requestTo("https://www.archive.invalid/2526/E0.csv"))
				.andRespond(withStatus(HttpStatus.FOUND)
						.header(HttpHeaders.LOCATION, "https://archive.invalid/2526/E0.csv"));
		server.expect(requestTo("https://archive.invalid/2526/E0.csv"))
				.andRespond(withStatus(HttpStatus.OK)
						.header(HttpHeaders.ETAG, "\"after-redirect\"")
						.body("Div,Date\nE0,15/08/25\n"));

		assertThat(wwwClient().fetch(E0, null))
				.isInstanceOfSatisfying(FetchResult.Fetched.class, fetched -> {
					assertThat(new String(fetched.body(), StandardCharsets.ISO_8859_1))
							.startsWith("Div,");
					assertThat(fetched.etag()).isEqualTo("\"after-redirect\"");
				});
		server.verify();
	}

	/**
	 * A revalidation across a redirect is still a revalidation. Without this the
	 * fix would quietly turn every conditional request into a full download —
	 * ~90 MB a sweep, which is the cost the ETag exists to avoid.
	 */
	@Test
	void theConditionalHeaderRidesAlongWithTheRedirect() {
		server.expect(requestTo("https://www.archive.invalid/2526/E0.csv"))
				.andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"held\""))
				.andRespond(withStatus(HttpStatus.FOUND)
						.header(HttpHeaders.LOCATION, "https://archive.invalid/2526/E0.csv"));
		server.expect(requestTo("https://archive.invalid/2526/E0.csv"))
				.andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"held\""))
				.andRespond(withStatus(HttpStatus.NOT_MODIFIED));

		assertThat(wwwClient().fetch(E0, new Validators("\"held\"", null)))
				.isInstanceOf(FetchResult.Unchanged.class);
		server.verify();
	}

	/**
	 * THE ONE THAT MATTERS FOR CUSTODY. A naive "same registrable domain" test
	 * reduces football-data.co.uk to co.uk and would follow this happily, writing
	 * a stranger's bytes into raw/ as though they were the archive's.
	 */
	@Test
	void aRedirectToAnotherSiteUnderTheSameSuffixIsRefused() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, "https://elsewhere.invalid/2526/E0.csv"));

		assertThat(client.fetch(E0, null))
				.isInstanceOfSatisfying(FetchResult.Failed.class,
						failed -> assertThat(failed.detail())
								.contains("302").contains("not the same resource"));
	}

	/** A different path is a different file, however familiar the host. */
	@Test
	void aRedirectToADifferentPathIsRefused() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, "https://archive.invalid/2526/E1.csv"));

		assertThat(client.fetch(E0, null))
				.isInstanceOfSatisfying(FetchResult.Failed.class,
						failed -> assertThat(failed.detail()).contains("not the same resource"));
	}

	/** Downgrading to cleartext is not the same resource either. */
	@Test
	void aRedirectToHttpIsRefused() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, "http://archive.invalid/2526/E0.csv"));

		assertThat(client.fetch(E0, null))
				.isInstanceOfSatisfying(FetchResult.Failed.class,
						failed -> assertThat(failed.detail()).contains("not the same resource"));
	}

	/** Bounded at one hop, so a redirect loop cannot spin the sweep. */
	@Test
	void aSecondRedirectIsRefusedRatherThanFollowed() {
		server.expect(requestTo("https://www.archive.invalid/2526/E0.csv"))
				.andRespond(withStatus(HttpStatus.FOUND)
						.header(HttpHeaders.LOCATION, "https://archive.invalid/2526/E0.csv"));
		server.expect(requestTo("https://archive.invalid/2526/E0.csv"))
				.andRespond(withStatus(HttpStatus.FOUND)
						.header(HttpHeaders.LOCATION, "https://www.archive.invalid/2526/E0.csv"));

		assertThat(wwwClient().fetch(E0, null))
				.isInstanceOfSatisfying(FetchResult.Failed.class,
						failed -> assertThat(failed.detail()).contains("more than once"));
		server.verify();
	}

	/** A 3xx with nowhere to go says so, rather than being followed to null. */
	@Test
	void aRedirectWithoutALocationIsRefused() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY));

		assertThat(client.fetch(E0, null))
				.isInstanceOfSatisfying(FetchResult.Failed.class,
						failed -> assertThat(failed.detail()).contains("without a Location"));
	}

	/** A client whose configured base carries the `www.` the real archive redirects off. */
	private ArchiveFetchClient wwwClient() {
		return new ArchiveFetchClient(builder.build(),
				new ArchiveProperties("https://www.archive.invalid", Path.of("/tmp/custody"),
						List.of("E0"), 1993, "test-agent", Duration.ZERO, Duration.ofSeconds(5), 0,
						Duration.ZERO, new ArchiveProperties.Sweep(false)));
	}
}
