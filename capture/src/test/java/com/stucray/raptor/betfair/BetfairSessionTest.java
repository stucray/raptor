package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Owning a session, rather than borrowing one.
 *
 * <p>The recorder's only source of a token used to be a row another application
 * cached in Postgres, which made a capture night depend on that application
 * being up. It was not, on 2026-09-01, and the row was a day stale: the night
 * died four seconds after the agent fired. These tests pin the behaviour that
 * replaced it.
 */
class BetfairSessionTest {

	/**
	 * What Betfair actually answers with.
	 *
	 * <p>The identity endpoints return JSON bodies typed {@code text/plain}, and
	 * these tests served {@code application/json} until a live smoke run failed
	 * with "no suitable HttpMessageConverter" against the real API. A mock that
	 * is more polite than the upstream proves nothing about the upstream.
	 */
	private static final MediaType BETFAIR_CONTENT_TYPE = MediaType.TEXT_PLAIN;

	private final RestClient.Builder builder = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	/**
	 * A login that gets no answer gives up, instead of holding every caller (#12).
	 *
	 * <p>The certificate-login client had no connect or read timeout, and
	 * {@code login()} is synchronized: on 2026-09-25 one login waited four minutes
	 * on a failing network while the stream attempt queued behind it. A listener
	 * that accepts and never replies is that network, minus the wait.
	 */
	@Test
	@Timeout(10)
	void aLoginThatGetsNoAnswerTimesOut() throws Exception {
		List<Socket> held = new ArrayList<>();
		try (ServerSocket silent = new ServerSocket(0)) {
			Thread.ofVirtual().start(() -> {
				while (!silent.isClosed()) {
					try {
						held.add(silent.accept());   // accepted, and never answered
					} catch (IOException closed) {
						return;
					}
				}
			});
			RestClient client = RestClient.builder()
					.requestFactory(BetfairSession.loginRequestFactory(SSLContext.getDefault(),
							Duration.ofMillis(500)))
					.build();

			assertThatThrownBy(() -> client.post()
					.uri("http://127.0.0.1:" + silent.getLocalPort() + "/api/certlogin")
					.retrieve()
					.body(String.class))
					.hasRootCauseInstanceOf(java.net.http.HttpTimeoutException.class);
		} finally {
			for (Socket socket : held) {
				socket.close();
			}
		}
	}

	@Test
	void logsInOnceAndReusesTheToken() {
		BetfairSession session = session();
		server.expect(ExpectedCount.once(), requestTo("https://certlogin.invalid"))
				.andRespond(withSuccess("""
						{"sessionToken":"fresh","loginStatus":"SUCCESS"}""",
						BETFAIR_CONTENT_TYPE));

		assertThat(session.token()).isEqualTo("fresh");
		assertThat(session.token()).isEqualTo("fresh");
		server.verify();
	}

	/**
	 * A refused login names the reason.
	 *
	 * <p>{@code INVALID_USERNAME_OR_PASSWORD}, {@code ACCOUNT_NOW_LOCKED},
	 * {@code CERT_AUTH_REQUIRED}, {@code PENDING_AUTH} — every one a human
	 * problem with a different fix, and indistinguishable if the error says only
	 * that logging in failed.
	 */
	@Test
	void namesWhyBetfairRefusedTheLogin() {
		BetfairSession session = session();
		server.expect(requestTo("https://certlogin.invalid"))
				.andRespond(withSuccess("""
						{"loginStatus":"ACCOUNT_NOW_LOCKED"}""", BETFAIR_CONTENT_TYPE));

		assertThatThrownBy(session::token)
				.isInstanceOf(BetfairException.class)
				.hasMessageContaining("ACCOUNT_NOW_LOCKED");
	}

	@Test
	void logsInAgainAfterTheTokenIsInvalidated() {
		BetfairSession session = session();
		server.expect(ExpectedCount.twice(), requestTo("https://certlogin.invalid"))
				.andRespond(withSuccess("""
						{"sessionToken":"fresh","loginStatus":"SUCCESS"}""",
						BETFAIR_CONTENT_TYPE));

		session.token();
		session.invalidate();
		session.token();

		server.verify();
	}

	/**
	 * A process with no credentials says so, rather than failing at a socket.
	 *
	 * <p>The ordinary state of a developer's `spring-boot:run`: the read side is
	 * most of this application and works perfectly well without a session, so
	 * this is a message to read, not a startup failure.
	 */
	@Test
	void saysPlainlyWhenThisProcessHasNoCredentials() {
		BetfairSession session = new BetfairSession(
				new BetfairProperties("", "", "", "", "", "https://certlogin.invalid",
						"https://keepalive.invalid", "https://rest.invalid/",
						Path.of("config/capture.properties")),
				builder);

		assertThatThrownBy(session::token)
				.isInstanceOf(BetfairException.class)
				.hasMessageContaining("sops exec-env");
	}

	/**
	 * A failed keep-alive drops the token instead of hoping.
	 *
	 * <p>Betfair expires an idle session in about four hours and the stream does
	 * not count as activity, so a resident recorder that ignores a failed
	 * keep-alive discovers the lapse from the stream — which is the expensive
	 * place to discover it.
	 */
	@Test
	void dropsTheTokenWhenKeepAliveFails() {
		BetfairSession session = session();
		// The whole conversation is declared before the first request: the mock
		// server refuses expectations added after one has been made.
		server.expect(requestTo("https://certlogin.invalid"))
				.andRespond(withSuccess("""
						{"sessionToken":"fresh","loginStatus":"SUCCESS"}""",
						BETFAIR_CONTENT_TYPE));
		server.expect(requestTo("https://keepalive.invalid"))
				.andRespond(withSuccess("""
						{"status":"FAIL","error":"INVALID_SESSION_INFORMATION"}""",
						BETFAIR_CONTENT_TYPE));
		server.expect(requestTo("https://certlogin.invalid"))
				.andRespond(withSuccess("""
						{"sessionToken":"second","loginStatus":"SUCCESS"}""",
						BETFAIR_CONTENT_TYPE));

		session.token();
		session.keepAlive();

		assertThat(session.token()).isEqualTo("second");
	}

	/** Keep-alive never throws: the next call re-authenticates on its own fault. */
	@Test
	void survivesAKeepAliveThatFailsAtTheTransport() {
		BetfairSession session = session();
		server.expect(requestTo("https://certlogin.invalid"))
				.andRespond(withSuccess("""
						{"sessionToken":"fresh","loginStatus":"SUCCESS"}""",
						BETFAIR_CONTENT_TYPE));
		server.expect(requestTo("https://keepalive.invalid"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

		session.token();
		session.keepAlive();

		// The token survives: a keep-alive that could not be delivered says
		// nothing about whether the session is still good, and throwing a working
		// token away on a transport blip costs a login for no reason.
		assertThat(session.token()).isEqualTo("fresh");
	}

	/**
	 * Credentials present, and the certlogin client supplied rather than derived
	 * from a PEM: the mock server stands in for the socket, so nothing here needs
	 * a real key pair. The production path builds that client from the configured
	 * certificate at first login.
	 */
	private BetfairSession session() {
		return new BetfairSession(
				new BetfairProperties("app-key", "user", "password", "cert-b64", "key-b64",
						"https://certlogin.invalid", "https://keepalive.invalid",
						"https://rest.invalid/", Path.of("config/capture.properties")),
				builder, builder.clone().build());
	}
}
