package com.stucray.raptor.scope;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@link ScopeCensus} over {@code raw.market_scope}, the live ledger.
 *
 * <p>The live one and not the projection the health screen browses: a count
 * that trails by up to a projection interval is the right answer for a
 * morning's browsing and the wrong one for a verdict computed at kickoff.
 */
@Component
class MarketScopeCensus implements ScopeCensus {

	private final MarketScopes scopes;

	MarketScopeCensus(MarketScopes scopes) {
		this.scopes = scopes;
	}

	@Override
	public ScopeSummary summarise() {
		List<ScopedMarket> open = scopes.open();
		Map<ScopeState, Integer> counts = new EnumMap<>(ScopeState.class);
		@Nullable Instant earliest = null;
		for (ScopedMarket market : open) {
			counts.merge(market.state(), 1, Integer::sum);
			Instant kickoff = market.kickoff();
			if (kickoff != null && (earliest == null || kickoff.isBefore(earliest))) {
				earliest = kickoff;
			}
		}
		return new ScopeSummary(open.size(), counts.getOrDefault(ScopeState.PENDING, 0),
				counts.getOrDefault(ScopeState.SUBSCRIBED, 0),
				counts.getOrDefault(ScopeState.LIVE, 0), earliest);
	}
}
