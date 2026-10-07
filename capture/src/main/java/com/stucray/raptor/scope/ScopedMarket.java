package com.stucray.raptor.scope;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A market in the recorder's scope, as the ledger holds it.
 *
 * @param inPlaySince when a poll first reported it in-play, and the clock
 *     {@link ScopeExit#IN_PLAY_ELAPSED} measures from
 * @param connectionSlot the connection slot whose subscription carries it, or
 *     null while it is on no connection — and on a row subscribed before
 *     connection slots existed (#64)
 */
public record ScopedMarket(
		String marketId,
		@Nullable String eventId,
		@Nullable Instant kickoff,
		ScopeState state,
		@Nullable Instant inPlaySince,
		boolean requested,
		@Nullable Integer connectionSlot) {

	/** A market on no connection slot. */
	public ScopedMarket(String marketId, @Nullable String eventId, @Nullable Instant kickoff,
			ScopeState state, @Nullable Instant inPlaySince, boolean requested) {
		this(marketId, eventId, kickoff, state, inPlaySince, requested, null);
	}

	/** Whether this market still wants to be in a subscription. */
	public boolean open() {
		return state != ScopeState.DONE;
	}
}
