package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.shape.WireShape;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The committed {@code capture-shape.json} is what the real capture corpus
 * actually looks like.
 *
 * <p>The other half of {@link SyntheticCaptureShapeTest}: that test holds the
 * synthetic sample inside the manifest on every build, and this one holds the
 * manifest to the corpus. Without it the manifest would be one more thing written
 * from the same head as the generator.
 *
 * <p>Not part of {@code verify}, because CI has no corpus. Run it with the replay
 * tier:
 *
 * <pre>./mvnw -pl acquisition -Preplay verify -Dit.test=CaptureShapeManifestIT</pre>
 *
 * <p>On a mismatch the shape it computed is written to
 * {@code target/capture-shape.json}; review the diff and copy it over the
 * committed file only if the corpus really did change. The capture corpus has been
 * frozen since the Python recorder was retired (#124), so it should not.
 */
@DisplayName("The committed capture shape is the real corpus's shape")
@EnabledIfSystemProperty(named = FullCorpusReplayIT.CORPUS, matches = ".+")
class CaptureShapeManifestIT {

	@Test
	void theManifestMatchesTheCorpus() throws IOException {
		Path root = Path.of(System.getProperty(FullCorpusReplayIT.CORPUS));
		List<Path> captures;
		try (Stream<Path> files = Files.list(root)) {
			captures = files.filter(p -> p.getFileName().toString().endsWith(".ndjson.gz"))
					.sorted().toList();
		}
		assertThat(captures).as("captures under %s", root).isNotEmpty();

		WireShape shape = WireShape.empty(WireShape.Spec.CAPTURE);
		for (Path capture : captures) {
			shape.merge(WireShape.of(WireShape.Spec.CAPTURE, CaptureSampleFiles.lines(capture)));
		}
		String computed = shape.toJson();
		String committed = Files.readString(SyntheticCaptureShapeTest.MANIFEST, StandardCharsets.UTF_8);

		if (!computed.equals(committed)) {
			Path out = Path.of("target/capture-shape.json");
			Files.writeString(out, computed, StandardCharsets.UTF_8);
			assertThat(computed)
					.as("the corpus's shape differs from %s; the computed shape is at %s",
							SyntheticCaptureShapeTest.MANIFEST, out.toAbsolutePath())
					.isEqualTo(committed);
		}
	}
}
