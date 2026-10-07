package com.stucray.raptor.scope;

import java.util.List;

/**
 * What one stream connection should subscribe to.
 *
 * @param connectionSlot raptor's own number for the connection (0, 1, ...):
 *     stable across reconnects, and unrelated to Betfair's per-socket
 *     {@code connectionId}
 * @param marketIds the whole {@code marketSubscription} for that connection,
 *     whole events only, never more than one connection may carry
 */
public record ConnectionSubscription(int connectionSlot, List<String> marketIds) {

	public ConnectionSubscription {
		if (connectionSlot < 0) {
			throw new IllegalArgumentException("a connection slot is never negative");
		}
		marketIds = List.copyOf(marketIds);
	}
}
