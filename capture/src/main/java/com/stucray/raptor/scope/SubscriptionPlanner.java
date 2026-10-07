package com.stucray.raptor.scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which markets go in the subscription, when not all of them fit.
 *
 * <p>Betfair caps one connection at about 200 markets, and four market types
 * across a country filter blows through that outright — the first live run
 * discovered it by reconnecting into {@code SUBSCRIPTION_LIMIT_EXCEEDED} every
 * five seconds, forever. So something has to choose, and the choice is not
 * arbitrary.
 *
 * <p><b>Partitioned across connections (#64).</b> The cap is per connection,
 * and an app key allows several (#56, #63), so the plan is one subscription per
 * <em>connection slot</em>: raptor's own number for a connection, from 0 up to
 * {@code maxConnections - 1}, stable across reconnects. The trim binds on each
 * connection separately, and a fixture stays on one connection until it leaves
 * scope.
 *
 * <p><b>Whole events, never half of one.</b> A fixture's MATCH_ODDS and its
 * three over/under lines are captured together or not at all. The reason given
 * when this was written was analytical (an O/U line is read alongside the match
 * odds it moves with), which is not a capture reason (#61). The policy stands
 * until that review replaces it.
 *
 * <p><b>Four tiers, in order — requested before control, and only then
 * in-flight before pending.</b>
 * <ol>
 * <li><b>Requested, already subscribed or in play.</b> A target-league match
 * being recorded outranks everything.
 * <li><b>Requested, not yet kicked off.</b> The leagues the programme exists to
 * record. The control set must never crowd these out — that is the whole reason
 * the tiers exist rather than one sorted list, and it is why this tier sits
 * above a control match already in progress rather than below it (#172).
 * <li><b>Control, already subscribed or in play.</b>
 * <li><b>Control, not yet kicked off.</b> Takes whatever capacity is left.
 * </ol>
 *
 * <p><b>Two consequences worth stating, because both are trades and neither is
 * free.</b>
 *
 * <p>Within a league class, a fixture that has not kicked off never displaces
 * one being recorded: losing the second half of a match to admit it is a bad
 * trade every time, and it is the trade a newest-first policy would keep making
 * because the match in progress always has the earliest kickoff of all.
 *
 * <p>Across league classes it is the other way round, deliberately. A control
 * match <b>can</b> now be dropped mid-flow to admit a target fixture that has
 * not started — half a control match is a poor thing to hold a slot with when
 * the alternative is missing the start of a league the programme is for. What
 * this must never become is an exemption from the cap: every tier goes through
 * the same trim, because a plan the server refuses drops the whole card rather
 * than the marginal market. That was #169 — an uncapped in-flight tier produced
 * a 340-market plan on 2026-09-05 and Betfair refused it outright.
 *
 * <p>Within a tier, earliest kickoff first: the fixture about to start is worth
 * more than the one in three hours, which may still fit on the next plan.
 *
 * <p>A port of {@code record_suspensions.py}'s {@code select_ids}, deliberately
 * — the policy has been exercised over a season of real Saturdays, and this
 * slice is not the place to invent a new one.
 */
@Component
class SubscriptionPlanner {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionPlanner.class);

	private final int cap;
	private final int maxConnections;

	@Autowired
	SubscriptionPlanner(ScopeProperties properties) {
		this(properties.marketsPerConnection(), properties.maxConnections());
	}

	/** One connection carrying at most {@code cap} markets. */
	SubscriptionPlanner(int cap) {
		this(cap, 1);
	}

	SubscriptionPlanner(int cap, int maxConnections) {
		this.cap = cap;
		this.maxConnections = maxConnections;
	}

	/**
	 * What each connection slot should subscribe to.
	 *
	 * @param open every market still in scope
	 * @return one subscription per slot that carries anything, slot 0 always;
	 *     ids in a stable order, capped per connection, whole events only
	 */
	SubscriptionPlan plan(List<ScopedMarket> open) {
		List<Set<String>> slots = new ArrayList<>();
		for (int slot = 0; slot < maxConnections; slot++) {
			slots.add(new LinkedHashSet<>());
		}
		Map<String, Integer> home = homes(open);
		int droppedRequested = 0;
		int droppedControl = 0;

		// EVERY tier goes through the cap, in-flight included. Tier 1 did not, and
		// on 2026-09-05 the 14:00Z kickoff wave put 303 markets in play at once:
		// the plan came to 340, Betfair refused the whole subscription with
		// SUBSCRIPTION_LIMIT_EXCEEDED, and the recorder crashlooped through the
		// rest of the card writing nothing (#169). "A market being recorded is
		// never dropped to make room" has to yield to the cap, because a plan the
		// server refuses does not drop the marginal market — it drops all of them.
		//
		// Requested before control, and only then in-flight before pending (#172).
		// The first fix capped every tier but kept in-flight ahead of requested,
		// which let the control set hold slots against a target fixture merely
		// because it got there first — the exact crowding-out the tiers exist to
		// prevent.
		droppedRequested += take(byEvent(inFlight(open, true)), slots, home);
		droppedRequested += take(byEvent(pending(open, true)), slots, home);
		droppedControl += take(byEvent(inFlight(open, false)), slots, home);
		droppedControl += take(byEvent(pending(open, false)), slots, home);

		if (droppedRequested > 0) {
			// Loud: this is the case where the configured leagues did not fit, which
			// is a configuration problem (too many market types, too long a horizon,
			// too few connections) rather than a busy evening.
			log.warn("{} requested event(s) did not fit on {} connection(s) of {} markets; "
					+ "narrow the horizon or the market types, or allow more connections",
					droppedRequested, maxConnections, cap);
		}
		if (droppedControl > 0) {
			log.info("{} control event(s) not subscribed; no capacity left on {} connection(s) "
					+ "of {} markets", droppedControl, maxConnections, cap);
		}
		List<ConnectionSubscription> connections = new ArrayList<>();
		for (int slot = 0; slot < slots.size(); slot++) {
			if (slot == 0 || !slots.get(slot).isEmpty()) {
				connections.add(new ConnectionSubscription(slot, List.copyOf(slots.get(slot))));
			}
		}
		return new SubscriptionPlan(connections, droppedRequested, droppedControl);
	}

	/**
	 * Fit whole events into the connections.
	 *
	 * <p>An event that does not fit is skipped, not partially taken — and the
	 * loop continues rather than stopping, because a later event may be smaller.
	 * Skipping to the end would drop fixtures that would have fitted, which is
	 * the same capacity loss the cap exists to manage.
	 *
	 * <p><b>An event with a home stays there.</b> Moving it would resubscribe two
	 * connections, and a resubscribe asks Betfair for a full image of every
	 * market on the connection, so an event already on a connection either fits
	 * on that one or is dropped, exactly as it was when there was only one
	 * connection. An event without a home goes to the lowest-numbered connection
	 * with room, and that becomes its home for the rest of the plan, so a market
	 * of the same fixture in a later tier joins it rather than splitting it.
	 */
	private int take(List<List<ScopedMarket>> events, List<Set<String>> slots,
			Map<String, Integer> home) {
		int dropped = 0;
		for (List<ScopedMarket> event : events) {
			List<String> ids = ids(event);
			String key = eventKey(event.getFirst());
			Integer slot = placement(home.get(key), slots, ids);
			if (slot == null) {
				dropped++;
				continue;
			}
			slots.get(slot).addAll(ids);
			home.put(key, slot);
		}
		return dropped;
	}

	/** Its home if it fits there, the lowest slot with room if it has none. */
	private @Nullable Integer placement(@Nullable Integer home, List<Set<String>> slots,
			List<String> ids) {
		if (home != null) {
			return fits(slots.get(home), ids) ? home : null;
		}
		return lowestWithRoom(slots, ids);
	}

	private @Nullable Integer lowestWithRoom(List<Set<String>> slots, List<String> ids) {
		for (int slot = 0; slot < slots.size(); slot++) {
			if (fits(slots.get(slot), ids)) {
				return slot;
			}
		}
		return null;
	}

	private boolean fits(Set<String> slot, List<String> ids) {
		long adding = ids.stream().filter(id -> !slot.contains(id)).count();
		return slot.size() + adding <= cap;
	}

	/**
	 * The connection each event is already on.
	 *
	 * <p>Read from the markets on the wire, whatever their tier, so the answer is
	 * the same for a fixture's in-flight markets and for one discovered later. A
	 * slot that is no longer allowed is no home: lowering the number of
	 * connections must re-place what was on the closed ones, not drop it. A
	 * market subscribed before connection slots existed has no recorded slot and
	 * is placed like a new one.
	 */
	private Map<String, Integer> homes(List<ScopedMarket> open) {
		Map<String, Integer> home = new HashMap<>();
		for (ScopedMarket market : open) {
			Integer slot = market.connectionSlot();
			boolean onTheWire = market.state() == ScopeState.SUBSCRIBED
					|| market.state() == ScopeState.LIVE;
			if (onTheWire && slot != null && slot < maxConnections) {
				home.merge(eventKey(market), slot, Math::min);
			}
		}
		return home;
	}

	/**
	 * Markets the subscription already carries, or that are in play.
	 *
	 * <p>Split by {@code requested} so the programme's own leagues keep their
	 * precedence over the control set even when the trim reaches this tier —
	 * which is the whole reason the tiers exist.
	 */
	private static List<ScopedMarket> inFlight(List<ScopedMarket> open, boolean requested) {
		return open.stream()
				.filter(m -> m.state() == ScopeState.SUBSCRIBED || m.state() == ScopeState.LIVE)
				.filter(m -> m.requested() == requested)
				.toList();
	}

	private static List<ScopedMarket> pending(List<ScopedMarket> open, boolean requested) {
		return open.stream()
				.filter(m -> m.state() == ScopeState.PENDING && m.requested() == requested)
				.toList();
	}

	/**
	 * Group by event, earliest kickoff first.
	 *
	 * <p>A market with no event id is its own event: the catalogue does not
	 * promise one, and a fixture with a missing id should be capturable alone
	 * rather than silently grouped with every other market that also lacks one.
	 * A missing kickoff sorts last for the same reason — unknown is not urgent,
	 * but it is not a reason to drop the market either.
	 */
	private static List<List<ScopedMarket>> byEvent(List<ScopedMarket> markets) {
		Map<String, List<ScopedMarket>> events = new LinkedHashMap<>();
		for (ScopedMarket market : markets) {
			events.computeIfAbsent(eventKey(market), k -> new ArrayList<>()).add(market);
		}
		List<List<ScopedMarket>> grouped = new ArrayList<>(events.values());
		grouped.sort(Comparator
				.<List<ScopedMarket>, Long>comparing(SubscriptionPlanner::earliestKickoffEpoch)
				.thenComparing(event -> ids(event).getFirst()));
		return grouped;
	}

	private static String eventKey(ScopedMarket market) {
		return market.eventId() == null ? "market:" + market.marketId()
				: "event:" + market.eventId();
	}

	private static long earliestKickoffEpoch(List<ScopedMarket> event) {
		return event.stream()
				.map(ScopedMarket::kickoff)
				.filter(java.util.Objects::nonNull)
				.mapToLong(java.time.Instant::toEpochMilli)
				.min()
				.orElse(Long.MAX_VALUE);
	}

	private static List<String> ids(List<ScopedMarket> event) {
		return event.stream().map(ScopedMarket::marketId).sorted().toList();
	}

}
