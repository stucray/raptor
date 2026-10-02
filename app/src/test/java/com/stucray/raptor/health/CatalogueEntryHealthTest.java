package com.stucray.raptor.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #44: the capture group says how many markets in scope have no catalogue entry
 * kept, read over HTTP rather than off the indicator, because a detail the
 * endpoint does not carry is a detail nobody sees.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("The capture group counts in-scope markets with no catalogue entry")
class CatalogueEntryHealthTest {

	private static final String COUNT = "$.components.scope.details.marketsWithoutCatalogueEntry";

	@Autowired MockMvc mvc;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	@DisplayName("a market in scope with no entry counts, and one with an entry does not")
	void countsOnlyMarketsInScopeWithoutAnEntry() throws Exception {
		scoped("1.1", "PENDING");
		scoped("1.2", "LIVE");
		kept("1.2");

		// The group itself may be DOWN here: markets in scope with no recorder is
		// captureCoverage's verdict to give, and not what this test is about.
		mvc.perform(get("/actuator/health/capture"))
				.andExpect(jsonPath(COUNT).value(1))
				.andExpect(jsonPath("$.components.scope.status").value("UP"));
	}

	@Test
	@DisplayName("a market that has left scope stops counting, entry or not")
	void aMarketOutOfScopeIsNotCounted() throws Exception {
		scoped("1.1", "DONE");

		mvc.perform(get("/actuator/health/capture"))
				.andExpect(jsonPath(COUNT).value(0));
	}

	private void scoped(String marketId, String state) {
		jdbc.sql("""
						insert into raw.market_scope
							(market_id, event_id, event_name, market_type, kickoff, requested, state,
							 exit_reason)
						values (:marketId, '29', 'Home v Away', 'MATCH_ODDS',
								now() + interval '2 hours', true, :state,
								case when :state = 'DONE' then 'CLOSED' end)""")
				.param("marketId", marketId)
				.param("state", state)
				.update();
	}

	private void kept(String marketId) {
		jdbc.sql("""
						insert into raw.market_catalogue (market_id, fetched_at, entry)
						values (:marketId, now(), cast(:entry as jsonb))""")
				.param("marketId", marketId)
				.param("entry", "{\"marketId\": \"" + marketId + "\"}")
				.update();
	}
}
