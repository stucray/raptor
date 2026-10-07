package com.stucray.raptor.scope;

import java.util.List;

/**
 * What each connection should subscribe to now, and what did not fit.
 *
 * @param connections one subscription per connection slot that carries
 *     anything, in slot order. Slot 0 is always present, if only empty, so a
 *     single connection always has a plan to read.
 * @param droppedRequestedEvents events from the requested competitions that did
 *     not fit on any connection. Non-zero means the configuration is asking for
 *     more than the connections can carry — too many market types, too long a
 *     horizon, or too few connections — and is a different problem from a busy
 *     evening.
 * @param droppedControlEvents control-set events that found no capacity left.
 *     Expected on a matchday, and not a fault.
 */
public record SubscriptionPlan(List<ConnectionSubscription> connections,
		int droppedRequestedEvents, int droppedControlEvents) {

	public SubscriptionPlan {
		connections = List.copyOf(connections);
	}

	/** A plan for one connection: everything on slot 0, nothing dropped. */
	public static SubscriptionPlan onSlotZero(List<String> marketIds) {
		return new SubscriptionPlan(List.of(new ConnectionSubscription(0, marketIds)), 0, 0);
	}

	/** Every market in the plan, slot by slot. */
	public List<String> marketIds() {
		return connections.stream().flatMap(c -> c.marketIds().stream()).toList();
	}

	/** What one connection slot should carry; empty if the plan gives it nothing. */
	public List<String> marketIds(int connectionSlot) {
		return connections.stream()
				.filter(c -> c.connectionSlot() == connectionSlot)
				.findFirst()
				.map(ConnectionSubscription::marketIds)
				.orElse(List.of());
	}
}
