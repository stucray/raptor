package com.stucray.raptor.scope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What gets recorded when not everything fits.
 *
 * <p>Betfair caps a connection at about 200 markets, and exceeding it fails the
 * whole subscription rather than truncating it — the first live run reconnected
 * into {@code SUBSCRIPTION_LIMIT_EXCEEDED} every five seconds, indefinitely. So
 * the trim is not an optimisation; it is the difference between a capture and
 * no capture at all, and each rule below was paid for by a real Saturday.
 */
class SubscriptionPlannerTest {

	private static final Instant KICKOFF = Instant.parse("2026-09-05T14:00:00Z");

	/** Under the cap, everything in scope is subscribed. */
	@Test
	void takesEverythingWhenEverythingFits() {
		List<ScopedMarket> open = event("e1", KICKOFF, 4, ScopeState.PENDING, true);

		SubscriptionPlan plan = new SubscriptionPlanner(200).plan(open);

		assertThat(plan.marketIds()).hasSize(4);
		assertThat(plan.droppedRequestedEvents()).isZero();
	}

	/**
	 * A fixture is captured whole or not at all.
	 *
	 * <p>The over/under lines are only worth anything beside the match odds they
	 * move with, so a market-by-market trim would fill the last slots with halves
	 * of two fixtures instead of the whole of one.
	 */
	@Test
	void neverTakesHalfAnEvent() {
		List<ScopedMarket> open = new ArrayList<>();
		open.addAll(event("early", KICKOFF, 4, ScopeState.PENDING, true));
		open.addAll(event("late", KICKOFF.plusSeconds(3600), 4, ScopeState.PENDING, true));

		SubscriptionPlan plan = new SubscriptionPlanner(6).plan(open);

		assertThat(plan.marketIds()).hasSize(4).allMatch(id -> id.startsWith("early"));
		assertThat(plan.droppedRequestedEvents()).isEqualTo(1);
	}

	/**
	 * A market already being recorded is never dropped to admit a new one.
	 *
	 * <p>Losing the second half of a match in progress to make room for a fixture
	 * that has not kicked off is a bad trade every time — and it is the trade any
	 * "earliest kickoff first" policy would keep making as the evening went on,
	 * because the fixture in progress always has the earliest kickoff of all.
	 */
	@Test
	void neverDropsAMarketAlreadyBeingRecorded() {
		List<ScopedMarket> open = new ArrayList<>();
		open.addAll(event("running", KICKOFF, 4, ScopeState.LIVE, true));
		open.addAll(event("new", KICKOFF.plusSeconds(60), 4, ScopeState.PENDING, true));

		SubscriptionPlan plan = new SubscriptionPlanner(4).plan(open);

		assertThat(plan.marketIds()).allMatch(id -> id.startsWith("running"));
	}

	/**
	 * The control set never crowds out the leagues the programme exists for.
	 *
	 * <p>This is the ordering that cost a night on 2026-08-21: a country filter
	 * plus four market types matched far more than the cap, and without tiering
	 * the target leagues are simply whatever survived an arbitrary sort.
	 */
	@Test
	void requestedCompetitionsOutrankTheControlSetEvenWhenTheyKickOffLater() {
		List<ScopedMarket> open = new ArrayList<>();
		open.addAll(event("control", KICKOFF, 4, ScopeState.PENDING, false));
		open.addAll(event("target", KICKOFF.plusSeconds(7200), 4, ScopeState.PENDING, true));

		SubscriptionPlan plan = new SubscriptionPlanner(4).plan(open);

		assertThat(plan.marketIds()).allMatch(id -> id.startsWith("target"));
		assertThat(plan.droppedControlEvents()).isEqualTo(1);
		assertThat(plan.droppedRequestedEvents()).isZero();
	}

