package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.shape.WireShape;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The synthetic capture sample says nothing the real wire has never said (#303).
 *
 * <p>Every key path, JSON type, co-occurring key set and enumerated value in the
 * sample must appear in {@code capture-shape.json}, which
 * {@link CaptureShapeManifestIT} reads off the real corpus. The values themselves
 * (ids, prices, sizes, times) are invented, and nothing here is about them: the
 * tests that use the sample compare two readings of the same bytes, so the values
 * only have to be coherent. What must not be invented is the shape, which is the
 * failure PRD #95 shipped.
 */
@DisplayName("The synthetic capture sample stays inside the real wire's shape")
class SyntheticCaptureShapeTest {

	static final Path MANIFEST = Path.of("src/test/resources/capture-shape.json");

	@Test
	void theSampleIsInsideTheShapeTheCorpusHas() throws IOException {
		WireShape wire = manifest();
		List<String> lines = new ArrayList<>();
		for (Path file : CaptureSampleFiles.all()) {
			lines.addAll(CaptureSampleFiles.lines(file));
		}
		WireShape sample = WireShape.of(WireShape.Spec.CAPTURE, lines);

		assertThat(sample.outside(wire)).as("the sample invents shape").isEmpty();
		// A sample of nothing is inside every shape.
		assertThat(sample.paths()).isNotEmpty();
	}

	/**
	 * The check can fail. An invented key, and a real key carrying a value the wire
	 * never sent, must each be reported: a containment test that passes everything
	 * would pass the sample too.
	 */
	@Test
	void anInventedKeyOrValueIsReported() throws IOException {
		WireShape wire = manifest();
		WireShape invented = WireShape.empty(WireShape.Spec.CAPTURE);
		invented.add(JsonMapper.builder().build().readTree("""
				{"pt": 1, "recv_ms": 2, "mc": {"id": "1.1", "competition": "X",
				 "marketDefinition": {"status": "RESTING"}}}"""));

		assertThat(invented.outside(wire))
				.anySatisfy(v -> assertThat(v).contains("path never seen on the wire: mc.competition"))
				.anySatisfy(v -> assertThat(v).contains("key set never seen on the wire at mc:"))
				.anySatisfy(v -> assertThat(v)
						.contains("value never seen on the wire: mc.marketDefinition.status = RESTING"));
	}

	private static WireShape manifest() throws IOException {
		return WireShape.fromJson(WireShape.Spec.CAPTURE,
				Files.readString(MANIFEST, StandardCharsets.UTF_8));
	}
}
