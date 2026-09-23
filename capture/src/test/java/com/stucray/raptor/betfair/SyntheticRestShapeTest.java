package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.shape.WireShape;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The synthetic REST responses say nothing the real API has never said (#304).
 *
 * <p>Each committed response in {@code betfair-rest/} must stay inside the shape
 * that {@link RestShapeManifestIT} read off the real response for the same
 * endpoint, held in custody. The values (ids, prices, times) are invented. The
 * shape is not, which is the rule PRD #95's invented node type broke.
 */
@DisplayName("The synthetic REST responses stay inside the real API's shape")
class SyntheticRestShapeTest {

	static final Path MANIFEST = Path.of("src/test/resources/rest-shape.json");
	static final Path SAMPLE = Path.of("src/test/resources/betfair-rest");

	@Test
	void everyResponseIsInsideItsEndpointsShape() throws IOException {
		Map<String, WireShape> wire = manifest();
		Map<String, WireShape> sample = RestShapes.of(SAMPLE);

		List<String> violations = new ArrayList<>();
		sample.forEach((endpoint, shape) -> shape.outside(wire.get(endpoint))
				.forEach(v -> violations.add(endpoint + ": " + v)));
		assertThat(violations).as("the sample invents shape").isEmpty();
		// A response of nothing is inside every shape.
		assertThat(sample.values()).allSatisfy(shape -> assertThat(shape.paths()).isNotEmpty());
	}

	/**
	 * The check can fail. The node a mapper written from intuition would expect,
	 * a catalogue carrying {@code marketType} at the top level where the real API
	 * only has {@code marketName}, must be reported, as must a status it never sent.
	 */
	@Test
	void anInventedKeyOrValueIsReported() throws IOException {
		WireShape invented = WireShape.empty(WireShape.Spec.REST);
		invented.add(JsonMapper.builder().build().readTree("""
				[{"marketId": "1.1", "marketType": "MATCH_ODDS", "status": "RESTING"}]"""));

		assertThat(invented.outside(manifest().get("list-market-catalogue")))
				.anySatisfy(v -> assertThat(v).contains("path never seen on the wire: [].marketType"))
				.anySatisfy(v -> assertThat(v).contains("key set never seen on the wire at []:"));
		assertThat(invented.outside(manifest().get("list-market-book")))
				.anySatisfy(v -> assertThat(v).contains("value never seen on the wire: [].status = RESTING"));
	}

	static Map<String, WireShape> manifest() throws IOException {
		return RestShapes.fromJson(Files.readString(MANIFEST, StandardCharsets.UTF_8));
	}
}
