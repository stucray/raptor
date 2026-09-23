package com.stucray.raptor.scope;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the recorder's scope current: what to capture, and when to stop.
 *
 * <p>This is what a capture <em>is</em> now. There is no window, no run length
 * and no fire hour: a fixture enters scope when its kickoff comes inside the
 * horizon and leaves when it is over, so a 20:00Z midweek tie and a 12:30Z
 * Saturday kickoff are both covered without subscribing to the hours between
 * them, and a fixture rescheduled at short notice is picked up on the next poll
 * rather than missed entirely.
 *
 * <p><b>The state machine is driven by the catalogue, not by the stream.</b>
 * The plan called for LIVE on the first message with {@code inPlay=true}, which
 * would require the write path to parse a message — and the one architectural
 * rule this system does not bend is that nothing which writes the system of
 * record may depend on a parse being right ({@code WritePathIsolationTest}).
 * Reading in-play from the catalogue costs granularity: a match that kicks off
 * just after a poll is not seen in-play for up to the poll interval, and
 * nothing downstream needs it sooner — {@code in_play_since} dates the in-play
 * guard and holds the machine awake, and neither is worth a minute.
 *
 * <p><b>In-play and on-the-wire are two facts, and #219 is what conflating them
 * cost.</b> The catalogue answers the first for every market in scope; only the
 * planner decides the second, and the 200-market cap means the answer is often
 * no. LIVE is the conjunction — on the wire, and in play — because that is what
 * the planner and the capture ledger both read it as. What a market did
 * regardless of whether it won a slot is on {@code in_play_since}.
 *
 * <p><b>Two upstream calls, not one.</b> Discovery finds what is coming;
 * following says what became of what was already found. They cannot be the same
 * call, because discovery filters on a start-time window and a market leaves
 * that window the moment it kicks off — which is exactly when its state starts
 * to matter. Conflating them was #127: for two nights every market in scope
 * left by the six-hour abandonment guard, and no market ever reached LIVE or was
 * ever seen to close.
 *
 * <p>A poll that fails leaves scope exactly as it was. That is the safe
 * direction: the failure modes of a stale scope are a market subscribed a
 * little too long, while the failure mode of retiring scope on a failed poll is
 * a live match dropped because a REST call timed out.
 */
@Service
class MarketScopeService implements CaptureScope, ScopeDiscovery {

	private static final Logger log = LoggerFactory.getLogger(MarketScopeService.class);

	private final ObjectProvider<MarketCatalogue> catalogues;
	private final MarketScopes scopes;
	private final SubscriptionPlanner planner;
	private final PowerAssertion power;
	private final ScopeProperties properties;
	private final Clock clock;

	private final AtomicInteger inScope = new AtomicInteger();
	private final AtomicInteger live = new AtomicInteger();

	/**
	 * Consecutive polls that threw. The swallow below is deliberate and stays;
	 * this is what stops it being silent (#180).
	 */
	private final AtomicInteger pollFailures = new AtomicInteger();

	/**
	 * When a poll last found anything at all — from startup until one does,
	 * because it is the observation and not the ledger that a restart resets.
	 */
	private volatile Instant scopeLastNonEmpty;

	/**
	 * When the CURRENT run of non-empty scope began, or null while there is
	 * nothing in scope.
	 *
	 * <p><b>Not the same question as {@link #scopeLastNonEmpty} above, and #278
	 * is what the difference costs.</b> That one is a recency — it moves forward
	 * on every poll that finds something, so while a card is on it is never more
	 * than one poll old. This one is a streak, and it only moves when scope goes
	 * from empty to non-empty. A reader asking "how long has this been going
	 * wrong while there was something to capture" needs the streak; given the
	 * recency it would get at most the poll interval, and a stuck recorder would
	 * be visible only in the few minutes before each poll reset the number.
	 */
	private volatile @Nullable Instant scopeNonEmptySince;