	/** Within a tier, the fixture about to start beats the one in three hours. */
	@Test
	void takesTheEarliestKickoffFirstWithinATier() {
		List<ScopedMarket> open = new ArrayList<>();
		open.addAll(event("later", KICKOFF.plusSeconds(10_800), 2, ScopeState.PENDING, true));
		open.addAll(event("sooner", KICKOFF, 2, ScopeState.PENDING, true));

		SubscriptionPlan plan = new SubscriptionPlanner(2).plan(open);

		assertThat(plan.marketIds()).allMatch(id -> id.startsWith("sooner"));
	}

	/**
	 * A big event that does not fit must not shut out a small one behind it.
	 *
	 * <p>Stopping at the first event that overflows would drop fixtures that
	 * would have fitted — the same capacity loss the cap exists to manage, but
	 * self-inflicted.
	 */
	@Test
	void keepsLookingAfterAnEventThatDoesNotFit() {
		List<ScopedMarket> open = new ArrayList<>();
		open.addAll(event("big", KICKOFF, 4, ScopeState.PENDING, true));
		open.addAll(event("small", KICKOFF.plusSeconds(600), 1, ScopeState.PENDING, true));

		SubscriptionPlan plan = new SubscriptionPlanner(3).plan(open);

		assertThat(plan.marketIds()).containsExactly("small-0");
		assertThat(plan.droppedRequestedEvents()).isEqualTo(1);
	}

	/**
	 * A market with no event id stands alone.
	 *
	 * <p>The catalogue does not promise an event id. Grouping every market that
	 * lacks one into a single pseudo-event would make them all-or-nothing
	 * together, which is an arbitrary bundle nobody asked for.
	 */
	@Test
	void treatsAMarketWithNoEventAsItsOwnEvent() {
		List<ScopedMarket> open = List.of(
				new ScopedMarket("orphan-a", null, KICKOFF, ScopeState.PENDING, null, true),
				new ScopedMarket("orphan-b", null, KICKOFF.plusSeconds(60), ScopeState.PENDING,
						null, true));

		SubscriptionPlan plan = new SubscriptionPlanner(1).plan(open);

		assertThat(plan.marketIds()).containsExactly("orphan-a");
		assertThat(plan.droppedRequestedEvents()).isEqualTo(1);
	}

	/** A missing kickoff sorts last, but is not a reason to drop the market. */
	@Test
	void capturesAMarketWithNoKickoffWhenThereIsRoom() {
		List<ScopedMarket> open = new ArrayList<>();
		open.add(new ScopedMarket("undated-0", "undated", null, ScopeState.PENDING, null, true));
		open.addAll(event("dated", KICKOFF, 1, ScopeState.PENDING, true));

		SubscriptionPlan plan = new SubscriptionPlanner(2).plan(open);

		assertThat(plan.marketIds()).containsExactly("dated-0", "undated-0");
	}

	/** Nothing in scope is a plan for nothing, not a crash. */
	@Test
	void plansNothingFromNothing() {
		assertThat(new SubscriptionPlanner(200).plan(List.of()).marketIds()).isEmpty();
	}

	/**
	 * The plan never exceeds the cap, however much is already in flight.
	 *
	 * <p>2026-09-05, 14:11Z. A mass 14:00Z kickoff put 303 markets in play at
	 * once against 37 still merely subscribed, and the in-flight tier was added
	 * without a cap check: the plan came to 340, Betfair answered
	 * {@code SUBSCRIPTION_LIMIT_EXCEEDED}, and the recorder crashlooped every
	 * seven seconds for the rest of the card — burning a capture session per
	 * attempt and writing nothing at all.
	 *
	 * <p>The lesson is that refusing to trim is not the safe direction. A plan
	 * the server rejects does not cost the marginal market, it costs every
	 * market, so the cap has to bind on every tier including this one.
	 */
	@Test
	void neverPlansMoreThanTheCapWhenEverythingIsAlreadyInFlight() {
		List<ScopedMarket> open = new ArrayList<>();
		for (int i = 0; i < 85; i++) {
			open.addAll(event("live-" + i, KICKOFF, 4, ScopeState.LIVE, true));
		}

		SubscriptionPlan plan = new SubscriptionPlanner(200).plan(open);

		assertThat(plan.marketIds()).hasSizeLessThanOrEqualTo(200);
		assertThat(plan.droppedRequestedEvents()).isPositive();
	}

