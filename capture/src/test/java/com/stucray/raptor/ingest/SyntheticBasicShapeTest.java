package com.stucray.raptor.ingest;

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
 * The synthetic historic sample says nothing the real BASIC corpus has never
 * said (#304).
 *
 * <p>Every key path, JSON type, co-occurring key set and enumerated value in the
 * sample must appear in {@code basic-shape.json}, which
 * {@link BasicShapeManifestIT} reads off the real corpus. The values themselves
 * (ids, names, prices, times) are invented, and nothing here is about them.
 */
@DisplayName("The synthetic historic sample stays inside the real corpus's shape")
class SyntheticBasicShapeTest {

	static final Path MANIFEST = Path.of("src/test/resources/basic-shape.json");

	@Test
	void theSampleIsInsideTheShapeTheCorpusHas() throws IOException {
		List<String> lines = new ArrayList<>();
		for (Path file : BasicSampleFiles.all()) {
			lines.addAll(BasicSampleFiles.lines(file));
		}
		WireShape sample = WireShape.of(WireShape.Spec.BASIC, lines);

		assertThat(sample.outside(manifest())).as("the sample invents shape").isEmpty();
		// A sample of nothing is inside every shape.
		assertThat(sample.paths()).isNotEmpty();
	}

	/**
	 * The check can fail. An invented key, a key set the corpus never sent, and a
	 * real key carrying a value it never sent must each be reported.
	 */
	@Test
	void anInventedKeyOrValueIsReported() throws IOException {
		WireShape invented = WireShape.empty(WireShape.Spec.BASIC);
		invented.add(JsonMapper.builder().build().readTree("""
				{"op": "mcm", "clk": "1", "pt": 1, "mc": [{"id": "1.1", "competition": "X",
				 "marketDefinition": {"status": "RESTING"}}]}"""));

		assertThat(invented.outside(manifest()))
				.anySatisfy(v -> assertThat(v).contains("path never seen on the wire: mc[].competition"))
				.anySatisfy(v -> assertThat(v).contains("key set never seen on the wire at mc[]:"))
				.anySatisfy(v -> assertThat(v)
						.contains("value never seen on the wire: mc[].marketDefinition.status = RESTING"));
	}

	static WireShape manifest() throws IOException {
		return WireShape.fromJson(WireShape.Spec.BASIC, Files.readString(MANIFEST, StandardCharsets.UTF_8));
	}
}
