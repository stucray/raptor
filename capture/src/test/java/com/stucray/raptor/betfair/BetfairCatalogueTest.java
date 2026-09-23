package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.stucray.raptor.scope.CatalogueMarket;
import com.stucray.raptor.scope.MarketCatalogue;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Mapping Betfair's REST responses into what scope decides on.
 *
 * <p>The responses are <b>synthetic, and their shape is pinned to real
 * captures</b> (#304; see {@code betfair-rest/README.md}). They used to be
 * verbatim captured nodes, and those captures now live in custody, because
 * Betfair's data may not be committed. What they guarded against still holds:
 * PRD #95 shipped three sport mappers whose unit tests passed against invented
 * fixtures and which were all broken on first contact with the real API, because
 * fixtures and mapper came from the same head and agreed only with each other.
 * So the ids, names and prices here are invented, and the node shapes are not:
 * {@link SyntheticRestShapeTest} fails the build if a response here carries a key,
 * a key set or a status that the captured response for the same endpoint did not.
 *
 * <p>The capture immediately earned its keep. The catalogue does not carry
 * {@code marketType} unless {@code MARKET_DESCRIPTION} is requested — it
 * carries {@code marketName}, "Over/Under 2.5 Goals" — so a mapper written from
 * intuition would have derived the canonical code from a display label, which
 * is the mistake that cost PRD #115 a re-cut slice.
 */
class BetfairCatalogueTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Instant NOW = Instant.parse("2026-09-02T03:52:00Z");

	private final BetfairRest rest = mock(BetfairRest.class);
	private final CaptureSelection selection = mock(CaptureSelection.class);
	private final BetfairCatalogue catalogue = new BetfairCatalogue(rest, selection,
			new BetfairProperties("app-key", "user", "password", "cert", "key",
					"https://certlogin.invalid", "https://keepalive.invalid",
					"https://rest.invalid/", java.nio.file.Path.of("config/capture.properties")),
			Clock.fixed(NOW, ZoneOffset.UTC));

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void captureTheLog() {
		appender.start();
		((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(BetfairCatalogue.class)).addAppender(appender);
	}

	@AfterEach
	void releaseTheLog() {
		((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(BetfairCatalogue.class)).detachAppender(appender);
	}

	@BeforeEach
	void wireTheRealResponses() {
		when(selection.current()).thenReturn(new CaptureSelection.Selection(
				List.of("English Premier League", "Italian Serie B"),
				List.of("MATCH_ODDS", "OVER_UNDER_15", "OVER_UNDER_25", "OVER_UNDER_35"),
				List.of("GB")));
		// doReturn rather than when/thenReturn: post() infers its element type
		// from the ParameterizedTypeReference argument, and a matcher erases that,
		// so the checked form cannot be given a concretely-typed list.
		doReturn(fixture("list-competitions")).when(rest).post(eq("listCompetitions/"), any(), any());
		doReturn(fixture("list-market-catalogue")).when(rest)
				.post(eq("listMarketCatalogue/"), any(), any());
		doReturn(fixture("list-market-book")).when(rest).post(eq("listMarketBook/"), any(), any());
	}

	/** The canonical code, from the description — never from the display name. */
	@Test
	void readsMarketTypeFromTheDescriptionNotTheMarketName() {
		List<CatalogueMarket> markets = allMarkets();

		assertThat(markets).extracting(CatalogueMarket::marketType)
				.contains("MATCH_ODDS", "OVER_UNDER_15", "OVER_UNDER_25", "OVER_UNDER_35")
				.doesNotContain("Match Odds", "Over/Under 2.5 Goals");
	}

	/** Event, competition and country come across, and country is the event's. */
	@Test
	void carriesTheIdentifyingFieldsScopeNeeds() {
		CatalogueMarket market = allMarkets().getFirst();

		assertThat(market.marketId()).startsWith("1.");
		assertThat(market.eventId()).isNotBlank();
		assertThat(market.eventName()).contains(" v ");
		assertThat(market.competitionId()).isNotBlank();
		assertThat(market.countryCode()).isEqualTo("GB");
		assertThat(market.kickoff()).isNotNull();
	}

	/**
	 * State comes from the book, because the catalogue has none.
	 *
	 * <p>Every book node is {@code OPEN} and not in-play, as every captured one
	 * was (the capture ran at 04:52 UK time), so this asserts the join happened,
	 * not a transition. The
	 * transitions are exercised through the domain type in the scope tests
	 * rather than through a wire shape nobody has captured yet.
	 */
	@Test
	void takesInPlayAndStatusFromTheBook() {
		assertThat(requestedMarkets())
				.filteredOn(market -> "1.900005011".equals(market.marketId()))
				.singleElement()
				.satisfies(market -> {
					assertThat(market.status()).isEqualTo("OPEN");
					assertThat(market.inPlay()).isFalse();
				});
	}

	/**
	 * A market the book did not answer for is still a market.
	 *
	 * <p>Not a contrived case: the captured book covered the first twenty market
	 * ids of the catalogue query, and one real catalogue market fell outside it.
	 * So the fixtures carry a catalogue market with no book row, and the mapper
	 * has to carry it through as open-and-not-in-play rather than dropping it —
	 * which is what a catalogue-only view would have said anyway. Scope's guards
	 * end such a market; a missing book row delays a decision instead of making a
	 * wrong one.
	 */
	@Test
	void carriesThroughAMarketTheBookDidNotAnswerFor() {
		assertThat(requestedMarkets())
				.filteredOn(market -> "1.900005001".equals(market.marketId()))
				.isNotEmpty()
				.allSatisfy(market -> {
					assertThat(market.status()).isNull();
					assertThat(market.inPlay()).isFalse();
					assertThat(market.marketType()).isEqualTo("MATCH_ODDS");
				});
	}

	/** Requested competitions and the control set are separate queries, marked. */
	@Test
	void marksWhichQueryIsTheRequestedOne() {
		List<MarketCatalogue.CatalogueQuery> queries = catalogue.poll(Duration.ofHours(24));

		assertThat(queries).hasSize(2);
		assertThat(queries.getFirst().requested()).isTrue();
		assertThat(queries.getLast().requested()).isFalse();
	}

	/**
	 * A league that does not resolve is skipped, and the rest of the night runs.
	 *
	 * <p>"Italian Serie B" is deliberately in the requested list and deliberately
	 * absent from the {@code listCompetitions} response, as it was from the real
	 * one on the day of the capture: a league between rounds
	 * during an international break. The version of this code that exited
	 * instead cost a full matchday on 2026-08-31.
	 */
	@Test
	void skipsALeagueThatDoesNotResolveRatherThanLosingTheNight() {
		List<MarketCatalogue.CatalogueQuery> queries = catalogue.poll(Duration.ofHours(24));

		assertThat(queries).isNotEmpty();
		assertThat(queries.getFirst().markets()).isNotEmpty();
	}

	/**
	 * Following a market by id, past the point discovery can see it.
	 *
	 * <p>This fixture is what #127 turned on. Discovery pins
	 * {@code marketStartTime} to {@code [now, now + horizon]}, so a market leaves
	 * its answer at kickoff and never returns — and for two nights that made the
	 * six-hour abandonment guard the only exit any market could take.
	 *
	 * <p>The nodes are shaped on a {@code listMarketBook} call made on 2026-09-04
	 * for two of the <em>previous night's</em> market ids: a full day after
	 * kickoff, by id alone, the upstream still answers, and it answers with
	 * exactly the two facts scope needs. Note {@code inplay: true} alongside
	 * {@code status: CLOSED} — Betfair does not clear the in-play flag when a
	 * market closes, so a mapper that read in-play first would never see a close.
	 */
	@Test
	void followsAMarketByIdAndReadsTheStateDiscoveryCannotSee() {
		doReturn(fixture("list-market-book-after-kickoff"))
				.when(rest).post(eq("listMarketBook/"), any(), any());

		List<MarketCatalogue.MarketState> states =
				catalogue.follow(List.of("1.900005101", "1.900005102"));

		assertThat(states).hasSize(2).allSatisfy(state -> {
			assertThat(state.status()).isEqualTo("CLOSED");
			assertThat(state.inPlay()).isTrue();
		});
	}

	/** Nothing to follow is nothing asked of Betfair. */
	@Test
	void followsNothingWhenScopeHasNothingOutsideTheCatalogue() {
		assertThat(catalogue.follow(List.of())).isEmpty();
		verify(rest, never()).post(eq("listMarketBook/"), any(), any());
	}

	/** Nothing configured is nothing asked of Betfair. */
	@Test
	void asksForNothingWhenNothingIsConfigured() {
		when(selection.current()).thenReturn(CaptureSelection.Selection.EMPTY);

		assertThat(catalogue.poll(Duration.ofHours(4))).isEmpty();
	}


	/**
	 * A full page on the requested query is a loud, self-naming WARN.
	 *
	 * <p>The helper serves both queries and used to name neither, so establishing
	 * that the cap hit on 2026-09-05 was the benign control one took a query
	 * against {@code raw.market_scope} rather than the log (#168). The requested
	 * side filling its page is target-league fixtures that are never
	 * <em>discovered</em>, so nothing downstream can report them missing.
	 *
	 * <p>The page is the first catalogue node repeated to 200 with distinct market
	 * ids. Nothing about its <em>shape</em> differs from the pinned one, only its
	 * length, which is the one property this test is about.
	 */
	@Test
	void namesTheRequestedQueryLoudlyWhenItFillsThePage() {
		doReturn(fullPage()).when(rest).post(eq("listMarketCatalogue/"),
				argThat(BetfairCatalogueTest::byCompetition), any());

		catalogue.poll(Duration.ofHours(24));

		assertThat(logged(Level.WARN)).anySatisfy(message ->
				assertThat(message).contains("requested competitions").contains("200"));
	}

	/** The control set filling its page is filler the planner trims: INFO. */
	@Test
	void namesTheControlQueryQuietlyWhenItFillsThePage() {
		doReturn(fullPage()).when(rest).post(eq("listMarketCatalogue/"),
				argThat(node -> !byCompetition(node)), any());

		catalogue.poll(Duration.ofHours(24));

		assertThat(logged(Level.INFO)).anySatisfy(message ->
				assertThat(message).contains("control countries").contains("200"));
		assertThat(logged(Level.WARN))
				.noneSatisfy(message -> assertThat(message).contains("page"));
	}

	/** Which of the two queries a request body is, by the filter it carries. */
	private static boolean byCompetition(@Nullable Object body) {
		return body instanceof Map<?, ?> map
				&& map.get("filter") instanceof Map<?, ?> filter
				&& filter.containsKey("competitionIds");
	}

	private static List<Map<String, Object>> fullPage() {
		List<Map<String, Object>> node = fixture("list-market-catalogue");
		List<Map<String, Object>> page = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			Map<String, Object> copy = new java.util.LinkedHashMap<>(node.getFirst());
			copy.put("marketId", "1.9990" + i);
			page.add(copy);
		}
		return page;
	}

	private List<String> logged(Level level) {
		return appender.list.stream().filter(event -> event.getLevel() == level)
				.map(ILoggingEvent::getFormattedMessage).toList();
	}

	/** One query's markets: the union is scope's business, not the mapper's. */
	private List<CatalogueMarket> requestedMarkets() {
		return catalogue.poll(Duration.ofHours(24)).getFirst().markets();
	}

	private List<CatalogueMarket> allMarkets() {
		return catalogue.poll(Duration.ofHours(24)).stream()
				.flatMap(query -> query.markets().stream())
				.toList();
	}

	private static List<Map<String, Object>> fixture(String name) {
		try (InputStream in = BetfairCatalogueTest.class
				.getResourceAsStream("/betfair-rest/" + name + ".json")) {
			if (in == null) {
				throw new IllegalStateException("missing fixture: " + name);
			}
			return MAPPER.readValue(in, MAPPER.getTypeFactory()
					.constructCollectionType(List.class, Map.class));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
