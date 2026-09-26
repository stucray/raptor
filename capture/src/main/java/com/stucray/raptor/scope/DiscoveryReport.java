package com.stucray.raptor.scope;

import java.time.Duration;
import java.util.List;

/**
 * What discovery knows about its own inability to find anything.
 *
 * <p>The counterpart to {@link ScopeSummary}, and the distinction is #180.
 * {@code ScopeSummary} counts what <em>is</em> in scope; this says how much to
 * trust that count. An empty scope is the correct state of a Tuesday morning
 * and it is also what a revoked app key, a renamed league and a typo in
 * {@code capture.properties} all look like — and until these three facts were
 * published, the two readings were byte-identical.
 *
 * @param consecutivePollFailures how many catalogue polls in a row have thrown.
 *     {@code MarketScopeService.poll()} swallows the exception by design — a
 *     REST failure must not retire a match in progress — so the count is the
 *     only honest way to make the swallow visible without changing it. Zero
 *     after any poll that completes.
 * @param configuredLeagues how many competition names the run asked for
 * @param leaguesWithNoOpenMarkets configured names {@code listCompetitions} did
 *     not list, verbatim. <b>Never a fault</b> (#9): it lists only competitions
 *     that currently have markets, so a league between rounds or over an
 *     international break is legitimately absent, for weeks if need be. Neutral
 *     information, and still requested on every poll
 * @param ambiguousLeagues configured names that matched more than one
 *     competition, with the ids each matched. Positive evidence of a problem:
 *     capture takes neither until the name is made unambiguous
 * @param sinceScopeNonEmpty how long since a poll last found anything at all.
 *     Informational, never a verdict: a genuine international break is days of
 *     legitimately empty scope, and the threshold that separates it from a
 *     broken discovery is the thing this record exists to let somebody
 *     calibrate. Measured from startup while nothing has yet been seen, since
 *     the observation and not the ledger is what resets on a restart.
 * @param scopeNonEmptyFor how long scope has been continuously non-empty — the
 *     length of the current card, in effect — and {@code ZERO} while there is
 *     nothing in scope. <b>The distinction from {@code sinceScopeNonEmpty} is
 *     #278.</b> That one is a recency and moves on every poll that finds
 *     something; this is a streak and moves only when scope fills. Anything
 *     asking "for how long has this been wrong <em>while there was something to
 *     capture</em>" needs the streak: a dwell measured against the recency can
 *     never exceed the poll interval, and one measured against the recorder's
 *     own clock counts hours of correct overnight idleness as a fault.
 */
public record DiscoveryReport(int consecutivePollFailures, int configuredLeagues,
		List<String> leaguesWithNoOpenMarkets, List<String> ambiguousLeagues,
		Duration sinceScopeNonEmpty, Duration scopeNonEmptyFor) {

	public DiscoveryReport {
		leaguesWithNoOpenMarkets = List.copyOf(leaguesWithNoOpenMarkets);
		ambiguousLeagues = List.copyOf(ambiguousLeagues);
	}
}
