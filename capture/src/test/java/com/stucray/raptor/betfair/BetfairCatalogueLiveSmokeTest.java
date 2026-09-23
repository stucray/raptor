package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.scope.CatalogueMarket;
import com.stucray.raptor.scope.MarketCatalogue;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * The whole client, against the real Betfair, on demand.
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
 *   ./mvnw -pl acquisition -Dtest=BetfairCatalogueLiveSmokeTest test'
 * </pre>
 *
 * <p>It exists because the fixture tests cannot prove the two things that
 * actually break in production: that this certificate logs in, and that the
 * projections asked for are the ones that come back. Both are claims about
 * Betfair, and only Betfair can settle them.
 *
 * <p>Read-only — a login and three catalogue queries. It places nothing and
 * writes nothing.
 */
@EnabledIfEnvironmentVariable(named = "BETFAIR_APP_KEY", matches = ".+",
		disabledReason = "no Betfair credentials in the environment")
class BetfairCatalogueLiveSmokeTest {

	private static final Duration HORIZON = Duration.ofHours(24);

	@Test
	void logsInAndReadsMarketsWithTheirCanonicalTypes() {
		BetfairProperties properties = new BetfairProperties(
				System.getenv("BETFAIR_APP_KEY"), System.getenv("BETFAIR_USERNAME"),
				System.getenv("BETFAIR_PASSWORD"), System.getenv("BETFAIR_CERT_PEM_B64"),
				System.getenv("BETFAIR_KEY_PEM_B64"),
				"https://identitysso-cert.betfair.com/api/certlogin",
				"https://identitysso.betfair.com/api/keepAlive",
				"https://api.betfair.com/exchange/betting/rest/v1.0/",
				Path.of("../config/capture.properties"));
		RestClient.Builder builder = RestClient.builder();
		BetfairSession session = new BetfairSession(properties, builder);
		BetfairCatalogue catalogue = new BetfairCatalogue(
				new BetfairRest(builder, session, properties), new CaptureSelection(properties),
				properties, Clock.systemUTC());

		List<MarketCatalogue.CatalogueQuery> queries = catalogue.poll(HORIZON);

		// A 24-hour horizon over eight leagues plus GB is never empty in season;
		// an empty answer here is the finding, not a flaky test.
		assertThat(queries).isNotEmpty();
		List<CatalogueMarket> markets = queries.stream()
				.flatMap(query -> query.markets().stream()).toList();
		assertThat(markets).isNotEmpty();
		assertThat(markets).allSatisfy(market -> {
			assertThat(market.marketId()).startsWith("1.");
			// The projection question, settled by the upstream rather than by a
			// fixture: MARKET_DESCRIPTION is what makes this a code, not a label.
			assertThat(market.marketType())
					.isIn("MATCH_ODDS", "OVER_UNDER_15", "OVER_UNDER_25", "OVER_UNDER_35");
		});
		assertThat(markets).anySatisfy(market -> assertThat(market.status()).isNotNull());
	}
}
