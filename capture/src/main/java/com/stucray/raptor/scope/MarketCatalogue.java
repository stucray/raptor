package com.stucray.raptor.scope;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Betfair's REST catalogue, as scope needs it.
 *
 * <p>A port, and for the same reason {@code StreamSource} is one: the state
 * machine and the planner behind it are decisions worth testing exhaustively,
 * and nothing about those decisions should require a Betfair session, an app
 * key, or a Saturday.
 *
 * <p><b>Two calls, because the upstream answers two different questions.</b>
 * {@link #poll} discovers what is coming; {@link #follow} says what has become
 * of what was already found. Conflating them is what #127 was: discovery's
 * start-time window is the right filter for the first question and silently
 * wrong for the second.
 */
public interface MarketCatalogue {

	/**
	 * Markets of the captured types kicking off inside the horizon.
	 *
	 * <p>Implementations union two queries — requested competitions, then control
	 * countries — because {@code MarketFilter} ANDs its fields and UEFA fixtures
	 * carry no {@code countryCode} at all, so a single combined filter returns
	 * their intersection: nothing.
	 *
	 * @param horizon how far ahead to look
	 * @return every market found, requested and control alike; which is which is
	 *     {@link CatalogueQuery#requested()}
	 */
	List<CatalogueQuery> poll(Duration horizon);

	/**
	 * The earliest kickoff that will eventually enter scope, however far off.
	 *
	 * <p><b>A different question from {@link #poll}, and deliberately not a
	 * widened horizon.</b> The horizon is load-bearing for
	 * {@code SubscriptionPlanner}: it is what keeps a Saturday inside Betfair's
	 * 200-market cap, and widening it to answer a planning question would change
	 * what gets subscribed. So this asks its own query over its own window and
	 * puts nothing into scope.
	 *
	 * <p><b>Both selections, not just the requested leagues.</b> Scope opens for
	 * a control market exactly as it does for a target fixture, so a figure that
	 * looked only at the configured leagues would tell an operator they had six
	 * hours when the control set was about to open in one. This must answer for
	 * whatever {@code poll} would eventually return.
	 *
	 * <p>Implementations should ask the upstream to sort rather than paging a
	 * wide window and taking a minimum: a query at the page cap would silently
	 * omit the earliest market, which is the one being asked for.
	 *
	 * @param lookahead how far ahead to look, typically days rather than hours
	 * @return the earliest kickoff found, or null when the window holds none.
	 *     <b>Null means "nothing is on", never "the question could not be
	 *     answered"</b> — an implementation that cannot reach the upstream throws,
	 *     so that a caller reporting forward visibility can tell a quiet week from
	 *     a broken poll. Reporting "clear" for a failed query is the failure shape
	 *     of #122, #141 and #144, and here it would invite shutting the lid on a
	 *     live card.
	 */
	@Nullable Instant nextKickoff(Duration lookahead);

	/**
	 * The current state of markets already in scope, asked for by id.
	 *
	 * <p><b>Why this is not just another {@link #poll} result.</b> Discovery
	 * filters on {@code marketStartTime} inside the horizon, so a market drops
	 * out of it the instant it kicks off — which is exactly when its state starts
	 * mattering. Every market the recorder has ever scoped left by the six-hour
	 * abandonment guard for that reason (#127): {@code LIVE} was never reached,
	 * and {@code CLOSED} and {@code SETTLED} were never observed once.
	 *
	 * <p>Following by id is not a widened window. A window cannot be widened far
	 * enough, because a settled market has no start time inside any of them —
	 * confirmed against Betfair on 2026-09-04, where {@code listMarketBook}
	 * answered for four of the previous night's markets with
	 * {@code status=CLOSED, inplay=true} a full day after their kickoffs.
	 *
	 * <p>Absence is not an error, and not a retirement: a market the upstream
	 * declines to answer for is left exactly as it was, and the guards are what
	 * end it.
	 *
	 * @param marketIds the markets in scope that discovery did not return
	 * @return a state for each market the upstream answered for; ids it did not
	 *     answer for are simply missing
	 */
	List<MarketState> follow(Collection<String> marketIds);

	/**
	 * How the configured competition names fared on the most recent {@link #poll}.
	 *
	 * <p>On the port rather than on a {@link CatalogueQuery} because the worst
	 * case emits no query at all: an implementation that resolves nothing has no
	 * competition ids to ask about, so it skips the requested query entirely and
	 * the failure leaves no trace in the result. That is precisely the shape of a
	 * revoked key or a config whose every name is wrong (#180).
	 *
	 * @return the resolution of the last poll, or {@link LeagueResolution#NONE}
	 *     before the first one
	 */
	LeagueResolution resolution();

	/**
	 * What became of the configured league names.
	 *
	 * @param configured how many names the run asked for
	 * @param unresolved the ones that matched no single competition, verbatim.
	 *     Not an error on its own — {@code listCompetitions} lists only
	 *     competitions that currently have markets, so a league between rounds is
	 *     legitimately missing.
	 */
	record LeagueResolution(int configured, List<String> unresolved) {

		/** Nothing has been asked for yet. */
		public static final LeagueResolution NONE = new LeagueResolution(0, List.of());

		public LeagueResolution {
			unresolved = List.copyOf(unresolved);
		}
	}

	/**
	 * The two facts scope needs about a market it is already following.
	 *
	 * @param status the upstream's own word — {@code OPEN}, {@code SUSPENDED},
	 *     {@code CLOSED}, {@code SETTLED} — carried verbatim, so an unrecognised
	 *     value cannot cost the poll
	 */
	record MarketState(String marketId, boolean inPlay, @Nullable String status) {}

	/**
	 * One query's worth of catalogue.
	 *
	 * @param requested true for the leagues the run exists to record, false for
	 *     the control set. The planner never lets the second crowd out the first.
	 */
	record CatalogueQuery(boolean requested, List<CatalogueMarket> markets) {}
}
