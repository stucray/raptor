package com.stucray.raptor.scope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * How much there is to capture, beside what the recorder is doing about it.
 *
 * <p>Published for one question the recorder's own state cannot answer on its
 * own: <b>is this normal?</b> A recorder saying IDLE is correct when nothing is
 * in scope and is an incident when eight markets are LIVE, and until #144 the
 * only way to tell was to open a psql session. The two contributors sit in the
 * same {@code capture} group, so one curl answers both halves.
 *
 * <p>The health screen shows these same counts, and this is not that: the
 * screen reads {@code ledger.market_scope}, which is a projection refreshed on a
 * timer, and this reads the live ledger the recorder is subscribing from. A
 * count that trails by five minutes is the right answer for a morning's
 * browsing and the wrong one for the endpoint an operator curls at kickoff.
 *
 * <p>Reported here rather than as a detail on the recorder because scope is not
 * the recorder's to know: the recorder asks {@link CaptureScope} what to
 * subscribe to and reports what it did, and a wider surface would invite it to
 * start making scope decisions of its own.
 *
 * <p><b>Always UP.</b> An empty horizon is the correct state of a Tuesday
 * morning, and a contributor that reads red when nothing is wrong is how a
 * signal stops being read. The verdict on the pair — markets in scope, nothing
 * capturing them — belongs to {@code captureCoverage}, which can see both
 * halves; this one only counts.
 */
@Component
class ScopeHealthIndicator implements HealthIndicator {

	private final ScopeCensus census;
	private final KickoffLookahead lookahead;
	private final Clock clock;

	ScopeHealthIndicator(ScopeCensus census, KickoffLookahead lookahead, Clock clock) {
		this.census = census;
		this.lookahead = lookahead;
		this.clock = clock;
	}

	@Override
	public Health health() {
		ScopeSummary scope = census.summarise();
		Health.Builder health = Health.up()
				.withDetail("marketsInScope", scope.marketsInScope())
				.withDetail("pending", scope.pending())
				.withDetail("subscribed", scope.subscribed())
				.withDetail("live", scope.live());
		Instant next = scope.nextKickoff();
		if (next != null) {
			// The one fact that makes an empty horizon legible rather than merely
			// empty: nothing in scope and a kickoff in forty minutes is a stream
			// about to open, where nothing in scope and no kickoff at all is a
			// catalogue poll worth looking at.
			health.withDetail("nextKickoff", next.toString())
					.withDetail("secondsToNextKickoff",
							Duration.between(clock.instant(), next).toSeconds());
		}
		health.withDetail("lookahead", lookahead());
		return health.build();
	}

	/**
	 * Forward visibility, in its own block and under its own names.
	 *
	 * <p>Nested deliberately. {@code nextKickoff} above means "the earliest
	 * kickoff ALREADY IN SCOPE" and is absent exactly when someone is asking how
	 * long they have got; this one means "the earliest kickoff there is". Two
	 * fields a keystroke apart meaning different things is how a reader takes the
	 * wrong one, and the reader here is deciding whether to shut a laptop lid on
	 * a live card (#265).
	 *
	 * <p>{@code known} is the field that must be read first. False means nobody
	 * has been able to ask — a restart, no credentials, or a poll that has never
	 * completed — and is not the same as {@code nextKickoff} being absent, which
	 * means the window really is clear.
	 */
	private Map<String, Object> lookahead() {
		KickoffForecast forecast = this.lookahead.current();
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("known", forecast.known());
		details.put("stale", this.lookahead.stale());
		details.put("windowSeconds", this.lookahead.window().toSeconds());
		details.put("consecutiveFailures", forecast.consecutiveFailures());
		Instant measured = forecast.measuredAt();
		if (measured != null) {
			details.put("measuredAt", measured.toString());
			details.put("measuredSecondsAgo",
					Duration.between(measured, clock.instant()).toSeconds());
		}
		Instant next = forecast.nextKickoff();
		if (next != null) {
			details.put("nextKickoff", next.toString());
			details.put("secondsToNextKickoff",
					Duration.between(clock.instant(), next).toSeconds());
		}
		Duration opens = this.lookahead.untilScopeOpens();
		if (opens != null) {
			// The figure the lid check actually wants: scope opens a horizon before
			// kickoff, and that subtraction belongs to whatever owns the horizon.
			details.put("secondsUntilScopeOpens", opens.toSeconds());
		}
		return details;
	}
}
