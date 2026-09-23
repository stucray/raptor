package com.stucray.raptor.scope;

import java.util.List;

/**
 * What to subscribe to now, and what did not fit.
 *
 * @param marketIds the ids to put in the {@code marketSubscription}, capped and
 *     containing only whole events
 * @param droppedRequestedEvents events from the requested competitions that did
 *     not fit. Non-zero means the configuration is asking for more than one
 *     connection can carry — too many market types, or too long a horizon — and
 *     is a different problem from a busy evening.
 * @param droppedControlEvents control-set events that found no capacity left.
 *     Expected on a matchday, and not a fault.
 */
public record SubscriptionPlan(List<String> marketIds, int droppedRequestedEvents,
		int droppedControlEvents) {}
