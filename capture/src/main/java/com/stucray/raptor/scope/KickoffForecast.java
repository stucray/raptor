package com.stucray.raptor.scope;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What is known about kickoffs beyond the scope horizon, and how well it is
 * known.
 *
 * <p>Three states, and the point of the record is that they are three rather
 * than two. A caller deciding whether it is safe to shut a laptop lid needs to
 * tell "nothing is on for two days" from "nobody has been able to ask", and a
 * single nullable instant cannot say which. Defaulting a failed poll to "clear"
 * is the failure shape of #122, #141 and #144 — here it would invite closing the
 * lid on a live card.
 *
 * @param nextKickoff the earliest kickoff inside the lookahead window, or null
 *     when the window genuinely holds none
 * @param measuredAt when a poll last completed, or null when none ever has —
 *     which is what the first minutes after a restart look like, and what a
 *     process with no Betfair credentials looks like forever
 * @param consecutiveFailures polls that have thrown in a row. Non-zero with a
 *     {@code measuredAt} means the figure is the last good one and is going
 *     stale; the age is the reader's to judge, because how stale is too stale
 *     depends on what they are about to do.
 */
public record KickoffForecast(@Nullable Instant nextKickoff, @Nullable Instant measuredAt,
		int consecutiveFailures) {

	/** Before the first poll, and after a restart. */
	public static final KickoffForecast UNKNOWN = new KickoffForecast(null, null, 0);

	/** Whether anything has ever been measured, however long ago. */
	public boolean known() {
		return measuredAt != null;
	}
}
