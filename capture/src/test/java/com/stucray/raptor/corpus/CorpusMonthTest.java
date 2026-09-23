package com.stucray.raptor.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorpusMonthTest {

	@Test
	void mapsDirectoryNamesToMonthKeys() {
		assertThat(CorpusMonth.key("2019", "Oct")).isEqualTo("2019-10");
		assertThat(CorpusMonth.key("2020", "Mar")).isEqualTo("2020-03");
		assertThat(CorpusMonth.key("2024", "Jan")).isEqualTo("2024-01");
		assertThat(CorpusMonth.key("2024", "Dec")).isEqualTo("2024-12");
	}

	/**
	 * The bug this guards against shipped once already: {@code indexOf("Ma")}
	 * lands on the "Ma" of "Mar", at a multiple of three, and passes as March.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"Ma", "March", "mar", "", "Foo"})
	void rejectsAnythingThatIsNotAThreeLetterMonth(String monthName) {
		assertThatThrownBy(() -> CorpusMonth.key("2020", monthName))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void roundTripsThroughThePathPrefix() {
		for (int month = 1; month <= 12; month++) {
			String key = "2021-%02d".formatted(month);
			String prefix = CorpusMonth.pathPrefix(key);
			assertThat(prefix).endsWith("/");
			String[] parts = prefix.split("/");
			assertThat(CorpusMonth.key(parts[0], parts[1]))
					.as("prefix %s must map back to %s", prefix, key)
					.isEqualTo(key);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"2020-13", "2020-00", "2020_03", "2020-3", "not-a-month"})
	void rejectsMalformedMonthKeys(String key) {
		assertThatThrownBy(() -> CorpusMonth.pathPrefix(key))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
