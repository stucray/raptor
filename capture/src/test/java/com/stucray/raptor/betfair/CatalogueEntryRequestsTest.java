package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.betfair.CatalogueEntryRequests.Projection;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The by-id requests are sized so that Betfair can never refuse one for weight
 * (#42). On 2026-10-02 the live API refused these projections at 200 markets with
 * {@code TOO_MUCH_DATA} and answered at 100.
 */
@DisplayName("Catalogue entry requests stay inside Betfair's weight budget")
class CatalogueEntryRequestsTest {

	@Test
	@DisplayName("the entry's projections fit exactly 100 markets")
	void theEntryProjectionsFitAHundred() {
		assertThat(CatalogueEntryRequests.marketsPerRequest(CatalogueEntryRequests.PROJECTIONS))
				.isEqualTo(100);
		assertThat(CatalogueEntryRequests.names(CatalogueEntryRequests.PROJECTIONS))
				.containsExactly("EVENT", "COMPETITION", "MARKET_START_TIME", "MARKET_DESCRIPTION",
						"RUNNER_DESCRIPTION", "RUNNER_METADATA");
	}

	@Test
	@DisplayName("the weight is the sum of the weighted projections, and none means 1000")
	void theWeightIsTheSumOfTheProjections() {
		assertThat(CatalogueEntryRequests.marketsPerRequest(List.of(Projection.MARKET_DESCRIPTION)))
				.isEqualTo(200);
		assertThat(CatalogueEntryRequests.marketsPerRequest(
				List.of(Projection.EVENT, Projection.RUNNER_DESCRIPTION))).isEqualTo(1000);
	}

	@Test
	@DisplayName("100 ids are one request, and 101 are two")
	void theBoundaryIsExact() {
		assertThat(CatalogueEntryRequests.batches(ids(100), 100)).hasSize(1);
		assertThat(CatalogueEntryRequests.batches(ids(101), 100))
				.extracting(List::size).containsExactly(100, 1);
	}

	@Test
	@DisplayName("every id is asked for exactly once, in order, and none means no request")
	void everyIdOnceInOrder() {
		List<String> ids = ids(250);

		assertThat(CatalogueEntryRequests.batches(ids, 100).stream().flatMap(List::stream))
				.containsExactlyElementsOf(ids);
		assertThat(CatalogueEntryRequests.batches(List.of(), 100)).isEmpty();
	}

	private static List<String> ids(int count) {
		return IntStream.range(0, count).mapToObj(i -> "1." + (900_000_000 + i)).toList();
	}
}
