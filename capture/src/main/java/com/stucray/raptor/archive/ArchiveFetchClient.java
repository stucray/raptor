package com.stucray.raptor.archive;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * One conditional GET against football-data.co.uk.
 *
 * <p>Everything expensive about a sweep is avoided here. The server serves both
 * {@code ETag} and {@code Last-Modified}, and {@code ETag} is preferred because
 * it is exact where a date comparison is only as good as the clock. So a file
 * already held costs one request and no body — 748 of those are a few hundred
 * KB against ~90 MB.
 *
 * <p><b>A status is never an exception here.</b> {@code 304} and {@code 300} are
 * both load-bearing answers rather than errors, and even a genuine {@code 5xx}
 * must cost one file rather than the sweep, so every response is mapped to a
 * {@link FetchResult} and nothing is thrown for the caller to catch.
 */
@Component
class ArchiveFetchClient {

	private static final Logger log = LoggerFactory.getLogger(ArchiveFetchClient.class);

	/**
	 * The server publishes {@code x-ws-ratelimit-limit: 1000}. Below this many
	 * remaining, say so once per sweep — a full sweep is 748 requests, so the
	 * headroom is real but not large, and a silent throttle would look exactly
	 * like an outage.
	 */
	private static final int RATE_LIMIT_FLOOR = 100;

	private final RestClient client;
	private final ArchiveProperties properties;

	@Autowired
	ArchiveFetchClient(RestClient.Builder builder, ArchiveProperties properties) {
		this(builder.clone().requestFactory(requestFactory(properties)).build(), properties);
	}

