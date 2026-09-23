package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.recorder.StreamFrame;
import com.stucray.raptor.scope.CaptureScope;
import com.stucray.raptor.scope.CatalogueMarket;
import com.stucray.raptor.scope.MarketCatalogue;
import com.stucray.raptor.scope.SubscriptionPlan;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * The whole live path, against the real Betfair, on demand.
 *
 * <p>Skipped unless {@code BETFAIR_APP_KEY} is in the environment, so CI and an
 * ordinary {@code mvn verify} never see it. To run it:
 *
 * <pre>
 * sops exec-env "$RAPTOR_SECRETS" '
 *   BETFAIR_APP_KEY="$(printenv betfair-app-key-live)" \
 *   BETFAIR_USERNAME="$(printenv betfair-username)" \
 *   BETFAIR_PASSWORD="$(printenv betfair-password)" \
 *   BETFAIR_CERT_PEM_B64="$(printenv betfair-cert-pem | base64 | tr -d "\n")" \
 *   BETFAIR_KEY_PEM_B64="$(printenv betfair-key-pem | base64 | tr -d "\n")" \
 *   ./mvnw -pl acquisition -Dtest=BetfairStreamLiveSmokeTest test'
 * </pre>
 *
 * <p>It exists because the scripted tests cannot settle the only questions that
 * matter about a socket: that TLS to this host completes, that this session
 * token authenticates on the stream as well as on REST, and that a subscription
 * built from the catalogue's market ids is one Betfair accepts. Every wire fact
 * this module has learned so far was learned this way — the certlogin content
 * type, the missing {@code Accept} header, the absent {@code marketType} — and
 * each of them passed a mock first.
 *
 * <p>Read-only: a login, a catalogue query, one subscription, and whatever the
 * book sends in the time it is listening. It places nothing and writes nothing.
 */
@EnabledIfEnvironmentVariable(named = "BETFAIR_APP_KEY", matches = ".+",
		disabledReason = "no Betfair credentials in the environment")
class BetfairStreamLiveSmokeTest {

	/** Long enough for a heartbeat at five seconds, short enough to sit through. */
	private static final Duration LISTEN = Duration.ofSeconds(20);

	@Test
	void authenticatesSubscribesAndReceivesTheBook() throws Exception {
		BetfairProperties properties = new BetfairProperties(
				System.getenv("BETFAIR_APP_KEY"), System.getenv("BETFAIR_USERNAME"),
				System.getenv("BETFAIR_PASSWORD"), System.getenv("BETFAIR_CERT_PEM_B64"),
				System.getenv("BETFAIR_KEY_PEM_B64"),
				"https://identitysso-cert.betfair.com/api/certlogin",
				"https://identitysso.betfair.com/api/keepAlive",
				"https://api.betfair.com/exchange/betting/rest/v1.0/",
				Path.of("../config/capture.properties"));
		StreamProperties stream = new StreamProperties(true, "stream-api.betfair.com", 443,
				Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(5),
				Duration.ZERO,
				List.of("EX_MARKET_DEF", "EX_ALL_OFFERS", "EX_TRADED", "EX_TRADED_VOL", "EX_LTP"),
				Duration.ofSeconds(60));
		RestClient.Builder builder = RestClient.builder();
		BetfairSession session = new BetfairSession(properties, builder);
		BetfairCatalogue catalogue = new BetfairCatalogue(
				new BetfairRest(builder, session, properties), new CaptureSelection(properties),
				properties, Clock.systemUTC());

		List<String> marketIds = catalogue.poll(Duration.ofHours(24)).stream()
				.flatMap(query -> query.markets().stream())
				.map(CatalogueMarket::marketId)
				.distinct()
				.limit(5)
				.toList();
		// An empty answer is the finding, not a reason to pass quietly: a 24-hour
		// horizon over the configured leagues plus GB is never empty in season.
		assertThat(marketIds).isNotEmpty();

		FixedScope scope = new FixedScope(marketIds);
		TlsStreamSourceFactory factory =
				new TlsStreamSourceFactory(session, properties, stream, scope, Clock.systemUTC());

		int frames = 0;
		String describe;
		try (var source = factory.open()) {
			describe = source.describe();
			long deadline = System.nanoTime() + LISTEN.toNanos();
			while (System.nanoTime() < deadline) {
				StreamFrame frame = source.next();
				if (frame == null) {
					break;
				}
				frames++;
			}
		}

		assertThat(describe).contains("stream-api.betfair.com");
		// At least the image, and at least one heartbeat inside twenty seconds.
		// Zero means the subscription was accepted and the book is not arriving,
		// which is the failure this test exists to catch.
		assertThat(frames).isPositive();
		assertThat(scope.subscribed).containsExactly(marketIds);
	}

	/** Scope for one run: whatever the catalogue just said, and nothing after. */
	private static final class FixedScope implements CaptureScope {

		private final List<String> marketIds;
		private final List<List<String>> subscribed = new java.util.ArrayList<>();

		private FixedScope(List<String> marketIds) {
			this.marketIds = marketIds;
		}

		@Override
		public SubscriptionPlan plan() {
			return new SubscriptionPlan(marketIds, 0, 0);
		}

		@Override
		public void subscribed(List<String> ids) {
			subscribed.add(List.copyOf(ids));
		}
	}
}
