package com.stucray.raptor.scope;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The dials that replace the capture window.
 *
 * @param horizon how far ahead a kickoff must be to enter scope. Two hours
 *     since #52. The capture cost it sets is how long before a card the host
 *     must be awake with its lid open. #52 weighed that against where the
 *     pre-kickoff activity sits, measured over every scoped market from
 *     2026-09-02 to 10-03: the last two hours before kickoff hold 64% of the
 *     money matched before kickoff and 81% of its stream messages. That half of
 *     the argument is a judgement about which data is worth having, which
 *     capture does not make (#61), so the value is a choice to be revisited
 *     there. Four hours, inherited from paddock #91, was never measured.
 * @param lateLookback how far back a kickoff may be for a market discovery has
 *     never seen to enter scope anyway (#57). Without it a fixture is captured
 *     only if some poll succeeds between Betfair listing it and its kickoff, so
 *     a host asleep across that window, or a fixture rescheduled at short
 *     notice, loses the whole match although raptor is running for most of it.
 *     Two hours because a market closes about two hours after kickoff: older
 *     ones have nothing left to capture, and the query shares the catalogue's
 *     200-market page with every in-play market raptor already follows.
 * @param pollInterval how often to re-read the catalogue. Deliberately the 900 s
 *     of {@code record_suspensions.py}'s {@code ID_REFRESH_S} — a value already
 *     exercised against Betfair's rate limits over a season, and this slice is
 *     not the place to find their edge again.
 * @param initialPollDelay how long after startup the first catalogue read
 *     happens. Seconds, not the interval: a restarted recorder that waits a
 *     whole interval before finding out what is on holds the single-writer
 *     lease and subscribes to nothing for that long, and a restart at 20:30 on
 *     a Saturday costs matches.
 * @param inPlayTimeout how long a market may stay in-play before the guard
 *     retires it. 130 minutes covers 90 plus a generous half-time and the long
 *     stoppages after an injury.
 * @param holdPowerAssertion whether to keep the machine awake while a market is
 *     in-play. On by default and a flag anyway: sleep is the largest single
 *     cause of gaps in the existing corpus, and an instance that is not the one
 *     capturing has no business pinning a laptop awake.
 * @param pollFailuresBeforeRed how many catalogue polls may fail in a row before
 *     {@code captureCoverage} turns over. Three at the fifteen-minute interval
 *     is three quarters of an hour of not knowing what is on, which is long
 *     enough that a single Betfair blip cannot fire it and short enough to catch
 *     a revoked key before an evening's kickoffs. Unlike every other red in that
 *     indicator this one is <b>not</b> gated on scope being non-empty: a poll
 *     that cannot run is exactly the case where the empty scope it leaves behind
 *     is not evidence of anything (#180).
 * @param lookahead how far ahead the forward-visibility poll looks. Days, not
 *     hours, and deliberately nothing to do with {@code horizon} — this one puts
 *     nothing into scope and is not sized by Betfair's 200-market cap. 48 hours
 *     covers "can I go out this evening" and "is tomorrow clear", which are the
 *     questions actually asked of it; a week would answer neither better.
 * @param lookaheadInterval how often to ask. A planning figure, not a control
 *     signal: a kickoff list does not move on a fifteen-minute timescale, and
 *     this costs two REST calls each time on top of discovery's own.
 * @param kickoffTimeout how long after kickoff a market may stay in scope
 *     regardless. The abandonment guard: it is what retires a fixture that was
 *     postponed after its market was created and therefore never went in-play,
 *     never closed, and would otherwise hold a slot forever.
 * @param marketsPerConnection how many markets one stream connection may carry.
 *     Betfair's default per subscription is 200, and a subscription above it is
 *     refused whole rather than truncated, which is why the planner trims to it
 *     rather than leaving it to the server. A setting because the account's
 *     limit can differ from the default (#64).
 * @param maxConnections how many stream connections scope may be partitioned
 *     across, each in its own connection slot. The 10 connections an app key
 *     allows are shared by every session on the key, whichever application
 *     opened it (#63), so this is raptor's share of that budget, not the key's
 *     whole allowance. Four since #67, leaving six for every other application
 *     on the account.
 */
@ConfigurationProperties("raptor.scope")
public record ScopeProperties(
		@DefaultValue("2h") Duration horizon,
		@DefaultValue("2h") Duration lateLookback,
		@DefaultValue("15m") Duration pollInterval,
		@DefaultValue("10s") Duration initialPollDelay,
		@DefaultValue("130m") Duration inPlayTimeout,
		@DefaultValue("6h") Duration kickoffTimeout,
		@DefaultValue("48h") Duration lookahead,
		@DefaultValue("30m") Duration lookaheadInterval,
		@DefaultValue("true") boolean holdPowerAssertion,
		@DefaultValue("3") int pollFailuresBeforeRed,
		@DefaultValue("200") int marketsPerConnection,
		@DefaultValue("4") int maxConnections) {

	public ScopeProperties {
		if (horizon.isNegative() || horizon.isZero()) {
			throw new IllegalArgumentException("the scope horizon must be positive");
		}
		if (lateLookback.isNegative()) {
			throw new IllegalArgumentException("the late-discovery lookback must not be negative");
		}
		if (inPlayTimeout.isNegative() || kickoffTimeout.isNegative()) {
			throw new IllegalArgumentException("scope guards must not be negative");
		}
		if (lookahead.compareTo(horizon) < 0) {
			// A lookahead inside the horizon can only ever repeat what scope already
			// knows, and the whole point of the figure is to see past it.
			throw new IllegalArgumentException(
					"the lookahead must reach at least as far as the scope horizon");
		}
		if (pollFailuresBeforeRed < 1) {
			// Zero would put the indicator red before a single poll had been tried.
			throw new IllegalArgumentException("pollFailuresBeforeRed must be at least 1");
		}
		if (marketsPerConnection < 1) {
			throw new IllegalArgumentException("marketsPerConnection must be at least 1");
		}
		if (maxConnections < 1) {
			// Zero connections would plan nothing and capture nothing, silently.
			throw new IllegalArgumentException("maxConnections must be at least 1");
		}
	}
}
