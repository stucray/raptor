package com.stucray.raptor.scope;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What is in scope, counted.
 *
 * @param marketsInScope every market that has not left scope, whatever it is
 *     doing — the number that decides whether a silent recorder is a quiet
 *     Tuesday or a lost night
 * @param nextKickoff the earliest kickoff still in scope, or null when nothing
 *     in scope carries one. An empty horizon with a kickoff forty minutes away
 *     is a stream about to open; an empty horizon with no kickoff at all is a
 *     catalogue poll worth looking at.
 */
public record ScopeSummary(int marketsInScope, int pending, int subscribed, int live,
		@Nullable Instant nextKickoff) {

	/** Whether there is anything at all the recorder should be capturing. */
	public boolean anythingToCapture() {
		return marketsInScope > 0;
	}
}
