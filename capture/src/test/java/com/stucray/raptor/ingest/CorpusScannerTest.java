package com.stucray.raptor.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CorpusScannerTest {

	private final CorpusScanner scanner = new CorpusScanner(Path.of("/corpus"));

	@Test
	void derivesMonthFromTheVendorDirectoryLayout() {
		assertThat(scanner.monthOf(Path.of("/corpus/2020/Mar/20/99920301/1.900003011.bz2")))
				.isEqualTo("2020-03");
		assertThat(scanner.monthOf(Path.of("/corpus/2019/Oct/1/1/1.1.bz2"))).isEqualTo("2019-10");
		assertThat(scanner.monthOf(Path.of("/corpus/2026/Dec/31/1/1.1.bz2"))).isEqualTo("2026-12");
	}

	/**
	 * "Mar" must not match at the "Mar" inside "Mar"/"May" overlaps, and an
	 * unknown directory must fail loudly rather than silently pick a month.
	 */
	@Test
	void rejectsAnUnrecognisedMonthDirectory() {
		assertThatThrownBy(() -> scanner.monthOf(Path.of("/corpus/2020/Smarch/1/1/1.1.bz2")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Smarch");
	}
}
