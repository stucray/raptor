package com.stucray.raptor.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.shape.WireShape;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The committed {@code basic-shape.json} is what the real historic corpus looks
 * like.
 *
 * <p>The other half of {@link SyntheticBasicShapeTest}: that test holds the
 * synthetic sample inside the manifest on every build, and this one holds the
 * manifest to the corpus. Without it the manifest would be one more thing written
 * from the same head as the generator.
 *
 * <p>Reads every file, in parallel, which takes a few minutes. Not part of
 * {@code verify}, because CI has no corpus. Run it with the replay tier:
 *
 * <pre>./mvnw -pl acquisition -Preplay verify -Dit.test=BasicShapeManifestIT</pre>
 *
 * <p>On a mismatch the shape it computed is written to
 * {@code target/basic-shape.json}. Review the diff, and copy it over the committed
 * file only if the corpus really did change: a month of BASIC files was added.
 */
@DisplayName("The committed BASIC shape is the real corpus's shape")
@EnabledIfSystemProperty(named = BasicShapeManifestIT.CORPUS, matches = ".+")
class BasicShapeManifestIT {

	static final String CORPUS = "raptor.replay.historic";

	@Test
	void theManifestMatchesTheCorpus() throws IOException {
		Path root = Path.of(System.getProperty(CORPUS));
		List<Path> files = BasicSampleFiles.under(root);
		assertThat(files).as("BASIC files under %s", root).isNotEmpty();

		String computed = files.parallelStream()
				.map(file -> WireShape.of(WireShape.Spec.BASIC, BasicSampleFiles.lines(file)))
				.reduce(WireShape.empty(WireShape.Spec.BASIC), (a, b) ->
						WireShape.empty(WireShape.Spec.BASIC).merge(a).merge(b))
				.toJson();
		String committed = Files.exists(SyntheticBasicShapeTest.MANIFEST)
				? Files.readString(SyntheticBasicShapeTest.MANIFEST, StandardCharsets.UTF_8)
				: "";

		if (!computed.equals(committed)) {
			Path out = Path.of("target/basic-shape.json");
			Files.writeString(out, computed, StandardCharsets.UTF_8);
			assertThat(computed)
					.as("the corpus's shape differs from %s; the computed shape is at %s",
							SyntheticBasicShapeTest.MANIFEST, out.toAbsolutePath())
					.isEqualTo(committed);
		}
	}
}
