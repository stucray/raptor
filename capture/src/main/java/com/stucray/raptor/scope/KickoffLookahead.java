package com.stucray.raptor.scope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How long there is before the next thing to capture — the question scope
 * cannot answer about itself.
 *
 * <p>Capture runs on a laptop that gets carried around, and the mitigation for
 * that is behavioural: shut the lid only when nothing is in scope.
 * {@code scope.marketsInScope} supports the check well and supports the
 * <em>plan</em> not at all, because a fixture enters scope four hours before
 * kickoff and paddock knows nothing about it until it does. So "is it safe
 * now?" could be asked and "can I go out for three hours?" could not — the
 * wrong shape for an operator who is usually away before kickoff (#265).
 *
 * <p><b>Its own query, not a wider horizon.</b> {@code ScopeProperties.horizon}
 * decides what gets subscribed and is what keeps a Saturday inside Betfair's
 * 200-market cap; widening it to answer a planning question would change the
 * capture. This asks {@link MarketCatalogue#nextKickoff} over its own window
 * and puts nothing into scope.
 *
 * <p><b>Slow on purpose.</b> A kickoff list does not move on a fifteen-minute
 * timescale, and nothing in the capture path may come to depend on this: every
 * watcher in this application is a {@code @Scheduled} bean, and a stopped
 * process reports nothing at all.
 *
 * <p><b>A failed poll keeps the last answer and says so.</b> It does not reset
 * to "clear" — see {@link KickoffForecast}.
 */
@Component
class KickoffLookahead {

	private static final Logger log = LoggerFactory.getLogger(KickoffLookahead.class);

	private final ObjectProvider<MarketCatalogue> catalogues;
	private final ScopeProperties properties;
	private final Clock clock;

	private volatile KickoffForecast forecast = KickoffForecast.UNKNOWN;

	KickoffLookahead(ObjectProvider<MarketCatalogue> catalogues, ScopeProperties properties,
			Clock clock) {
		this.catalogues = catalogues;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * The first poll is immediate for the same reason discovery's is: a restart
	 * an hour before someone walks out of the door must not leave them without an
	 * answer for half of it.
	 */
	@Scheduled(initialDelayString = "${raptor.scope.initial-poll-delay:10s}",
			fixedDelayString = "${raptor.scope.lookahead-interval:30m}")
	void poll() {
		MarketCatalogue catalogue = catalogues.getIfAvailable();
		if (catalogue == null) {
			// No source configured at all. Not a failure and not a quiet week: this
			// process was never going to capture anything, and saying "unknown" is
			// the honest answer rather than "clear".
			return;
		}
		try {
			Instant next = catalogue.nextKickoff(properties.lookahead());
			this.forecast = new KickoffForecast(next, clock.instant(), 0);
		} catch (RuntimeException e) {
			KickoffForecast previous = this.forecast;
			int failures = previous.consecutiveFailures() + 1;
			this.forecast = new KickoffForecast(previous.nextKickoff(), previous.measuredAt(),
					failures);
			// WARN and not ERROR: this is a planning figure, and losing it costs an
			// operator a convenience rather than costing the corpus anything. The
			// count is what stops it being silent.
			log.warn("could not read the kickoff lookahead ({} in a row); the published figure "
					+ "is the last good one", failures, e);
		}
	}

	/** The last answer, and how good it is. Never null. */
	KickoffForecast current() {
		return this.forecast;
	}

	/** How far ahead the figure looks, so a reader can say what "clear" covers. */
	Duration window() {
		return properties.lookahead();
	}

	/**
	 * Whether the published figure is too old to act on.
	 *
	 * <p>Decided here rather than by whoever reads it. The threshold is a
	 * multiple of the poll interval, which is a property of this application — a
	 * shell script comparing an age against a number it hardcoded would be a
	 * second copy of a rule that can be changed in a config file, and would go
	 * quietly wrong the moment the interval did.
	 *
	 * <p>Three intervals, not one: a single missed poll is a Betfair blip, and a
	 * forward-visibility figure that cried stale every time one request timed out
	 * would be ignored by the time it mattered.
	 */
	boolean stale() {
		Instant measured = this.forecast.measuredAt();
		if (measured == null) {
			return true;
		}
		return Duration.between(measured, clock.instant())
				.compareTo(properties.lookaheadInterval().multipliedBy(3)) > 0;
	}

	/**
	 * How long until the next kickoff brings something into scope, or null when
	 * that is not known.
	 *
	 * <p>Computed here rather than left to the caller. It is the horizon
	 * subtracted from the wait, and the horizon is a property of this
	 * application — a shell script deriving it from two published numbers would
	 * be a second copy of a rule that can be changed in a config file.
	 *
	 * <p>Zero, not negative: a kickoff already inside the horizon means scope is
	 * open or opens on the next discovery poll, and "minus twenty minutes" is not
	 * an answer to "how long have I got".
	 */
	@Nullable Duration untilScopeOpens() {
		Instant next = this.forecast.nextKickoff();
		if (next == null || !this.forecast.known()) {
			return null;
		}
		Duration until =
				Duration.between(clock.instant(), next).minus(properties.horizon());
		return until.isNegative() ? Duration.ZERO : until;
	}
}
