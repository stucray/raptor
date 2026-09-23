package com.stucray.raptor.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stucray.raptor.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who is in the capture group, asserted as a SET rather than one member at a
 * time.
 *
 * <p>Nothing enumerated this list before slice 10 (#255). Each contributor had
 * its own endpoint test asserting that it was present, which is a different
 * claim — a test of that shape cannot see an <em>extra</em> member, and the
 * extra member is the hazard here. This group's verdict drives an automatic
 * backend restart, so a contributor that joins it by accident acquires the
 * power to restart the recorder, and one that can go down for a reason a
 * restart cannot fix acquires the power to do it in a loop.
 *
 * <p>That is exactly why {@code analysis} left. A stale derivation is
 * rebuildable and a lost capture is not, and restarting a JVM does not derive a
 * feature. It reports on overround-analysis's own
 * {@code /actuator/health/derivation} now; #255 took it out of this group and
 * #256 deleted the indicator itself, so there is no longer anything in this
 * application that could contribute it. The absence assertion below is what
 * would fail if analysis were ever reintroduced here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("The capture group holds capture's contributors, and only those")
class CaptureHealthGroupTest {

	@Autowired MockMvc mvc;

	@Test
	@DisplayName("every capture contributor is on the wire, and analysis is not")
	void theGroupIsExactlyCaptureSContributors() throws Exception {
		mvc.perform(get("/actuator/health/capture"))
				.andExpect(status().isOk())
				// The five that remain. Present rather than merely configured: the
				// `include` list names a contributor that may not exist, and Boot
				// does not complain — it simply omits it, which reads identically
				// to a healthy component nobody asked about.
				.andExpect(jsonPath("$.components.recorder").exists())
				.andExpect(jsonPath("$.components.scope").exists())
				.andExpect(jsonPath("$.components.spill").exists())
				.andExpect(jsonPath("$.components.captureCoverage").exists())
				.andExpect(jsonPath("$.components.partitionRunway").exists())
				// THE CUTOVER ASSERTION (#255). The heartbeat reads this payload
				// every five minutes and had a STALE-ANALYSIS probe keyed on
				// `.components.analysis.details.stale`; that probe is gone, and
				// this is what keeps the thing it read from coming back.
				.andExpect(jsonPath("$.components.analysis").doesNotExist())
				// And the count, so that a SIXTH contributor cannot arrive
				// unnoticed. A new member is a deliberate act — the list names its
				// members precisely so that adding one is a decision someone makes
				// rather than a consequence of declaring a bean.
				.andExpect(jsonPath("$.components.length()").value(5));
	}
}