	MarketScopeService(ObjectProvider<MarketCatalogue> catalogues, MarketScopes scopes,
			SubscriptionPlanner planner, PowerAssertion power, ScopeProperties properties,
			Clock clock, MeterRegistry meters) {
		this.catalogues = catalogues;
		this.scopes = scopes;
		this.planner = planner;
		this.power = power;
		this.properties = properties;
		this.clock = clock;
		this.scopeLastNonEmpty = clock.instant();

		Gauge.builder("raptor.scope.pollFailures", pollFailures, AtomicInteger::get)
				.description("Catalogue polls that have thrown in a row; zero after any that completes")
				.register(meters);
		Gauge.builder("raptor.scope.markets", inScope, AtomicInteger::get)
				.description("Markets currently in scope: pending, subscribed or live")
				.register(meters);
		Gauge.builder("raptor.scope.live", live, AtomicInteger::get)
				.description("Markets the catalogue last reported in-play")
				.register(meters);
	}

	/**
	 * <b>The first poll is immediate, and the interval only applies afterwards.</b>
	 * Sharing the initial delay with the interval left a restarted recorder blind
	 * for fifteen minutes — it holds the lease, reports that nothing is in scope,
	 * and does not subscribe — which on a Saturday evening is fifteen minutes of
	 * matches. The Python reads its ids at startup and refreshes on the interval
	 * afterwards; this is the same shape, and one extra catalogue call per restart
	 * is not what Betfair's rate limits are about.
	 */
	@Scheduled(initialDelayString = "${raptor.scope.initial-poll-delay:10s}",
			fixedDelayString = "${raptor.scope.poll-interval:15m}")
	void poll() {
		try {
			if (!refresh().isEmpty()) {
				this.scopeLastNonEmpty = clock.instant();
				// Only on the transition: this is when the card started, and a
				// poll that finds the same twenty markets again has not restarted
				// it. See the field.
				if (this.scopeNonEmptySince == null) {
					this.scopeNonEmptySince = clock.instant();
				}
			} else {
				// Scope has emptied, so nothing is owed a reaction any more and
				// the next fixture starts its own clock.
				this.scopeNonEmptySince = null;
			}
			pollFailures.set(0);
		} catch (RuntimeException e) {
			// Scope is left exactly as it was. A REST failure must not retire a
			// match in progress — and the swallow is why the count exists: it is
			// the only thing outside this method that can tell a quiet Tuesday from
			// a catalogue nobody can reach (#180).
			log.warn("scope poll failed ({} in a row); scope is unchanged",
					pollFailures.incrementAndGet(), e);
		}
	}

	/**
	 * <b>Facts, not a verdict.</b> Only the poll-failure count is unambiguous
	 * enough to gate on: a name that does not resolve is routine mid-week, and an
	 * empty scope is routine every night. The judgement is
	 * {@code CaptureCoverageHealthIndicator}'s, and it is deliberately made where
	 * the recorder's state is visible too.
	 */
	@Override
	public DiscoveryReport discovery() {
		MarketCatalogue catalogue = catalogues.getIfAvailable();
		MarketCatalogue.LeagueResolution resolved = catalogue == null
				? MarketCatalogue.LeagueResolution.NONE
				: catalogue.resolution();
		Instant filled = this.scopeNonEmptySince;
		return new DiscoveryReport(pollFailures.get(), resolved.configured(),
				resolved.unresolved(),
				Duration.between(this.scopeLastNonEmpty, clock.instant()),
				// ZERO, not null, and it means the same thing a reader wants it to
				// mean: nothing has been waiting to be captured for any length of
				// time. A failed poll leaves scope alone and so leaves this alone
				// too, which is why a night of failing polls does not silently
				// stop the dwell clock it feeds.
				filled == null ? Duration.ZERO : Duration.between(filled, clock.instant()));
	}

