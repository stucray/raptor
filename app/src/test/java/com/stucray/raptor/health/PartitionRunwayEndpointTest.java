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
 * The partition runway on the wire (#267).
 *
 * <p>The endpoint, not the indicator. A {@code HealthIndicator}'s details are
 * computed and thrown away unless a group forces {@code show-details}, so a unit
 * test asserting on the returned {@code Health} object passes while the payload
 * reaches nobody — and this detail exists for exactly one reader, the deployed
 * heartbeat. Between the indicator and that reader sit the group's {@code
 * include} list and the actuator exposure, and this list is <b>explicit</b>: a
 * new contributor does not join a group that names its members. Nothing but an
 * endpoint test can see that omission.
 *
 * <p>The other half is that it must never turn the group over. That verdict
 * drives an automatic backend restart after three unhealthy checks, and
 * restarting a JVM does not create a partition — so a runway warning able to go
 * OUT_OF_SERVICE would answer a schema deadline with a SIGTERM against a live
 * recorder, months before anything was actually wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("The partition runway reaches the capture endpoint without ever turning it red")
class PartitionRunwayEndpointTest {

	@Autowired MockMvc mvc;

	@Test
	@DisplayName("it reports the runway, and the group stays UP")
	void runwayReachesTheEndpoint() throws Exception {
		mvc.perform(get("/actuator/health/capture"))
				.andExpect(status().isOk())
				// THE ASSERTION THAT MATTERS MOST: a deadline known months ahead
				// must not restart the recorder.
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.components.partitionRunway.status").value("UP"))
				// Present at all, which is what the `include` list decides.
				.andExpect(jsonPath("$.components.partitionRunway.details.daysRemaining").exists())
				.andExpect(jsonPath("$.components.partitionRunway.details.lastCovered").exists())
				.andExpect(jsonPath("$.components.partitionRunway.details.low").exists())
				// Counted from the migration's own generated set, so a number
				// rather than a hardcoded expectation: the point is that it read
				// real partition bounds, not that the fixture has a known size.
				.andExpect(jsonPath("$.components.partitionRunway.details.partitions")
						.isNumber())
				// There IS one since V28, and this assertion flipped with it. The
				// flag was written when there was none, saying that adding one
				// would change what running out means — writes landing somewhere
				// findable instead of failing. That is precisely what #267 did, so
				// the deadline is now a cleanup job, and the question worth asking
				// moved on.
				.andExpect(jsonPath("$.components.partitionRunway.details.hasDefaultPartition")
						.value(true))
				// The one that matters now: the net exists, and nothing has hit it.
				// A row in the default means the extender stopped or a pt arrived
				// that nothing anticipated — and it blocks creating the partition
				// that range belongs to, so it is a fault report and not a detail.
				.andExpect(jsonPath("$.components.partitionRunway.details.defaultPartitionUsed")
						.value(false));
	}
}
