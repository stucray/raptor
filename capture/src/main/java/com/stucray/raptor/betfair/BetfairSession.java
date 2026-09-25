package com.stucray.raptor.betfair;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStore;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * A Betfair session this process owns.
 *
 * <p>The recorder used to have no session of its own: it read a token the
 * overround backend cached in Postgres, which made a capture night depend on an
 * unrelated application being up. On 2026-09-01 it was not, the row was a day
 * stale, and the night died four seconds after the agent fired. Paddock logs in
 * for itself for exactly that reason — a capture may not depend on anything but
 * Betfair and this process.
 *
 * <p>Betfair's non-interactive (bot) login is the only endpoint that accepts a
 * certificate; the others are blocked for this flow. The certificate and key
 * arrive base64-encoded in the environment and are parsed by Boot's own PEM
 * support rather than by hand — an RSA-versus-EC or PKCS#1-versus-PKCS#8
 * assumption is exactly the kind of thing that works against one account's
 * certificate and fails against the next.
 */
@Component
class BetfairSession {

	private static final Logger log = LoggerFactory.getLogger(BetfairSession.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * How long a login may take to connect, and then to answer (#12).
	 *
	 * <p>The stream's own order, and for the same reason: a network that has
	 * gone away should cost an attempt, not an unbounded wait. Without it one
	 * login held for four minutes on 2026-09-25, inside a synchronized method,
	 * with the stream attempt queued behind it.
	 */
	static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(30);

	private final BetfairProperties properties;
	private final RestClient.Builder builder;
	private final RestClient identity;

	private volatile @Nullable String token;
	private volatile @Nullable RestClient certLogin;

	/** Two constructors, so the annotation says which one Spring builds. */
	@Autowired
	BetfairSession(BetfairProperties properties, RestClient.Builder builder) {
		this.properties = properties;
		this.builder = builder;
		this.identity = builder.clone().build();
	}

	/** For tests: the certlogin client supplied rather than derived from a PEM. */
	BetfairSession(BetfairProperties properties, RestClient.Builder builder,
			RestClient certLogin) {
		this(properties, builder);
		this.certLogin = certLogin;
	}

	/**
	 * The client that carries the certificate, built on first use.
	 *
	 * <p>Lazily, deliberately. Parsing the PEM in the constructor makes a
	 * malformed certificate a failure to start the <em>application</em> — and the
	 * read side, which is most of this application, has no business failing
	 * because a credential it never uses is malformed. Built here, the same
	 * problem surfaces at the login that needs it, with a message about the
	 * certificate.
	 */
	private RestClient certLoginClient() {
		RestClient current = certLogin;
		if (current != null) {
			return current;
		}
		RestClient built = builder.clone()
				.requestFactory(loginRequestFactory(sslContext(properties), LOGIN_TIMEOUT))
				.build();
		certLogin = built;
		return built;
	}

	/**
	 * The certificate-carrying request factory, bounded at both ends.
	 *
	 * <p>{@code HttpClient}'s default connect timeout is none, and so is the
	 * request factory's read timeout: each has to be said, and each covers a
	 * different stall (a network that does not answer the SYN, and a server that
	 * accepted and went quiet).
	 */
	static JdkClientHttpRequestFactory loginRequestFactory(SSLContext sslContext, Duration timeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().sslContext(sslContext).connectTimeout(timeout).build());
		factory.setReadTimeout(timeout);
		return factory;
	}

	/**
	 * The current session token, logging in if there is not one.
	 *
	 * @throws BetfairException if this process has no credentials, or Betfair
	 *     refuses them — both situations where carrying on would mean pretending
	 *     to capture
	 */
	String token() {
		String current = token;
		return current != null ? current : login();
	}

	/**
	 * Throw the current token away, so the next call logs in again.
	 *
	 * <p>Deliberately not a re-login here: the caller that noticed the session
	 * fault is usually somewhere a second round trip inline is the wrong thing.
	 */
	void invalidate() {
		token = null;
	}

	private synchronized String login() {
		String current = token;
		if (current != null) {
			return current;
		}
		if (!properties.configured()) {
			throw new BetfairException(
					"no Betfair credentials in this process: start it under `sops exec-env` "
							+ "(see bin/up), or leave the recorder disabled", "NO_CREDENTIALS");
		}
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("username", properties.username());
		form.add("password", properties.password());
		Map<?, ?> response;
		try {
			response = json(certLoginClient().post()
					.uri(properties.certLoginUrl())
					.header("X-Application", properties.appKey())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(String.class));
		} catch (RuntimeException e) {
			throw new BetfairException("certlogin failed: " + e.getMessage(), e);
		}
		Object status = response == null ? null : response.get("loginStatus");
		Object sessionToken = response == null ? null : response.get("sessionToken");
		if (!"SUCCESS".equals(status) || sessionToken == null) {
			// INVALID_USERNAME_OR_PASSWORD, ACCOUNT_NOW_LOCKED, CERT_AUTH_REQUIRED,
			// PENDING_AUTH — every one a human problem, and worth naming rather
			// than reporting as "login failed".
			throw new BetfairException("certlogin refused: loginStatus=" + status,
					String.valueOf(status));
		}
		String fresh = sessionToken.toString();
		token = fresh;
		log.info("Betfair session established");
		return fresh;
	}

	/**
	 * Keep the session alive.
	 *
	 * <p>Betfair expires an idle session in about four hours, and <b>the stream
	 * does not count as activity</b> — only this endpoint does. A resident
	 * recorder therefore has to say something every few hours or lose a session
	 * it is actively using.
	 *
	 * <p>Never throws. A failed keep-alive is not worth taking anything down
	 * for: the next call re-authenticates on its own fault code.
	 */
	@Scheduled(initialDelayString = "${raptor.betfair.keep-alive-interval:3h}",
			fixedDelayString = "${raptor.betfair.keep-alive-interval:3h}")
	void keepAlive() {
		String current = token;
		if (current == null) {
			return;
		}
		try {
			Map<?, ?> response = json(identity.post()
					.uri(properties.keepAliveUrl())
					.header("X-Application", properties.appKey())
					.header("X-Authentication", current)
					.retrieve()
					.body(String.class));
			Object status = response == null ? "no body" : response.get("status");
			if (!"SUCCESS".equals(status)) {
				log.warn("keepAlive said {}; dropping the token so the next call re-logs in",
						status);
				invalidate();
			}
		} catch (RuntimeException e) {
			log.warn("keepAlive failed ({}); the next call will re-authenticate", e.toString());
		}
	}

	/**
	 * Betfair's identity endpoints answer JSON as {@code text/plain}.
	 *
	 * <p>Verified against the live API: {@code certlogin} returns a JSON body
	 * with {@code Content-Type: text/plain;charset=ISO-8859-1}, so asking a
	 * message converter for a {@code Map} fails with "no suitable
	 * HttpMessageConverter" — <b>and a mock server serving
	 * {@code application/json} passes happily</b>, which is exactly what the
	 * unit tests here did until a live smoke run said otherwise. Reading the
	 * body as text and parsing it explicitly removes the content type from the
	 * question entirely.
	 */
	private static Map<?, ?> json(@Nullable String body) {
		if (body == null || body.isBlank()) {
			return Map.of();
		}
		try {
			return MAPPER.readValue(body, Map.class);
		} catch (RuntimeException e) {
			throw new BetfairException("could not read Betfair's response: "
					+ body.strip().substring(0, Math.min(200, body.strip().length())), e);
		}
	}

	/**
	 * An SSL context carrying the client certificate.
	 *
	 * <p>Boot's {@code PemSslStore} does the parsing. Hand-rolling it means
	 * choosing a {@code KeyFactory} algorithm and a key encoding up front, which
	 * is how a login works on one machine and fails on the next.
	 */
	private static SSLContext sslContext(BetfairProperties properties) {
		try {
			PemSslStoreDetails details = PemSslStoreDetails
					.forCertificate(decode(properties.certPemBase64()))
					.withPrivateKey(decode(properties.keyPemBase64()));
			PemSslStore store = PemSslStore.load(details);
			return SslBundle.of(new PemSslStoreBundle(store, null)).createSslContext();
		} catch (RuntimeException e) {
			throw new BetfairException("the Betfair client certificate did not load; cert and "
					+ "key must be a matching PEM pair, base64-encoded", e);
		}
	}

	private static String decode(String base64) {
		return new String(Base64.getMimeDecoder().decode(base64), StandardCharsets.UTF_8);
	}
}