	/**
	 * The constructor a test uses, so that a {@code RestClient} bound to a mock
	 * server survives — the one above replaces the request factory, which is
	 * exactly what such a binding is.
	 */
	ArchiveFetchClient(RestClient client, ArchiveProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	private static JdkClientHttpRequestFactory requestFactory(ArchiveProperties properties) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder()
						.connectTimeout(properties.timeout())
						// STILL NEVER, and now load-bearing rather than incidental:
						// a redirect chased silently is a fetch of something other
						// than what was asked for, and what lands in `raw/` is
						// evidence. Redirects are followed HERE instead — see
						// followed(), which admits only the same resource, one hop.
						// Handing the decision to the JDK would hand it to a
						// library that cannot know what this archive's paths mean.
						.followRedirects(HttpClient.Redirect.NEVER)
						.build());
		factory.setReadTimeout(properties.timeout());
		// Store the bytes the archive actually serves, not a decoded stand-in.
		factory.enableCompression(false);
		return factory;
	}

	/**
	 * Fetch one file, revalidating against {@code prior} when we hold validators.
	 *
	 * @param prior what the last fetch of this path recorded, or null for a path
	 *     never seen — in which case this is an unconditional GET
	 */
	FetchResult fetch(ArchiveTarget target, @Nullable Validators prior) {
		String url = properties.urlFor(target);
		Exception last = null;
		for (int attempt = 0; attempt <= properties.retries(); attempt++) {
			try {
				return exchange(url, prior);
			}
			catch (ResourceAccessException | IllegalStateException e) {
				// A dropped connection is a transport failure, not an answer: it
				// is worth one retry and never worth the sweep. `fetch.py` found
				// this the expensive way — ConnectionResetError is a plain
				// OSError, inheriting from neither of the two exception types its
				// first version caught, and it ended an unattended run 37 minutes
				// and 748 requests deep.
				last = e;
				sleep(properties.backoff().multipliedBy(1L << attempt));
			}
		}
		return new FetchResult.Failed(String.valueOf(last));
	}

	private FetchResult exchange(String url, @Nullable Validators prior) {
		return exchange(url, prior, true);
	}

	/**
	 * @param mayRedirect whether a same-resource redirect may still be followed.
	 *     False on the second call, which bounds the chain at one hop
	 */
	private FetchResult exchange(String url, @Nullable Validators prior, boolean mayRedirect) {
		RestClient.RequestHeadersSpec<?> request = client.get().uri(url)
				.header(HttpHeaders.USER_AGENT, properties.userAgent());
		if (prior != null && prior.etag() != null) {
			request = request.header(HttpHeaders.IF_NONE_MATCH, prior.etag());
		}
		else if (prior != null && prior.lastModified() != null) {
			request = request.header(HttpHeaders.IF_MODIFIED_SINCE, prior.lastModified());
		}
		// Where a redirect says to go, filled in by the callback below. A holder
		// rather than a fifth FetchResult: a redirect is not an outcome of a
		// fetch, it is a step inside one, and the sealed interface's four cases
		// are what the caller reasons about.
		@Nullable String[] redirectTo = new @Nullable String[1];
		int[] redirectCode = new int[1];
		FetchResult result = request.exchange((req, response) -> {
			HttpStatusCode status = response.getStatusCode();
			warnOnLowRateLimit(response.getHeaders());
			if (status.isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
				return new FetchResult.Unchanged();
			}
			// BEFORE the redirect branch, and it has to be: a 300 is in the 3xx
			// range and carries no Location, but it is an ANSWER here — the
			// season is not published — not a redirect that failed to say where.
			if (status.isSameCodeAs(HttpStatus.MULTIPLE_CHOICES)) {
				return new FetchResult.NotPublished();
			}
			if (status.is3xxRedirection()) {
				redirectTo[0] = response.getHeaders().getFirst(HttpHeaders.LOCATION);
				redirectCode[0] = status.value();
				return new FetchResult.Failed("HTTP " + status.value());
			}
			if (!status.is2xxSuccessful()) {
				return new FetchResult.Failed("HTTP " + status.value());
			}
			return fetched(response);
		});
		if (redirectCode[0] != 0) {
			return followed(url, redirectTo[0], redirectCode[0], prior, mayRedirect);
		}
		return result;
	}

	/**
	 * Follow a redirect only when it is provably the <em>same resource</em>.
	 *
	 * <p>The client is {@code Redirect.NEVER} and stays that way. The reason is
	 * unchanged and is about custody rather than about politeness: what lands in
	 * {@code raw/} is evidence, and a redirect chased blindly makes it a fetch of
	 * something other than what was asked for, with nothing on disk to show the
	 * difference. Letting the JDK follow redirects would hand that decision to a
	 * library that cannot know what this archive's paths mean.
	 *
	 * <p>So the test is narrow, and deliberately narrower than a browser's: the
	 * path must be identical, the scheme must still be {@code https}, and the
	 * host may differ only by a leading subdomain. That admits exactly the case
	 * that
	 * broke #286 — football-data.co.uk began 302ing {@code www.} to its apex,
	 * which is the same bytes at the same path — and admits nothing else. A
	 * redirect to a different path, a different site, or plain HTTP is still a
	 * {@link FetchResult.Failed} carrying its status, because those are the
	 * shapes that would put the wrong file into custody.
	 *
	 * <p>One hop, and the caller's conditional headers ride along with it, so a
	 * revalidation across a redirect is still a revalidation rather than a
	 * silent re-download of ~90 MB.
	 */
	private FetchResult followed(String from, @Nullable String location, int code,
			@Nullable Validators prior, boolean mayRedirect) {
		if (location == null) {
			return new FetchResult.Failed("HTTP " + code + " without a Location");
		}
		if (!mayRedirect) {
			return new FetchResult.Failed("HTTP " + code + " redirected more than once");
		}
		URI target;
		try {
			target = URI.create(from).resolve(location);
		}
		catch (IllegalArgumentException e) {
			return new FetchResult.Failed("HTTP " + code + " to an unparseable " + location);
		}
		if (!sameResource(URI.create(from), target)) {
			return new FetchResult.Failed("HTTP " + code + " to " + target
					+ ", which is not the same resource");
		}
		log.info("{} redirected to {}; following once", from, target);
		return exchange(target.toString(), prior, false);
	}

	/** Same path, still https, and a host that differs only by a subdomain. */
	private static boolean sameResource(URI from, URI to) {
		String fromHost = from.getHost();
		String toHost = to.getHost();
		if (fromHost == null || toHost == null) {
			return false;
		}
		return "https".equals(to.getScheme())
				&& from.getPath().equals(to.getPath())
				&& subdomainOfEachOther(fromHost.toLowerCase(Locale.ROOT),
						toHost.toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether two hosts differ only by a leading subdomain.
	 *
	 * <p><b>Not a "same registrable domain" test, deliberately.</b> The obvious
	 * implementation — compare the last two labels — is wrong on exactly the host
	 * this exists for: {@code football-data.co.uk} reduces to {@code co.uk}, and
	 * so does {@code anything-at-all.co.uk}, so that version would follow a
	 * redirect to a stranger's site and write its bytes into custody as though
	 * they were the archive's. Getting it right in general needs the public
	 * suffix list, which is a dependency and a data file that goes stale.
	 *
	 * <p>It does not need to be right in general. The question here is only
	 * whether the server dropped or added a subdomain on the host we ourselves
	 * asked for, so the comparison is anchored on that host rather than on any
	 * guess about where the site boundary sits: equal, or one is the other with
	 * labels prepended. {@code www.football-data.co.uk} → {@code
	 * football-data.co.uk} passes; {@code evil.co.uk} does not.
	 */
	private static boolean subdomainOfEachOther(String a, String b) {
		return a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a);
	}

	private FetchResult fetched(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
		try {
			byte[] body = response.getBody().readAllBytes();
			HttpHeaders headers = response.getHeaders();
			return new FetchResult.Fetched(body,
					headers.getFirst(HttpHeaders.ETAG),
					headers.getFirst(HttpHeaders.LAST_MODIFIED));
		}
		catch (IOException e) {
			// Mid-body, so the retry loop above is the right place for it: a
			// truncated CSV must never reach custody, and a half-read body is
			// indistinguishable from a short file once it is on disk.
			throw new IllegalStateException("body read failed", e);
		}
	}

	private void warnOnLowRateLimit(HttpHeaders headers) {
		String remaining = headers.getFirst("x-ws-ratelimit-remaining");
		if (remaining == null) {
			return;
		}
		try {
			int left = Integer.parseInt(remaining.trim());
			if (left < RATE_LIMIT_FLOOR) {
				log.warn("football-data.co.uk rate limit is down to {} requests", left);
			}
		}
		catch (NumberFormatException ignored) {
			// A header we only log about must not be able to fail a fetch.
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while backing off", e);
		}
	}
}
