package com.stucray.raptor.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The endpoint, not the indicator: what an operator or a monitor actually gets.
 *
 * <p>The unit test proves the verdict; this proves it reaches the wire. Between
 * the two sit the group's {@code include} list, the actuator exposure, and
 * Boot's mapping of OUT_OF_SERVICE to 503 — none of which any unit test can see
 * fail, and all of which have to hold for #131 to have changed anything. This
 * context has no stream source and no credentials, which is exactly the
 * condition #122 and #127 shipped in twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Capture coverage: a recorder that cannot capture, beside a horizon that needs it")
class CaptureCoverageEndpointTest {

	@Autowired MockMvc mvc;
	@Autowired @Acquisition JdbcClient jdbc;

	@BeforeEach
	void emptyTheHorizon() {
	}

	/** 04:00 on a Tuesday. Nothing to capture, so nothing to report. */
	@Test
	void upWithAnEmptyHorizon() throws Exception {
		mvc.perform(get("/actuator/health/capture"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components.captureCoverage.status").value("UP"))
				// The churn count reaches the wire, not just the Health object. An
				// indicator's details are dropped unless a group forces show-details,
				// and a unit test on the returned Health cannot see that (#171).
				.andExpect(jsonPath("$.components.captureCoverage.details.consecutiveFailedAttempts")
						.value(0));
	}

	/**
	 * 19:00 on a Saturday with the same recorder. The incident that stayed green
	 * twice: no rows written, no gap row, keep-awake quiet, and the only trace a
	 * capture session that never appeared.
	 */
	@Test
	void outOfServiceWhenMarketsAreInScopeAndNothingCanCaptureThem() throws Exception {
		inScope("1.987654321");

		mvc.perform(get("/actuator/health/capture"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"))
				.andExpect(jsonPath("$.components.captureCoverage.status").value("OUT_OF_SERVICE"))
				.andExpect(jsonPath("$.components.captureCoverage.details.marketsInScope").value(1))
				// The scope contributor stays UP beside it: counting is not judging,
				// and a horizon with fixtures in it is not itself a fault.
				.andExpect(jsonPath("$.components.scope.status").value("UP"));
	}

	private void inScope(String marketId) {
		jdbc.sql("""
						insert into raw.market_scope
							(market_id, event_id, event_name, market_type, kickoff, requested, state)
						values (:marketId, '29', 'Arsenal v Chelsea', 'MATCH_ODDS',
								now() + interval '2 hours', true, 'PENDING')""")
				.param("marketId", marketId)
				.update();
	}
}
