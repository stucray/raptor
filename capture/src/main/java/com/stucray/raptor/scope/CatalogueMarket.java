package com.stucray.raptor.scope;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One market as Betfair's REST catalogue describes it.
 *
 * <p>The boundary type between "what the upstream said" and "what the recorder
 * decided". Everything downstream of here — the state machine, the planner —
 * works on this record and never on a JSON node, which is what lets both be
 * tested without a Betfair session.
 *
 * @param eventId the fixture this market belongs to. The planner trims by whole
 *     events, so this is load-bearing rather than descriptive: half a fixture's
 *     MATCH_ODDS + O/U bundle is worth little to calibration.
 * @param kickoff Betfair's {@code marketStartTime}. Nullable because the
 *     catalogue does not promise it, and a market with no start time must still
 *     be capturable — it sorts last rather than being dropped.
 * @param inPlay whether the catalogue reported it in-play at the moment of the
 *     poll
 * @param status the catalogue's own word: {@code OPEN}, {@code SUSPENDED},
 *     {@code CLOSED}, {@code INACTIVE}. Carried verbatim rather than mapped to
 *     an enum here, so an unrecognised value cannot cost the poll.
 */
public record CatalogueMarket(
		String marketId,
		@Nullable String eventId,
		@Nullable String eventName,
		@Nullable String competitionId,
		@Nullable String competitionName,
		String marketType,
		@Nullable String countryCode,
		@Nullable Instant kickoff,
		boolean inPlay,
		@Nullable String status) {}
