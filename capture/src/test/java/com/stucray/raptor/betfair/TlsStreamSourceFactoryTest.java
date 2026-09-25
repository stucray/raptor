package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.scope.CaptureScope;
import com.stucray.raptor.scope.SubscriptionPlan;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

/**
 * Log in first, then open the stream (#12).
 *
 * <p>On 2026-09-25 a stream attempt connected, then asked for a token, and the
 * certificate login took four minutes on a failing network. Betfair closed the
 * unauthenticated connection ({@code TIMEOUT}) five milliseconds after the
 * login returned. Every second spent logging in was spent on Betfair's clock.
 */
class TlsStreamSourceFactoryTest {

	/**
	 * A login that fails costs an attempt, not a connection — and is reported as
	 * an attempt that failed.
	 *
	 * <p>Two things at once. The listener must see no connection, because the
	 * token is needed before the socket is worth opening. And the failure must
	 * reach the supervisor as an {@link IOException}: the supervisor retries
	 * those, and a {@link BetfairException} is a {@code RuntimeException} that
	 * its loop does not catch.
	 */
	@Test
	@Timeout(10)
	void aLoginThatFailsNeverOpensTheSocketAndIsAnOrdinaryFailedAttempt() throws Exception {
		AtomicInteger accepted = new AtomicInteger();
		try (ServerSocket listener = new ServerSocket(0)) {
			Thread.ofVirtual().start(() -> {
				while (!listener.isClosed()) {
					try (Socket ignored = listener.accept()) {
						accepted.incrementAndGet();
					} catch (IOException closed) {
						return;
					}
				}
			});
			TlsStreamSourceFactory factory = new TlsStreamSourceFactory(
					noCredentials(), betfair(""), stream(listener.getLocalPort()),
					inScope(), Clock.systemUTC());

			assertThatThrownBy(factory::open)
					.isInstanceOf(IOException.class)
					.hasCauseInstanceOf(BetfairException.class);
			assertThat(accepted).hasValue(0);
		}
	}

	private static BetfairSession noCredentials() {
		return new BetfairSession(betfair(""), RestClient.builder());
	}

	private static BetfairProperties betfair(String appKey) {
		return new BetfairProperties(appKey, "", "", "", "", "https://certlogin.invalid",
				"https://keepalive.invalid", "https://rest.invalid/",
				Path.of("config/capture.properties"));
	}

	private static StreamProperties stream(int port) {
		return new StreamProperties(true, "127.0.0.1", port, Duration.ofSeconds(1),
				Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ZERO,
				List.of("EX_MARKET_DEF"), Duration.ofSeconds(60));
	}

	private static CaptureScope inScope() {
		return new CaptureScope() {
			@Override
			public SubscriptionPlan plan() {
				return new SubscriptionPlan(List.of("1.900000001"), 0, 0);
			}

			@Override
			public void subscribed(List<String> marketIds) {
			}
		};
	}
}
