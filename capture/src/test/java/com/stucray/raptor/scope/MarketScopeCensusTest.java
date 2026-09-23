package com.stucray.raptor.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Counting the ledger: what a verdict about a silent recorder rests on. */
class MarketScopeCensusTest {

	private static final Instant NOW = Instant.parse("2026-09-05T18:00:00Z");

	private final MarketScopes scopes = mock(MarketScopes.class);
	private final MarketScopeCensus census = new MarketScopeCensus(scopes);

	@Test
	void countsByStateAndFindsTheEarliestKickoff() {
		when(scopes.open()).thenReturn(List.of(
				market("1.1", ScopeState.PENDING, NOW.plusSeconds(7200)),
				market("1.2", ScopeState.PENDING, NOW.plusSeconds(3600)),
				market("1.3", ScopeState.SUBSCRIBED, NOW.plusSeconds(1800)),
				market("1.4", ScopeState.LIVE, NOW.minusSeconds(2700))));

		ScopeSummary summary = census.summarise();

		assertThat(summary.marketsInScope()).isEqualTo(4);
		assertThat(summary.pending()).isEqualTo(2);
		assertThat(summary.subscribed()).isEqualTo(1);
		assertThat(summary.live()).isEqualTo(1);
		// Earliest, not first: the rows arrive ordered by kickoff today, and a
		// summary that depended on that would be wrong the day the query changes.
		assertThat(summary.nextKickoff()).isEqualTo(NOW.minusSeconds(2700));
		assertThat(summary.anythingToCapture()).isTrue();
	}

	/** Nothing in scope: the state that makes a switched-off recorder correct. */
	@Test
	void anEmptyLedgerHasNothingToCapture() {
		when(scopes.open()).thenReturn(List.of());

		ScopeSummary summary = census.summarise();

		assertThat(summary.marketsInScope()).isZero();
		assertThat(summary.nextKickoff()).isNull();
		assertThat(summary.anythingToCapture()).isFalse();
	}

	/** A market whose kickoff the catalogue never gave still counts. */
	@Test
	void countsAMarketWithNoKickoff() {
		when(scopes.open()).thenReturn(List.of(market("1.1", ScopeState.PENDING, null)));

		ScopeSummary summary = census.summarise();

		assertThat(summary.marketsInScope()).isEqualTo(1);
		assertThat(summary.nextKickoff()).isNull();
	}

	private static ScopedMarket market(String marketId, ScopeState state,
			@Nullable Instant kickoff) {
		return new ScopedMarket(marketId, "29", kickoff, state, null, true);
	}
}
