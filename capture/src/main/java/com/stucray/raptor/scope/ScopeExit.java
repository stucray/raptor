package com.stucray.raptor.scope;

/**
 * Why a market left scope.
 *
 * <p>Two of these are answers and three are guards, and the split matters. A
 * market that is abandoned, voided, or simply never closed cleanly still has to
 * leave scope: Betfair's subscription cap is 200 markets, so a handful of
 * fixtures held open forever is a Saturday's worth of capacity gone, silently.
 * The guards are what make scope self-clearing without anybody watching it.
 */
public enum ScopeExit {

	/** The catalogue said {@code CLOSED}. The ordinary end. */
	CLOSED,

	/** The catalogue said {@code SETTLED}. */
	SETTLED,

	/**
	 * In-play for longer than any football match runs.
	 *
	 * <p>130 minutes covers 90 plus a generous half-time and the long stoppages
	 * that follow an injury; extra time is a different market and does not extend
	 * this one indefinitely.
	 */
	IN_PLAY_ELAPSED,

	/**
	 * Kickoff was long enough ago that nothing can still be happening.
	 *
	 * <p>The abandonment guard. It is the one that fires for a fixture that never
	 * started at all — postponed after the market was created, and therefore never
	 * in-play, never closed, and otherwise in scope forever.
	 */
	KICKOFF_ELAPSED,

	/**
	 * The catalogue stopped returning it while it was still only PENDING.
	 *
	 * <p>A rescheduled or withdrawn fixture, seen before it moved. Distinct from
	 * the guards because nothing timed out — the market simply is not there any
	 * more, and a row that says so is better than one that waits six hours to
	 * conclude the same thing.
	 */
	OUT_OF_HORIZON
}
