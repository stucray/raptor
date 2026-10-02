package com.stucray.raptor.betfair;

import java.util.ArrayList;
import java.util.List;

/**
 * The by-id catalogue requests that fetch each market's entry (#42), sized so that
 * Betfair can never refuse one for weight.
 *
 * <p>Betfair charges every {@code listMarketCatalogue} request: the summed weight of
 * its projections, times the markets it returns, may not exceed 200, or the WHOLE
 * request is refused with {@code TOO_MUCH_DATA}. {@code MARKET_DESCRIPTION} and
 * {@code RUNNER_METADATA} weigh 1 each and the others nothing, so the projections
 * here fit 100 markets. Measured against the live API on 2026-10-02: with these
 * projections, 100 markets were answered and 200 were refused. A request with no
 * weighted projection at all is capped at 1000.
 */
final class CatalogueEntryRequests {

	/** A projection, with the weight Betfair charges for it. */
	enum Projection {
		EVENT(0),
		COMPETITION(0),
		MARKET_START_TIME(0),
		MARKET_DESCRIPTION(1),
		RUNNER_DESCRIPTION(0),
		RUNNER_METADATA(1);

		final int weight;

		Projection(int weight) {
			this.weight = weight;
		}
	}

	/** Everything Betfair says about a market's definition and its runners. */
	static final List<Projection> PROJECTIONS = List.of(Projection.values());

	private static final int BUDGET = 200;
	private static final int UNWEIGHTED_CAP = 1000;

	private CatalogueEntryRequests() {}

	/** The most markets one request with these projections may return. */
	static int marketsPerRequest(List<Projection> projections) {
		int weight = projections.stream().mapToInt(p -> p.weight).sum();
		return weight == 0 ? UNWEIGHTED_CAP : BUDGET / weight;
	}

	/** The ids in order, cut into requests of at most {@code perRequest}. */
	static List<List<String>> batches(List<String> marketIds, int perRequest) {
		List<List<String>> batches = new ArrayList<>();
		for (int from = 0; from < marketIds.size(); from += perRequest) {
			batches.add(List.copyOf(
					marketIds.subList(from, Math.min(from + perRequest, marketIds.size()))));
		}
		return batches;
	}

	static List<String> names(List<Projection> projections) {
		return projections.stream().map(Enum::name).toList();
	}
}
