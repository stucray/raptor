package com.stucray.raptor.scope;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A market in the recorder's scope, as the ledger holds it.
 *
 * @param inPlaySince when a poll first reported it in-play, and the clock
 *     {@link ScopeExit#IN_PLAY_ELAPSED} measures from
 */
public record ScopedMarket(
		String marketId,
		@Nullable String eventId,
		@Nullable Instant kickoff,
		ScopeState state,
		@Nullable Instant inPlaySince,
		boolean requested) {

	/** Whether this market still wants to be in a subscription. */
	public boolean open() {
		return state != ScopeState.DONE;
	}
}
