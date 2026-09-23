package com.stucray.raptor.scope;

import java.util.List;

/**
 * What the recorder is allowed to know about scope.
 *
 * <p>The module's published surface, and deliberately three methods wide. The
 * state machine, the guards, the catalogue port and the trimming policy are all
 * implementation: the recorder's business is to ask what to subscribe to and to
 * report what it did subscribe to, and a wider interface would invite it to
 * start making scope decisions of its own — which is precisely the tangle that
 * made the twelve-hour window impossible to move away from.
 */
public interface CaptureScope {

	/** The markets to subscribe to now, trimmed to Betfair's cap. */
	SubscriptionPlan plan();

	/**
	 * Record that these markets are in the subscription the server accepted.
	 *
	 * <p>Called after the subscribe, never before: a market marked subscribed
	 * that the server refused would be protected from the next trim by the very
	 * tier that exists to protect markets actually being recorded.
	 */
	void subscribed(List<String> marketIds);
}