	/**
	 * One pass: read the catalogue, move what it answers, retire what has run out.
	 *
	 * @return the markets still in scope afterwards
	 */
	List<ScopedMarket> refresh() {
		MarketCatalogue catalogue = catalogues.getIfAvailable();
		if (catalogue == null) {
			// Honest state of this application until the live client lands: nothing
			// to poll, so nothing in scope, and no pretence otherwise.
			return scopes.open();
		}
		// One market can arrive from BOTH queries, and routinely does: the control
		// set is a country filter, and a requested league's markets are in that
		// country too. Upserting each sighting as it arrives would let the control
		// query overwrite `requested` to false moments after the league query set
		// it true — and the planner's whole first rule is that the control set may
		// never crowd out a requested league. So the poll is folded first, and
		// requested wins wherever the two disagree.
		Map<String, CatalogueMarket> markets = new LinkedHashMap<>();
		Set<String> requested = new HashSet<>();
		for (MarketCatalogue.CatalogueQuery query : catalogue.poll(properties.horizon())) {
			for (CatalogueMarket market : query.markets()) {
				markets.putIfAbsent(market.marketId(), market);
				if (query.requested()) {
					requested.add(market.marketId());
				}
			}
		}
		Set<String> seen = new HashSet<>(markets.keySet());
		for (CatalogueMarket market : markets.values()) {
			scopes.seen(market, requested.contains(market.marketId()));
			apply(market.marketId(), market.inPlay(), market.status());
		}
		Set<String> answered = follow(catalogue, seen);
		retire(seen, answered);
		List<ScopedMarket> open = scopes.open();
		inScope.set(open.size());
		// Counted from the in-play CLOCK rather than from the LIVE state, and the
		// difference is #219: LIVE now means "on the wire and in play", so a
		// fixture the 200-market cap could not admit is PENDING throughout its
		// match. Narrowing the awake window to the markets that happen to have won
		// a slot is the one consequence of that fix nobody would want — if the
		// planner takes a control market back mid-half, the machine must already
		// be awake. `in_play_since` is sticky exactly as LIVE was, so this is the
		// count the assertion had before, held by a column that cannot be trimmed.
		int inPlay = (int) open.stream().filter(m -> m.inPlaySince() != null).count();
		live.set(inPlay);
		// The one side effect this poll has outside the database. It belongs here
		// because in-play is decided here: a match in progress is precisely the
		// interval during which a sleeping machine costs book.
		power.covering(inPlay);
		return open;
	}

	/**
	 * Ask the upstream directly about the markets discovery no longer returns.
	 *
	 * <p><b>This is the whole of #127.</b> Discovery filters on a start-time
	 * window, so a market leaves it the instant it kicks off — which is the
	 * instant its state begins to matter. Without this call the only exit a
	 * market can ever take is the six-hour abandonment guard, and that is
	 * precisely what the first two nights of resident capture recorded: 76
	 * markets scoped, 76 retired by {@code KICKOFF_ELAPSED}, and {@code LIVE},
	 * {@code CLOSED} and {@code SETTLED} never reached once.
	 *
	 * <p><b>A wider window would not have done it.</b> A settled market has no
	 * start time inside any window, however far back it reaches; following by id
	 * is the only route that survives the close.
	 *
	 * @param seen the markets discovery answered for this poll, which are already
	 *     up to date and are not asked about twice
	 * @return the markets the upstream answered for, which {@link #retire} needs
	 *     in order to tell "withdrawn" from "merely started"
	 */
	private Set<String> follow(MarketCatalogue catalogue, Set<String> seen) {
		List<String> following = scopes.open().stream()
				.map(ScopedMarket::marketId)
				.filter(id -> !seen.contains(id))
				.toList();
		if (following.isEmpty()) {
			return Set.of();
		}
		Set<String> answered = new HashSet<>();
		for (MarketCatalogue.MarketState state : catalogue.follow(following)) {
			answered.add(state.marketId());
			apply(state.marketId(), state.inPlay(), state.status());
		}
		return answered;
	}

