package com.stucray.raptor.betfair;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading what the programme asked for, from the file the launcher reads.
 *
 * <p>The shipped file is exercised on purpose. `capture.properties` is the one
 * definition of what a night records — the data repo's launcher builds the
 * recorder's arguments from it and paddock's health screen reads it — so a test
 * that parsed a fixture would prove only that a parser works, not that this
 * parser can read the file it will actually be given.
 */
class CaptureSelectionTest {

	/** The real file, in the repo, as committed. */
	@Test
	void readsTheShippedCaptureConfiguration() {
		CaptureSelection.Selection selection = selection(Path.of("../config/capture.properties"));

		assertThat(selection.leagues())
				.contains("English Premier League", "Italian Serie B", "Spanish Segunda Division")
				.hasSize(8);
		assertThat(selection.marketTypes())
				.containsExactly("MATCH_ODDS", "OVER_UNDER_15", "OVER_UNDER_25", "OVER_UNDER_35");
		assertThat(selection.controlCountries())
				.as("the control set is retired (#11): every market in scope is one the leagues asked for")
				.isEmpty();
		assertThat(selection.isEmpty()).isFalse();
	}

	/**
	 * A missing file asks Betfair for nothing.
	 *
	 * <p>Not built-in defaults: a night captured on a league set nobody chose
	 * looks identical afterwards to a night captured on the configured one, which
	 * is the failure this file exists to prevent. Empty is loud in the ledger;
	 * a plausible default is not.
	 */
	@Test
	void asksForNothingWhenTheFileIsMissing(@TempDir Path directory) {
		CaptureSelection.Selection selection = selection(directory.resolve("absent.properties"));

		assertThat(selection.isEmpty()).isTrue();
		assertThat(selection.leagues()).isEmpty();
	}

	/** Re-read per call: changing the league set is one edit, not a redeploy. */
	@Test
	void seesAnEditWithoutARestart(@TempDir Path directory) throws Exception {
		Path file = directory.resolve("capture.properties");
		java.nio.file.Files.writeString(file, "capture.leagues=Serie A\ncapture.market-types=MATCH_ODDS\ncapture.control-countries=GB\n");
		CaptureSelection reader = new CaptureSelection(properties(file));
		assertThat(reader.current().leagues()).containsExactly("Serie A");

		java.nio.file.Files.writeString(file, "capture.leagues=Serie A,La Liga\ncapture.market-types=MATCH_ODDS\ncapture.control-countries=GB\n");

		assertThat(reader.current().leagues()).containsExactly("Serie A", "La Liga");
	}

	private static CaptureSelection.Selection selection(Path file) {
		return new CaptureSelection(properties(file)).current();
	}

	private static BetfairProperties properties(Path file) {
		return new BetfairProperties("", "", "", "", "", "https://certlogin.invalid",
				"https://keepalive.invalid", "https://rest.invalid/", file);
	}
}