	/**
	 * When the in-flight tier itself has to be trimmed, the control set goes
	 * first — a match in progress in a target league outranks one that is only
	 * being watched to calibrate against.
	 */
	@Test
	void requestedLeaguesSurviveATrimOfTheInFlightTier() {
		List<ScopedMarket> open = new ArrayList<>();
		// The control fixture kicked off first, so an untiered "earliest kickoff
		// wins" would keep it and drop the target league.
		open.addAll(event("control", KICKOFF, 4, ScopeState.LIVE, false));
		open.addAll(event("target", KICKOFF.plusSeconds(1800), 4, ScopeState.LIVE, true));

		SubscriptionPlan plan = new SubscriptionPlanner(4).plan(open);

		assertThat(plan.marketIds()).allMatch(id -> id.startsWith("target"));
		assertThat(plan.droppedControlEvents()).isEqualTo(1);
		assertThat(plan.droppedRequestedEvents()).isZero();
	}

	/**
	 * A target fixture that has not kicked off beats a control match in progress.
	 *
	 * <p>The tiers exist so the control set can never crowd out the leagues the
	 * programme is for, and until #172 they only half did: every tier was capped
	 * (#169), but in-flight ranked above requested, so a control match held its
	 * slot against a target fixture purely by having got there first. On
	 * 2026-09-05 that displaced one, then two, requested events while ninety-odd
	 * control markets kept theirs.
	 *
	 * <p>The cost is stated rather than hidden: this drops a control match
	 * mid-flow. That is the intended trade — half a control match is a poor thing
	 * to hold capacity with when the alternative is missing the start of a
	 * fixture the programme exists to record.
	 */
	@Test
	void requestedFixtureNotYetKickedOffOutranksAControlMatchInProgress() {
		List<ScopedMarket> open = new ArrayList<>();
		open.addAll(event("control", KICKOFF, 4, ScopeState.LIVE, false));
		open.addAll(event("target", KICKOFF.plusSeconds(3600), 4, ScopeState.PENDING, true));

		SubscriptionPlan plan = new SubscriptionPlanner(4).plan(open);

		assertThat(plan.marketIds()).allMatch(id -> id.startsWith("target"));
		assertThat(plan.droppedControlEvents()).isEqualTo(1);
		assertThat(plan.droppedRequestedEvents()).isZero();
	}

	/**
	 * Reordering the classes did not collapse the ordering inside them: a control
	 * match in progress still beats a control fixture that has not started.
	 */
	@Test
	void withinTheControlSetAMatchInProgressStillBeatsOneNotStarted() {
		List<ScopedMarket> open = new ArrayList<>();
		// The pending one kicks off first, so only the in-flight rule can save the
		// running match here.
		open.addAll(event("running", KICKOFF.plusSeconds(3600), 4, ScopeState.LIVE, false));
		open.addAll(event("upcoming", KICKOFF, 4, ScopeState.PENDING, false));

		SubscriptionPlan plan = new SubscriptionPlanner(4).plan(open);

		assertThat(plan.marketIds()).allMatch(id -> id.startsWith("running"));
		assertThat(plan.droppedControlEvents()).isEqualTo(1);
	}

	private static List<ScopedMarket> event(String eventId, Instant kickoff, int markets,
			ScopeState state, boolean requested) {
		List<ScopedMarket> group = new ArrayList<>();
		for (int i = 0; i < markets; i++) {
			group.add(new ScopedMarket(eventId + "-" + i, eventId, kickoff, state, null, requested));
		}
		return group;
	}
}
