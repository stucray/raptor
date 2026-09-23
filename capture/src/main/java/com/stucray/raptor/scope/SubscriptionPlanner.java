package com.stucray.raptor.scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Whole events, never half of one.</b> A fixture's MATCH_ODDS and its
 * three over/under lines are captured together or not at all. Half a bundle is
 * worth very little to calibration: the O/U lines are only interesting
 * alongside the match odds they move with, and a market-by-market trim would
 * silently produce exactly that.
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

	/**
	 * Betfair's per-connection market cap.
	 *
	 * <p>Not a documented number: it is what a subscription is observed to
	 * tolerate, and exceeding it fails the whole subscription rather than
	 * truncating it — which is why the trim happens here rather than being left
	 * to the server.
	 */
	static final int SUBSCRIPTION_CAP = 200;

	private final int cap;

	SubscriptionPlanner() {
		this(SUBSCRIPTION_CAP);
	}

	SubscriptionPlanner(int cap) {
		this.cap = cap;
	}

	/**
	 * The market ids to subscribe to.
	 *
	 * @param open every market still in scope
	 * @return ids in a stable order, capped, whole events only
	 */
	SubscriptionPlan plan(List<ScopedMarket> open) {
		Set<String> chosen = new LinkedHashSet<>();
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
		droppedRequested += take(byEvent(inFlight(open, true)), chosen);
		droppedRequested += take(byEvent(pending(open, true)), chosen);
		droppedControl += take(byEvent(inFlight(open, false)), chosen);
		droppedControl += take(byEvent(pending(open, false)), chosen);

		if (droppedRequested > 0) {
			// Loud: this is the case where the programme's own leagues did not fit,
			// which is a configuration problem (too many market types, too long a
			// horizon) rather than a busy evening.
			log.warn("{} requested event(s) did not fit under the {}-market cap; "
					+ "narrow the horizon or the market types", droppedRequested, cap);
		}
		if (droppedControl > 0) {
			log.info("{} control event(s) not subscribed; no capacity left under the "
					+ "{}-market cap", droppedControl, cap);
		}
		return new SubscriptionPlan(List.copyOf(chosen), droppedRequested, droppedControl);
	}

	/**
	 * Fit whole events into what is left of the cap.
	 *
	 * <p>An event that does not fit is skipped, not partially taken — and the
	 * loop continues rather than stopping, because a later event may be smaller.
	 * Skipping to the end would drop fixtures that would have fitted, which is
	 * the same capacity loss the cap exists to manage.
	 */
	private int take(List<List<ScopedMarket>> events, Set<String> chosen) {
		int dropped = 0;
		for (List<ScopedMarket> event : events) {
			List<String> ids = ids(event);
			long adding = ids.stream().filter(id -> !chosen.contains(id)).count();
			if (chosen.size() + adding > cap) {
				dropped++;
				continue;
			}
			chosen.addAll(ids);
		}
		return dropped;
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
			String key = market.eventId() == null ? "market:" + market.marketId()
					: "event:" + market.eventId();
			events.computeIfAbsent(key, k -> new ArrayList<>()).add(market);
		}
		List<List<ScopedMarket>> grouped = new ArrayList<>(events.values());
		grouped.sort(Comparator
				.<List<ScopedMarket>, Long>comparing(SubscriptionPlanner::earliestKickoffEpoch)
				.thenComparing(event -> ids(event).getFirst()));
		return grouped;
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
