package com.stucray.raptor.scope;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Scope: what the recorder is trying to capture, and — the part that actually
 * costs something — when it stops.
 *
 * <p>Every guard here exists because the alternative is a market held open
 * forever. Betfair's cap is 200, so a handful of abandoned or never-closed
 * fixtures is a Saturday's worth of capacity gone, and nothing anywhere would
 * say why. The states are asserted against the real table because the state
 * machine lives half in SQL — the upsert's conflict clause is what stops a poll
 * walking a live match back to PENDING, and that is not observable from a mock.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MarketScopeIntegrationTest.Fakes.class})
class MarketScopeIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-09-05T13:00:00Z");
	private static final Instant KICKOFF = Instant.parse("2026-09-05T14:00:00Z");

	@Autowired MarketScopeService service;
	@Autowired FakeCatalogue catalogue;
	@Autowired MutableClock clock;
	@Autowired @Acquisition JdbcClient jdbc;

	@BeforeEach
	void reset() {
		catalogue.markets.clear();
		catalogue.control.clear();
		catalogue.book.clear();
		catalogue.asked.clear();
		// The fake is a context-scoped singleton, so its failure switch outlives
		// the test that flipped it — reset it here or every later method inherits
		// an unreachable catalogue.
		catalogue.fail = false;
		catalogue.resolution = MarketCatalogue.LeagueResolution.NONE;
		clock.now = NOW;
	}

	@Test
	void aFixtureInsideTheHorizonEntersScopeAndIsPlanned() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));

		List<ScopedMarket> open = service.refresh();

		assertThat(open).singleElement()
				.satisfies(m -> assertThat(m.state()).isEqualTo(ScopeState.PENDING));
		assertThat(service.plan().marketIds()).containsExactly("1.1");
	}

	@Test
	void subscribingIsRecordedOnlyForWhatWasSubscribed() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		catalogue.offer(market("1.2", "e1", KICKOFF, false, "OPEN"));
		service.refresh();

		service.subscribed(List.of("1.1"));

		assertThat(states()).containsEntry("1.1", "SUBSCRIBED").containsEntry("1.2", "PENDING");
	}

	/**
	 * A later poll must not walk a market backwards.
	 *
	 * <p>The upsert runs on every poll for every market it sees, so the conflict
	 * clause is the only thing standing between "refresh the kickoff time" and
	 * "reset a match in progress to PENDING", where it would then be eligible for
	 * the trim as though nothing were being recorded.
	 */
	@Test
	void aRepeatedSightingDoesNotResetTheState() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		service.refresh();

		assertThat(states()).containsEntry("1.1", "SUBSCRIBED");
	}

	@Test
	void theCatalogueReportingInPlayMakesASubscribedMarketLiveAndDatesTheGuard() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		catalogue.markets.clear();
		catalogue.offer(market("1.1", "e1", KICKOFF, true, "OPEN"));
		service.refresh();

		assertThat(states()).containsEntry("1.1", "LIVE");
		assertThat(inPlaySince("1.1")).isEqualTo(NOW);
	}

	/**
	 * Half of #219, and the half that cost the most.
	 *
	 * <p>This test used to assert the opposite — a market the recorder had never
	 * subscribed to reached LIVE the moment the catalogue turned it in-play, and
	 * that is the behaviour the card of 2026-09-08 recorded: 232 rows LIVE
	 * against a 200-market wire subscription, 31 of them with no message in the
	 * preceding five minutes of in-play. LIVE is not the inert marker its own
	 * javadoc claimed: the planner reads it as "being recorded" and gives it a
	 * tier above a fixture waiting to start, and the capture ledger reads it as
	 * coverage.
	 *
	 * <p>What the fixture actually did is still recorded, in the column that is a
	 * fact about the match rather than about the recorder — so the in-play guard
	 * keeps working for a market that never fitted under the cap.
	 */
	@Test
	void aMarketInPlayButNotOnTheWireIsNotRecordedAsLive() {
		catalogue.offer(market("1.1", "e1", KICKOFF, true, "OPEN"));

		service.refresh();

		assertThat(states()).containsEntry("1.1", "PENDING");
		assertThat(inPlaySince("1.1")).isEqualTo(NOW);
	}

	/**
	 * The other half of #219: the subscription is a replacement, so the ledger
	 * has to record the exits as well as the entries.
	 *
	 * <p>No existing test could see this, because every one of them planned once.
	 * On the real card the planner trimmed to 200, said so in the log, and the
	 * table held 205 rows SUBSCRIBED at the same instant — the five markets
	 * displaced to admit a target fixture were never moved back.
	 */
	@Test
	void aMarketDisplacedFromThePlanLeavesTheWire() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		catalogue.offer(market("1.2", "e2", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1", "1.2"));

		service.subscribed(List.of("1.1"));

		assertThat(states()).containsEntry("1.1", "SUBSCRIBED").containsEntry("1.2", "PENDING");
		// PENDING and not DONE: it lost a slot, it did not leave scope, and the
		// next plan may well take it back.
		assertThat(exitReason("1.2")).isNull();
	}

	/**
	 * And on the other side of kickoff, which is where it stopped being a
	 * miscount and started being a false claim of capture.
	 */
	@Test
	void aMarketDisplacedAfterKickoffLeavesTheWireToo() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		catalogue.offer(market("1.2", "e2", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1", "1.2"));

		catalogue.markets.clear();
		catalogue.books("1.2", true, "OPEN");
		clock.now = KICKOFF.plusSeconds(60);
		service.refresh();
		assertThat(states()).containsEntry("1.2", "LIVE");

		service.subscribed(List.of("1.1"));

		assertThat(states()).containsEntry("1.2", "PENDING");
		// The match is still being played, and the guard still measures from when
		// it started — this market simply is not the one on the wire.
		assertThat(inPlaySince("1.2")).isEqualTo(KICKOFF.plusSeconds(60));
	}

	/**
	 * The invariant the whole issue reduces to: what the table calls subscribed
	 * is what the subscription carries, market for market.
	 *
	 * <p>Asserted as a set rather than a count, because the counts agreed for
	 * every single-plan test that ever ran here and it was the membership that
	 * had drifted.
	 */
	@Test
	void whatTheTableCallsOnTheWireIsExactlyWhatWasSubscribed() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		catalogue.offer(market("1.2", "e2", KICKOFF, false, "OPEN"));
		catalogue.offer(market("1.3", "e3", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1", "1.2"));

		// A target fixture arrives and takes the slot, exactly as the tier policy
		// says it may.
		service.subscribed(List.of("1.1", "1.3"));

		assertThat(onTheWire()).containsExactlyInAnyOrder("1.1", "1.3");
	}

	/** The in-play clock is set once: it is what the guard measures from. */
	@Test
	void theInPlayClockDoesNotRestartOnEveryPoll() {
		catalogue.offer(market("1.1", "e1", KICKOFF, true, "OPEN"));
		service.refresh();

		clock.now = NOW.plus(Duration.ofMinutes(20));
		service.refresh();

		assertThat(inPlaySince("1.1")).isEqualTo(NOW);
	}

	@Test
	void aClosedMarketLeavesScopeWithTheReasonKept() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "CLOSED"));

		service.refresh();

		assertThat(states()).containsEntry("1.1", "DONE");
		assertThat(exitReason("1.1")).isEqualTo("CLOSED");
		assertThat(service.plan().marketIds()).isEmpty();
	}

	@Test
	void aSettledMarketLeavesScopeToo() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "SETTLED"));

		service.refresh();

		assertThat(exitReason("1.1")).isEqualTo("SETTLED");
	}

	/**
	 * The match that never closed.
	 *
	 * <p>130 minutes covers 90 plus a generous half-time and the stoppages after
	 * an injury. Past that, whatever the market is doing, it is not this fixture
	 * still being played.
	 */
	@Test
	void theInPlayGuardRetiresAMatchThatNeverClosed() {
		catalogue.offer(market("1.1", "e1", KICKOFF, true, "OPEN"));
		service.refresh();

		clock.now = NOW.plus(Duration.ofMinutes(131));
		service.refresh();

		assertThat(exitReason("1.1")).isEqualTo("IN_PLAY_ELAPSED");
	}

	/**
	 * The fixture that never started.
	 *
	 * <p>Postponed after its market was created, so it never goes in-play and
	 * never closes — the case that holds a subscription slot indefinitely and is
	 * invisible in every other ledger.
	 */
	@Test
	void theAbandonmentGuardRetiresAFixtureThatNeverKickedOff() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		clock.now = KICKOFF.plus(Duration.ofHours(6)).plusSeconds(1);
		service.refresh();

		assertThat(exitReason("1.1")).isEqualTo("KICKOFF_ELAPSED");
	}

	/**
	 * The bug of #127, in the only shape that reproduces it.
	 *
	 * <p>Discovery filters on a start-time window, so a market vanishes from it
	 * the moment it kicks off. Every market this recorder scoped in its first two
	 * nights — 76 of them — left by the six-hour abandonment guard for that
	 * reason, and LIVE was never once reached. The fake models the split
	 * faithfully: gone from the catalogue, still answered for by id.
	 */
	@Test
	void aMarketThatHasKickedOffIsFollowedByIdAndReachesLive() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		catalogue.markets.clear();
		catalogue.books("1.1", true, "OPEN");
		clock.now = KICKOFF.plusSeconds(60);
		service.refresh();

		assertThat(states()).containsEntry("1.1", "LIVE");
		assertThat(inPlaySince("1.1")).isEqualTo(KICKOFF.plusSeconds(60));
	}

	/**
	 * And so the in-play guard finally has something to act on.
	 *
	 * <p>{@code raptor.scope.in-play-timeout} was dead configuration before this
	 * — documented as the guard that retires a market shortly after it finishes,
	 * and never once fired, because nothing could reach LIVE to date it from.
	 */
	@Test
	void theInPlayGuardRetiresAFollowedMatchTwoHoursAfterKickoff() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		catalogue.markets.clear();
		catalogue.books("1.1", true, "OPEN");
		clock.now = KICKOFF;
		service.refresh();

		clock.now = KICKOFF.plus(Duration.ofMinutes(131));
		service.refresh();

		assertThat(exitReason("1.1")).isEqualTo("IN_PLAY_ELAPSED");
		// And not the abandonment guard, which is six hours away and would have
		// held the subscription slot for four more of them.
		assertThat(clock.now).isBefore(KICKOFF.plus(Duration.ofHours(6)));
	}

	/**
	 * The ordinary end, which had never happened.
	 *
	 * <p>A closed market has no start time inside <em>any</em> window, however far
	 * back it reaches, so widening discovery could not have recovered this half.
	 * Following by id is what makes CLOSED reachable at all.
	 */
	@Test
	void aMarketThatClosesAfterKickoffIsSeenToClose() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		catalogue.markets.clear();
		catalogue.books("1.1", true, "CLOSED");
		clock.now = KICKOFF.plus(Duration.ofMinutes(120));
		service.refresh();

		assertThat(states()).containsEntry("1.1", "DONE");
		assertThat(exitReason("1.1")).isEqualTo("CLOSED");
		assertThat(service.plan().marketIds()).isEmpty();
	}

	/** SETTLED arrives by the same route, and means the same thing. */
	@Test
	void aMarketThatSettlesAfterKickoffIsSeenToSettle() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();

		catalogue.markets.clear();
		catalogue.books("1.1", false, "SETTLED");
		service.refresh();

		assertThat(exitReason("1.1")).isEqualTo("SETTLED");
	}

	/**
	 * Discovery's answer is not asked about twice.
	 *
	 * <p>The follow-up is a second call to the upstream on every poll, and a
	 * Saturday's scope is hundreds of markets. Asking again about the ones the
	 * catalogue just answered for would double it for nothing.
	 */
	@Test
	void aMarketDiscoveryStillReturnsIsNotFollowedAgain() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));

		service.refresh();

		assertThat(catalogue.asked).isEmpty();
	}

	/**
	 * A pending market the catalogue stopped listing was withdrawn or moved.
	 *
	 * <p>Nothing is invested in it — it is not in any subscription — so saying so
	 * now beats waiting six hours for a guard to conclude the same thing.
	 */
	@Test
	void aPendingMarketThatVanishesFromTheCatalogueLeavesScope() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();

		catalogue.markets.clear();
		catalogue.offer(market("1.2", "e2", KICKOFF, false, "OPEN"));
		// And the upstream does not answer for it by id either, which is what
		// separates a withdrawn market from one that has merely kicked off.
		service.refresh();

		assertThat(exitReason("1.1")).isEqualTo("OUT_OF_HORIZON");
	}

	/**
	 * But not one the upstream still answers for.
	 *
	 * <p>A market drops out of discovery at kickoff, and Betfair does not turn it
	 * in-play at the same instant. In that gap a still-PENDING market looks
	 * exactly like a withdrawn one, and retiring it would drop a fixture minutes
	 * before it started producing the prices the whole corpus is for.
	 */
	@Test
	void aPendingMarketTheBookStillAnswersForIsNotWithdrawn() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();

		catalogue.markets.clear();
		catalogue.books("1.1", false, "OPEN");
		clock.now = KICKOFF.plusSeconds(30);
		service.refresh();

		assertThat(states()).containsEntry("1.1", "PENDING");
		assertThat(exitReason("1.1")).isNull();
	}

	/**
	 * But a market being recorded is never retired for being absent.
	 *
	 * <p>The catalogue not listing a market says nothing about a live
	 * subscription, and dropping a match in progress because a REST query came
	 * back short would be the worst trade in this system.
	 */
	@Test
	void aSubscribedMarketIsNotRetiredMerelyForVanishing() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		catalogue.markets.clear();
		service.refresh();

		assertThat(states()).containsEntry("1.1", "SUBSCRIBED");
	}

	/** A rescheduled fixture that comes back is a market to capture again. */
	/**
	 * A market in both queries stays a requested one.
	 *
	 * <p>Not hypothetical: the control set is a country filter, and a requested
	 * English league's markets are in GB too, so every night's poll returns them
	 * twice. Letting the second sighting overwrite the flag would hand the
	 * planner a Premier League fixture labelled "control" — and the planner's
	 * first rule is that control markets are trimmed before requested ones, so
	 * the leagues the programme exists to record would be the first dropped
	 * under the cap.
	 */
	@Test
	void aMarketInBothQueriesKeepsItsRequestedFlag() {
		CatalogueMarket market = market("1.1", "e1", KICKOFF, false, "OPEN");
		catalogue.offer(market);
		catalogue.offerAsControl(market);

		service.refresh();

		assertThat(requested("1.1")).isTrue();
	}

	@Test
	void aMarketThatReappearsComesBackIntoScope() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "CLOSED"));
		service.refresh();
		assertThat(states()).containsEntry("1.1", "DONE");

		catalogue.markets.clear();
		catalogue.offer(market("1.1", "e1", KICKOFF.plusSeconds(86_400), false, "OPEN"));
		service.refresh();

		assertThat(states()).containsEntry("1.1", "PENDING");
		assertThat(exitReason("1.1")).isNull();
	}

	/**
	 * A failed poll leaves scope exactly as it was.
	 *
	 * <p>The failure modes are not symmetric: a stale scope subscribes a market a
	 * little too long, while retiring scope on a failed poll drops a live match
	 * because a REST call timed out.
	 */
	@Test
	void aFailedPollChangesNothing() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.refresh();
		service.subscribed(List.of("1.1"));

		catalogue.fail = true;
		service.poll();

		assertThat(states()).containsEntry("1.1", "SUBSCRIBED");
	}

	/**
	 * The swallow above is deliberate; this is what stops it being silent.
	 *
	 * <p>#180: a catalogue nobody can reach leaves scope empty, and an empty scope
	 * is what a quiet Tuesday looks like too. The count is the only thing that
	 * separates them, so it is asserted through {@link MarketScopeService#poll()}
	 * — the method that catches — rather than through {@code refresh()}, which
	 * throws and would let a missing reset pass.
	 */
	@Test
	void failedPollsAreCountedAndACompletedOneClearsTheCount() {
		// The counter lives on a context-scoped singleton, so the precondition is
		// stated rather than assumed: a poll that completes is what zeroes it.
		service.poll();
		assertThat(service.discovery().consecutivePollFailures()).isZero();

		catalogue.fail = true;
		service.poll();
		service.poll();

		assertThat(service.discovery().consecutivePollFailures()).isEqualTo(2);

		catalogue.fail = false;
		service.poll();

		assertThat(service.discovery().consecutivePollFailures()).isZero();
	}

	/**
	 * League names that did not resolve reach health, not just the container log.
	 *
	 * <p>The catalogue is the only thing that knows them, and the worst case emits
	 * no query at all — an implementation that resolves nothing has no ids to ask
	 * about — so the fact travels on the port rather than in the poll's result.
	 */
	@Test
	void discoveryCarriesTheCataloguesUnresolvedLeagueNames() {
		catalogue.resolution =
				new MarketCatalogue.LeagueResolution(8, List.of("Scottish Premiership"));

		assertThat(service.discovery().configuredLeagues()).isEqualTo(8);
		assertThat(service.discovery().unresolvedLeagues())
				.containsExactly("Scottish Premiership");
	}

	/**
	 * The empty-scope clock measures what has been observed, and only that.
	 *
	 * <p>It is informational — a real international break is days of legitimately
	 * empty scope — so what matters is that it moves when a poll finds something
	 * and keeps running when one does not.
	 */
	@Test
	void theEmptyScopeClockRestartsOnlyWhenAPollFindsSomething() {
		// Same singleton, same discipline: the baseline is established here, by a
		// poll that finds something, rather than inherited from whatever ran first.
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.poll();

		assertThat(service.discovery().sinceScopeNonEmpty()).isZero();

		// Eight hours on, past the six-hour abandonment guard, so 1.1 leaves the
		// ledger and the poll genuinely finds nothing.
		catalogue.markets.clear();
		clock.now = NOW.plus(Duration.ofHours(8));
		service.poll();

		assertThat(service.discovery().sinceScopeNonEmpty()).isEqualTo(Duration.ofHours(8));
	}

	/**
	 * The card clock is a streak, and the distinction from the recency above is
	 * the whole of #278.
	 *
	 * <p>{@code sinceScopeNonEmpty} moves forward on every poll that finds
	 * something, so while a card is on it is never more than one poll old.
	 * Anything asking "how long has there been something to capture" needs the
	 * other number — and asking the recency would cap the answer at the poll
	 * interval, which is why the health indicator's dwell test could not simply
	 * be pointed at it.
	 */
	@Test
	void theCardClockMeasuresTheStreakWhereTheEmptyScopeClockMeasuresTheRecency() {
		catalogue.offer(market("1.1", "e1", KICKOFF, false, "OPEN"));
		service.poll();

		assertThat(service.discovery().scopeNonEmptyFor()).isZero();

		// Two hours in, with the same market still in scope. A poll that finds
		// what it found last time has not restarted the card.
		clock.now = NOW.plus(Duration.ofHours(2));
		service.poll();

		assertThat(service.discovery().scopeNonEmptyFor()).isEqualTo(Duration.ofHours(2));
		// And the recency says the opposite thing, correctly: a poll just found
		// something. Both are published; they answer different questions.
		assertThat(service.discovery().sinceScopeNonEmpty()).isZero();

		// Scope empties, so nothing is owed a reaction any more.
		catalogue.markets.clear();
		clock.now = NOW.plus(Duration.ofHours(8));
		service.poll();

		assertThat(service.discovery().scopeNonEmptyFor()).isZero();

		// And the next card starts its own clock rather than resuming the last.
		catalogue.offer(market("2.2", "e2", NOW.plus(Duration.ofHours(10)), false, "OPEN"));
		clock.now = NOW.plus(Duration.ofHours(9));
		service.poll();
		clock.now = NOW.plus(Duration.ofHours(9)).plus(Duration.ofMinutes(20));

		assertThat(service.discovery().scopeNonEmptyFor()).isEqualTo(Duration.ofMinutes(20));
	}

	/** Every market the ledger says is in the current subscription. */
	private List<String> onTheWire() {
		return jdbc.sql("""
						select market_id from raw.market_scope
						where state in ('SUBSCRIBED', 'LIVE')""")
				.query(String.class).list();
	}

	private java.util.Map<String, String> states() {
		java.util.Map<String, String> states = new java.util.LinkedHashMap<>();
		jdbc.sql("select market_id, state from raw.market_scope").query()
				.listOfRows()
				.forEach(row -> states.put((String) row.get("market_id"), (String) row.get("state")));
		return states;
	}

	private @org.jspecify.annotations.Nullable String exitReason(String marketId) {
		return jdbc.sql("select exit_reason from raw.market_scope where market_id = ?")
				.param(marketId).query(String.class).optional().orElse(null);
	}

	private boolean requested(String marketId) {
		return jdbc.sql("select requested from raw.market_scope where market_id = ?")
				.param(marketId).query(Boolean.class).single();
	}

	private Instant inPlaySince(String marketId) {
		return jdbc.sql("select in_play_since from raw.market_scope where market_id = ?")
				.param(marketId).query(java.time.OffsetDateTime.class).single().toInstant();
	}

	private static CatalogueMarket market(String marketId, String eventId, Instant kickoff,
			boolean inPlay, String status) {
		return new CatalogueMarket(marketId, eventId, "Home v Away", "10932509",
				"English Premier League", "MATCH_ODDS", "GB", kickoff, inPlay, status);
	}

	/** A catalogue a test can drive, including into failure. */
	static final class FakeCatalogue implements MarketCatalogue {

		final List<CatalogueMarket> markets = new ArrayList<>();
		boolean fail;
		LeagueResolution resolution = LeagueResolution.NONE;

		@Override
		public LeagueResolution resolution() {
			return this.resolution;
		}

		void offer(CatalogueMarket market) {
			markets.add(market);
		}

		final List<CatalogueMarket> control = new ArrayList<>();

		void offerAsControl(CatalogueMarket market) {
			control.add(market);
		}

		/**
		 * What the upstream will say about a market discovery no longer returns.
		 *
		 * <p>Separate from {@link #markets} on purpose: the bug this models is
		 * exactly that the two answers differ, so a fake where a market is in both
		 * or neither could not reproduce it.
		 */
		final List<MarketState> book = new ArrayList<>();

		void books(String marketId, boolean inPlay, String status) {
			book.add(new MarketState(marketId, inPlay, status));
		}

		@Override
		public List<CatalogueQuery> poll(Duration horizon) {
			if (fail) {
				throw new IllegalStateException("catalogue unreachable (simulated)");
			}
			return List.of(new CatalogueQuery(true, List.copyOf(markets)),
					new CatalogueQuery(false, List.copyOf(control)));
		}

		/**
		 * The lookahead's own answer, set by a test rather than derived from
		 * {@link #markets}.
		 *
		 * <p>Separate on purpose: the whole point of the figure is that it sees
		 * kickoffs discovery cannot, so a fake deriving it from what discovery
		 * returns could not reproduce the case it exists for.
		 */
		@Nullable Instant nextKickoff;

		@Override
		public @Nullable Instant nextKickoff(Duration lookahead) {
			if (fail) {
				throw new IllegalStateException("catalogue unreachable (simulated)");
			}
			return nextKickoff;
		}

		@Override
		public List<MarketState> follow(Collection<String> marketIds) {
			if (fail) {
				throw new IllegalStateException("catalogue unreachable (simulated)");
			}
			// Only what was asked about, so a test can assert that a market
			// discovery DID return is not followed a second time.
			asked.add(List.copyOf(marketIds));
			return book.stream().filter(s -> marketIds.contains(s.marketId())).toList();
		}

		final List<List<String>> asked = new ArrayList<>();
	}

	/** A clock a test can move a match through. */
	static final class MutableClock extends Clock {

		Instant now = NOW;

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Fakes {

		/**
		 * Primary because the real {@code BetfairCatalogue} is on the classpath
		 * too: scope resolves its port with {@code getIfAvailable()}, which fails
		 * on two candidates rather than picking one.
		 */
		@Bean
		@Primary
		FakeCatalogue fakeCatalogue() {
			return new FakeCatalogue();
		}

		@Bean
		MutableClock clock() {
			return new MutableClock();
		}
	}
}
