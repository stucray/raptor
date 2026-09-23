package com.stucray.raptor.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The half of the capture group that says whether the other half is worrying.
 *
 * <p>A recorder that is not recording is correct with an empty horizon and an
 * incident with markets in it, and the recorder's own state cannot tell the two
 * apart (#144). These counts are what an operator reads beside it.
 */
class ScopeHealthIndicatorTest {

	private static final Instant NOW = Instant.parse("2026-09-05T03:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final ScopeCensus census = mock(ScopeCensus.class);
	private final FakeLookahead catalogue = new FakeLookahead();
	private final KickoffLookahead lookahead =
			new KickoffLookahead(provider(catalogue), scopeProperties(), CLOCK);
	private final ScopeHealthIndicator indicator =
			new ScopeHealthIndicator(census, lookahead, CLOCK);

	/** 03:00 on a Tuesday: nothing to record, and nothing wrong. */
	@Test
	void anEmptyHorizonIsUpAndSaysSo() {
		when(census.summarise()).thenReturn(new ScopeSummary(0, 0, 0, 0, null));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("marketsInScope", 0)
				.containsEntry("live", 0)
				.doesNotContainKey("nextKickoff");
	}

	@Test
	void countsWhatIsInScopeAndDatesTheNextKickoff() {
		when(census.summarise())
				.thenReturn(new ScopeSummary(4, 2, 1, 1, NOW.minusSeconds(1800)));

		Health health = indicator.health();

		assertThat(health.getDetails()).containsEntry("marketsInScope", 4)
				.containsEntry("pending", 2)
				.containsEntry("subscribed", 1)
				.containsEntry("live", 1)
				// The earliest kickoff still in scope, which here is a market already
				// in-play: a kickoff in the past is a fixture under way, and reads as
				// a negative countdown rather than as no answer.
				.containsEntry("nextKickoff", NOW.minusSeconds(1800).toString())
				.containsEntry("secondsToNextKickoff", -1800L);
	}

	/** A market with no kickoff is countable and not datable; neither trips. */
	@Test
	void tolerantOfAMarketWithNoKickoff() {
		when(census.summarise()).thenReturn(new ScopeSummary(1, 1, 0, 0, null));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("marketsInScope", 1)
				.doesNotContainKey("nextKickoff");
	}

	/**
	 * Forward visibility, which is the whole of #265.
	 *
	 * <p>Scope opens a horizon before kickoff, so the figure an operator needs
	 * before shutting a laptop lid is not the kickoff but the subtraction — and
	 * the horizon belongs to this application, not to the shell script asking.
	 */
	@Test
	void publishesHowLongThereIsBeforeScopeOpens() {
		when(census.summarise()).thenReturn(new ScopeSummary(0, 0, 0, 0, null));
		// Nine hours out, against a four-hour horizon: five hours of freedom.
		catalogue.nextKickoff = NOW.plusSeconds(9 * 3600);
		lookahead.poll();

		Health health = indicator.health();

		assertThat(lookaheadDetails(health))
				.containsEntry("known", true)
				.containsEntry("nextKickoff", NOW.plusSeconds(9 * 3600).toString())
				.containsEntry("secondsToNextKickoff", 9L * 3600)
				.containsEntry("secondsUntilScopeOpens", 5L * 3600)
				.containsEntry("consecutiveFailures", 0);
	}

	/**
	 * A kickoff already inside the horizon reads as zero, not as a negative.
	 *
	 * <p>Scope is open or opens on the next discovery poll; "minus twenty
	 * minutes" is not an answer to "how long have I got".
	 */
	@Test
	void clampsTheWindowAtZeroOnceTheHorizonIsReached() {
		when(census.summarise()).thenReturn(new ScopeSummary(0, 0, 0, 0, null));
		catalogue.nextKickoff = NOW.plusSeconds(3600);
		lookahead.poll();

		assertThat(lookaheadDetails(indicator.health()))
				.containsEntry("secondsUntilScopeOpens", 0L);
	}

	/**
	 * "Nothing is on" and "nobody could ask" are different answers, and the
	 * second must never be published as the first.
	 *
	 * <p>This is the #122 / #141 / #144 shape pointed at an operator: a reader
	 * that took a missing {@code nextKickoff} for a clear week would shut the lid
	 * on a live card the first time Betfair was unreachable. {@code known} is what
	 * separates them, and a poll that has never run leaves it false.
	 */
	@Test
	void saysItDoesNotKnowRatherThanSayingTheWeekIsClear() {
		when(census.summarise()).thenReturn(new ScopeSummary(0, 0, 0, 0, null));

		assertThat(lookaheadDetails(indicator.health()))
				.containsEntry("known", false)
				.doesNotContainKey("nextKickoff")
				.doesNotContainKey("secondsUntilScopeOpens");
	}

	/** A genuinely empty window says so, and says when it was measured. */
	@Test
	void anEmptyLookaheadWindowIsKnownAndKickoffless() {
		when(census.summarise()).thenReturn(new ScopeSummary(0, 0, 0, 0, null));
		catalogue.nextKickoff = null;
		lookahead.poll();

		assertThat(lookaheadDetails(indicator.health()))
				.containsEntry("known", true)
				.containsEntry("measuredAt", NOW.toString())
				.doesNotContainKey("nextKickoff");
	}

	/**
	 * A failed poll keeps the last good answer and counts the failure.
	 *
	 * <p>Resetting to "no kickoff found" would turn an unreachable Betfair into
	 * an all-clear, which is the one direction this must never fail in.
	 */
	@Test
	void aFailedPollKeepsTheLastAnswerAndSaysItIsFailing() {
		when(census.summarise()).thenReturn(new ScopeSummary(0, 0, 0, 0, null));
		catalogue.nextKickoff = NOW.plusSeconds(9 * 3600);
		lookahead.poll();

		catalogue.fail = true;
		lookahead.poll();

		assertThat(lookaheadDetails(indicator.health()))
				.containsEntry("known", true)
				.containsEntry("nextKickoff", NOW.plusSeconds(9 * 3600).toString())
				.containsEntry("consecutiveFailures", 1);
	}

	@SuppressWarnings("unchecked")
	private static java.util.Map<String, Object> lookaheadDetails(Health health) {
		return (java.util.Map<String, Object>) health.getDetails().get("lookahead");
	}

	private static ScopeProperties scopeProperties() {
		return new ScopeProperties(java.time.Duration.ofHours(4), java.time.Duration.ofMinutes(15),
				java.time.Duration.ofSeconds(10), java.time.Duration.ofMinutes(130),
				java.time.Duration.ofHours(6), java.time.Duration.ofHours(48),
				java.time.Duration.ofMinutes(30), true, 3);
	}

	/** A catalogue that answers only the lookahead question. */
	private static final class FakeLookahead implements MarketCatalogue {

		private @org.jspecify.annotations.Nullable Instant nextKickoff;
		private boolean fail;

		@Override
		public @org.jspecify.annotations.Nullable Instant nextKickoff(
				java.time.Duration lookahead) {
			if (fail) {
				throw new IllegalStateException("catalogue unreachable (simulated)");
			}
			return nextKickoff;
		}

		@Override
		public java.util.List<CatalogueQuery> poll(java.time.Duration horizon) {
			return java.util.List.of();
		}

		@Override
		public java.util.List<MarketState> follow(java.util.Collection<String> marketIds) {
			return java.util.List.of();
		}

		@Override
		public LeagueResolution resolution() {
			return LeagueResolution.NONE;
		}
	}

	private static org.springframework.beans.factory.ObjectProvider<MarketCatalogue> provider(
			MarketCatalogue catalogue) {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override
			public MarketCatalogue getObject() {
				return catalogue;
			}

			@Override
			public MarketCatalogue getObject(Object... args) {
				return catalogue;
			}

			@Override
			public MarketCatalogue getIfAvailable() {
				return catalogue;
			}

			@Override
			public MarketCatalogue getIfUnique() {
				return catalogue;
			}

			@Override
			public java.util.Iterator<MarketCatalogue> iterator() {
				return java.util.List.of(catalogue).iterator();
			}
		};
	}
}
