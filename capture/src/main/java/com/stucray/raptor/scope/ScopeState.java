package com.stucray.raptor.scope;

/** Where a market is in its life inside the recorder's scope. */
public enum ScopeState {

	/**
	 * Kickoff is inside the horizon; not in the current subscription.
	 *
	 * <p><b>Not the same as "has not started".</b> A market the planner could not
	 * fit under the 200-market cap is PENDING while its match is played, and one
	 * displaced from the plan comes back here rather than going to DONE — it has
	 * lost a slot, not left scope. {@code in_play_since} is what separates the
	 * two cases, and it is dated whether or not the market ever reached the wire.
	 */
	PENDING,

	/** In the current {@code marketSubscription}. */
	SUBSCRIBED,

	/**
	 * On the wire, and the catalogue has reported it in-play.
	 *
	 * <p><b>Both halves, since #219.</b> It used to mean only the second, so a
	 * market that never fitted under the cap reached LIVE at kickoff anyway — and
	 * this state is not as inert as it looks: the planner reads SUBSCRIBED and
	 * LIVE together as "being recorded", and gives that tier precedence over a
	 * fixture waiting to start. A market that was never subscribed was therefore
	 * holding a slot it did not hold, and the capture ledger was recording
	 * in-play capture of a market nothing was listening to.
	 *
	 * <p>Still skippable, and still not something anything waits for: a market
	 * can close without a poll ever catching it in-play. What it dates is the
	 * {@link ScopeExit#IN_PLAY_ELAPSED} guard — through {@code in_play_since},
	 * which is written for every market in scope rather than only for these.
	 */
	LIVE,

	/** Out of scope. The row stays; {@link ScopeExit} says why. */
	DONE
}
