package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The committed {@code rest-shape.json} is what Betfair's REST API really sent.
 *
 * <p>The other half of {@link SyntheticRestShapeTest}. The real responses were
 * captured by {@code scripts/capture-betfair-rest.py} and live in custody,
 * {@code $RAPTOR_DATA_ROOT/betfair-rest/captured/}, because they may not be
 * committed (#304). Not part of {@code verify}, because CI has no custody. Run it
 * with the replay tier:
 *
 * <pre>./mvnw -pl acquisition -Preplay verify -Dit.test=RestShapeManifestIT</pre>
 *
 * <p>On a mismatch the shape it computed is written to
 * {@code target/rest-shape.json}. That happens by design when a new capture is
 * added to custody, for example the in-play book the fixtures still lack. Review
 * the diff and copy it over the committed file.
 */
@DisplayName("The committed REST shape is the captured responses' shape")
@EnabledIfSystemProperty(named = RestShapeManifestIT.CAPTURES, matches = ".+")
class RestShapeManifestIT {

	static final String CAPTURES = "raptor.replay.rest";

	@Test
	void theManifestMatchesTheCaptures() throws IOException {
		Path dir = Path.of(System.getProperty(CAPTURES));
		assertThat(dir).as("REST captures").isDirectory();

		String computed = RestShapes.toJson(RestShapes.of(dir));
		String committed = Files.exists(SyntheticRestShapeTest.MANIFEST)
				? Files.readString(SyntheticRestShapeTest.MANIFEST, StandardCharsets.UTF_8)
				: "";

		if (!computed.equals(committed)) {
			Path out = Path.of("target/rest-shape.json");
			Files.writeString(out, computed, StandardCharsets.UTF_8);
			assertThat(computed)
					.as("the captures' shape differs from %s; the computed shape is at %s",
							SyntheticRestShapeTest.MANIFEST, out.toAbsolutePath())
					.isEqualTo(committed);
		}
	}
}
