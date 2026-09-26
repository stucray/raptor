package com.stucray.raptor.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the paddock-owned capture config (PRD #51 slice 6).
 *
 * <p>The happy path reads THE SHIPPED FILE — {@code config/capture.properties},
 * the same one the data repo's launcher parses — rather than a fixture of
 * its own. A fixture would prove the parser handles the shape the parser's
 * author had in mind; this proves the file the recorder will actually run
 * on is readable, complete and well-formed, and a typo in it fails the
 * build instead of a capture night.
 */
@DisplayName("Capture config: the shipped file, and what happens without one")
class FileCaptureConfigProviderTest {

    /** Surefire runs from the backend module, so the repo root is one up. */
    private static final Path SHIPPED = Path.of("../config/capture.properties");

    /** Irrelevant to reading config, but part of the properties record. */

    @Test
    void theShippedConfigParsesAndCarriesTheEightTargetLeagues() {
        CaptureConfig c = new FileCaptureConfigProvider(
            new CaptureProperties(SHIPPED.toString())).current().orElseThrow();

        assertThat(c.leagues())
            .hasSize(8)
            .contains("English Premier League", "Spanish Segunda Division");
        // MATCH_ODDS plus the three O/U lines the historic corpus carries.
        assertThat(c.marketTypes()).hasSize(4).contains("MATCH_ODDS");
        assertThat(c.controlCountries())
            .as("the control set is retired (#11)")
            .isEmpty();
    }

    @Test
    void anAbsentConfigDegradesRatherThanThrowing() {
        // The file is mounted, so a host that has not mounted it is a
        // deployment state the health screen must survive: better a panel
        // that says the config is unreadable than an endpoint that 500s.
        assertThat(new FileCaptureConfigProvider(
            new CaptureProperties("/no/such/capture.properties")).current())
            .isEmpty();
    }

    @Test
    void aConfigMissingAKeyIsTreatedAsUnreadable(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("capture.properties");
        Files.writeString(f, "capture.leagues=English Premier League\n");

        assertThat(new FileCaptureConfigProvider(
            new CaptureProperties(f.toString())).current()).isEmpty();
    }

    @Test
    void aConfigWithNoControlSetIsReadableAndHasNone(@TempDir Path dir) throws Exception {
        // The control set is retired (#11) and its key gone from the shipped
        // file. This reader once required it, so the health screen reported the
        // live configuration as unreadable the moment the key was removed.
        Path f = dir.resolve("capture.properties");
        Files.writeString(f, "capture.leagues=Italian Serie B\ncapture.market-types=MATCH_ODDS\n");

        CaptureConfig c = new FileCaptureConfigProvider(
            new CaptureProperties(f.toString())).current().orElseThrow();
        assertThat(c.leagues()).containsExactly("Italian Serie B");
        assertThat(c.controlCountries()).isEmpty();
    }

    @Test
    void listValuesAreTrimmedSoTheFileCanBeReadableToPeople(@TempDir Path dir)
            throws Exception {
        Path f = dir.resolve("capture.properties");
        Files.writeString(f, """
            capture.leagues=Italian Serie A, Italian Serie B ,
            capture.market-types=MATCH_ODDS
            capture.control-countries=GB
            capture.run-hours=12
            capture.fire-hour-utc=11
            capture.ledger-lag-hours=6
            """);

        assertThat(new FileCaptureConfigProvider(
            new CaptureProperties(f.toString())).current().orElseThrow().leagues())
            .containsExactly("Italian Serie A", "Italian Serie B");
    }
}