	/** What the upstream said about one market this poll, from either call. */
	private void apply(String marketId, boolean inPlay, @Nullable String status) {
		if ("CLOSED".equals(status)) {
			scopes.done(marketId, ScopeExit.CLOSED);
			return;
		}
		if ("SETTLED".equals(status)) {
			scopes.done(marketId, ScopeExit.SETTLED);
			return;
		}
		if (inPlay) {
			scopes.live(marketId);
		}
	}

	/**
	 * Retire what this poll did not answer for.
	 *
	 * <p>Two different things happen here, and conflating them would lose a
	 * match. A market <b>already being recorded</b> is never retired for being
	 * absent from the catalogue — absence there says nothing about a live
	 * subscription, and the guards below are what end it. A market still only
	 * PENDING, on the other hand, has nothing invested in it: if the catalogue
	 * has stopped listing it, it was withdrawn or moved, and a row saying so
	 * beats waiting six hours to conclude the same thing. If it comes back, the
	 * next poll resurrects it.
	 *
	 * <p><b>Unless the upstream answered for it anyway.</b> Since #127 a market
	 * absent from discovery is asked about by id, and an answer means it exists —
	 * it has merely left the start-time window, which every market does the
	 * moment it kicks off. Retiring one of those as OUT_OF_HORIZON would drop a
	 * fixture in the minutes between its kickoff and Betfair turning it in-play.
	 *
	 * @param seen markets discovery returned
	 * @param answered markets the follow-up book answered for
	 */
	private void retire(Set<String> seen, Set<String> answered) {
		Instant now = clock.instant();
		for (ScopedMarket market : scopes.open()) {
			if (market.state() == ScopeState.PENDING && !seen.contains(market.marketId())
					&& !answered.contains(market.marketId())) {
				scopes.done(market.marketId(), ScopeExit.OUT_OF_HORIZON);
				continue;
			}
			ScopeExit guard = expired(market, now);
			if (guard != null) {
				log.info("market {} retired by the {} guard", market.marketId(), guard);
				scopes.done(market.marketId(), guard);
			}
		}
	}

	/**
	 * Whether a guard has run out for this market.
	 *
	 * <p>The guards are what make scope self-clearing. Betfair's cap is 200
	 * markets, so a handful of fixtures held open forever — abandoned, voided, or
	 * simply never closed cleanly — is a Saturday's worth of capacity gone, with
	 * nothing anywhere saying why.
	 */
	private @Nullable ScopeExit expired(ScopedMarket market, Instant now) {
		Instant inPlaySince = market.inPlaySince();
		if (inPlaySince != null
				&& Duration.between(inPlaySince, now).compareTo(properties.inPlayTimeout()) > 0) {
			return ScopeExit.IN_PLAY_ELAPSED;
		}
		Instant kickoff = market.kickoff();
		if (kickoff != null
				&& Duration.between(kickoff, now).compareTo(properties.kickoffTimeout()) > 0) {
			return ScopeExit.KICKOFF_ELAPSED;
		}
		return null;
	}

	@Override
	public SubscriptionPlan plan() {
		return planner.plan(scopes.open());
	}

	/**
	 * Record that these markets, and no others, are in the subscription the
	 * server accepted.
	 *
	 * <p>Called after the subscribe, not before: a market marked SUBSCRIBED that
	 * the server refused would be protected from the trim by the very tier that
	 * exists to protect markets actually being recorded.
	 *
	 * <p><b>The list replaces the previous one rather than adding to it</b>,
	 * because that is what {@code marketSubscription} does — a market trimmed out
	 * of this plan has left the wire, and recording only the entries is what let
	 * the ledger claim in-play capture of markets nothing was listening to
	 * (#219).
	 */
	@Override
	public void subscribed(List<String> marketIds) {
		scopes.subscribed(marketIds);
	}
}
