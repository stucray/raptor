package com.stucray.raptor.capture;

import java.util.List;

/**
 * What capture is configured to record.
 *
 * <p>Paddock owns this configuration and, since S6, executes it too: the
 * resident recorder's {@code CaptureSelection} reads the same file to decide
 * which competitions to scope. The read side reads it to show intent beside
 * outcome.
 *
 * <p><b>It carried three more settings until S8</b>, and all three described a
 * launchd window rather than the data: {@code runHours}, {@code fireHourUtc} and
 * {@code ledgerLagHours}, together with the five methods that turned them into a
 * lateness verdict. Nothing fires at any hour now — the recorder is resident and
 * {@code raw.market_scope} is what replaced the window — so a verdict computed
 * from a fire hour would have gone on answering confidently about a thing that
 * no longer happens. S10 moved the verdict onto scope, which is what left these
 * with no readers.
 *
 * @param leagues the leagues to capture, by name. Names and not ids because
 *     second-tier competition ids are season-scoped and rotate; a pinned list
 *     would keep working for the glamour divisions and silently stop capturing
 *     the rest at a rollover
 * @param marketTypes the market types captured per event
 * @param controlCountries the control set, filling capacity the target leagues
 *     leave under Betfair's subscription cap
 */
public record CaptureConfig(
    List<String> leagues,
    List<String> marketTypes,
    List<String> controlCountries) {

    public CaptureConfig {
        leagues = List.copyOf(leagues);
        marketTypes = List.copyOf(marketTypes);
        controlCountries = List.copyOf(controlCountries);
    }
}
