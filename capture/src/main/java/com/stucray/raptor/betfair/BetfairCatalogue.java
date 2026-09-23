package com.stucray.raptor.betfair;

import com.stucray.raptor.scope.CatalogueMarket;
import com.stucray.raptor.scope.MarketCatalogue;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/**
 * Scope's view of Betfair, assembled from three REST calls.
 *
 * <p><b>Why three.</b> {@code listCompetitions} turns the configured league
 * NAMES into this season's ids; {@code listMarketCatalogue} finds the markets
 * inside the horizon; {@code listMarketBook} supplies the only thing the
 * catalogue does not carry — whether a market is in-play and whether it is
 * still open. Captured responses on 2026-09-02 confirm the split: a catalogue
 * node has {@code marketId}, {@code event}, {@code competition},
 * {@code description} and no state at all.
 *
 * <p><b>{@code listMarketBook} is called for two different reasons.</b> Once to
 * attach state to what discovery just found, and once — {@link #follow} — for
 * markets already in scope that discovery can no longer see. The second is not
 * an optimisation of the first: it is the only route to a market that has
 * kicked off.
 *
 * <p><b>Why two catalogue queries.</b> {@code MarketFilter} ANDs its fields,
 * and UEFA fixtures carry no {@code countryCode}, so a single filter combining
 * competitions and countries returns their intersection: nothing. Requested
 * competitions and the control countries are therefore asked separately and
 * unioned, with the requested side marked so the planner can refuse to let the
 * control set crowd it out.
 *
 * <p><b>Market type comes from {@code description.marketType}.</b> Without the
 * {@code MARKET_DESCRIPTION} projection the response carries only
 * {@code marketName} — "Over/Under 2.5 Goals" — and re-deriving
 * {@code OVER_UNDER_25} from a display label is the menu-label-versus-canonical
 * -code mistake that cost PRD #115 a re-cut slice. Verified against a real
 * capture, which is also why the projection list is not shorter.
 */
@Component
class BetfairCatalogue implements MarketCatalogue {

	private static final Logger log = LoggerFactory.getLogger(BetfairCatalogue.class);

	private static final DateTimeFormatter WINDOW =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC);

	/** Betfair's page cap for one catalogue query. */
	private static final int MAX_RESULTS = 200;

	private static final ParameterizedTypeReference<List<Map<String, Object>>> NODES =
			new ParameterizedTypeReference<>() {};

	private final BetfairRest rest;
	private final CaptureSelection selection;
	private final BetfairProperties properties;
	private final java.time.Clock clock;

	private boolean saidItHasNoCredentials;

	/** Written by {@link #resolve} on the poll thread, read by health on another. */
	private volatile LeagueResolution resolution = LeagueResolution.NONE;

	BetfairCatalogue(BetfairRest rest, CaptureSelection selection, BetfairProperties properties,
			java.time.Clock clock) {
		this.rest = rest;
		this.selection = selection;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public List<CatalogueQuery> poll(Duration horizon) {
		if (!properties.configured()) {
			// Said once, not every fifteen minutes. A developer running the read
			// side without `sops exec-env` is the ordinary case, not a fault, and a
			// warning on a timer is how a log stops being read.
			if (!saidItHasNoCredentials) {
				log.info("no Betfair credentials in this process; scope will stay empty "
						+ "(start under `sops exec-env` — see bin/up)");
				saidItHasNoCredentials = true;
			}
			return List.of();
		}
		CaptureSelection.Selection asked = selection.current();
		if (asked.isEmpty()) {
			return List.of();
		}
		Map<String, String> window = window(horizon);
		List<CatalogueQuery> queries = new ArrayList<>();

		Resolved resolved = resolve(asked.leagues());
		// Published here and not inside resolve(), because the lookahead query
		// below resolves the same names on its own schedule and must not overwrite
		// what `resolution()` is documented to mean: the last DISCOVERY poll.
		this.resolution = new LeagueResolution(asked.leagues().size(), resolved.missing());
		if (!resolved.missing().isEmpty()) {
			log.warn("{} of {} competition name(s) did not resolve and are not being captured: {}",
					resolved.missing().size(), asked.leagues().size(), resolved.missing());
		}
		List<String> competitionIds = resolved.ids();
		if (!competitionIds.isEmpty()) {
			queries.add(new CatalogueQuery(true,
					markets(Origin.REQUESTED, Map.of("competitionIds", competitionIds),
							asked.marketTypes(), window)));
		}
		if (!asked.controlCountries().isEmpty()) {
			queries.add(new CatalogueQuery(false,
					markets(Origin.CONTROL, Map.of("marketCountries", asked.controlCountries()),
							asked.marketTypes(), window)));
		}
		return queries;
	}

	/**
	 * Markets already in scope, followed by id rather than by start time.
	 *
	 * <p>Deliberately <b>does not</b> go through {@code listMarketCatalogue}. The
	 * discovery filter above pins {@code marketStartTime} to
	 * {@code [now, now + horizon]}, so a market leaves the catalogue's answer the
	 * moment it kicks off and is never seen again — which is why every market this
	 * recorder has scoped was retired by the six-hour abandonment guard, and why
	 * {@code LIVE}, {@code CLOSED} and {@code SETTLED} had never once been
	 * observed (#127).
	 *
	 * <p>{@code listMarketBook} has no such window. Asked for four of the previous
	 * night's market ids on 2026-09-04 it answered for all four with
	 * {@code status=CLOSED} and {@code inplay=true}, a day after their kickoffs.
	 */
	@Override
	public List<MarketState> follow(Collection<String> marketIds) {
		if (!properties.configured() || marketIds.isEmpty()) {
			return List.of();
		}
		return state(List.copyOf(marketIds)).entrySet().stream()
				.map(e -> new MarketState(e.getKey(), e.getValue().inPlay(), e.getValue().status()))
				.toList();
	}

	@Override
	public LeagueResolution resolution() {
		return this.resolution;
	}

	/**
	 * League names to this season's competition ids.
	 *
	 * <p>A name that resolves to nothing is <b>recorded and skipped</b>, never
	 * fatal. {@code listCompetitions} only returns competitions that currently
	 * have markets, so a league between rounds simply is not there — and the
	 * version of this code that exited instead cost a full matchday on
	 * 2026-08-31, taking the other seven leagues and the control set with it.
	 *
	 * <p>Resolving <em>nothing</em> is different, and is left to the caller to
	 * notice: that is a wrong app key or a config whose every name is wrong, not
	 * an international break.
	 */
	private Resolved resolve(List<String> names) {
		if (names.isEmpty()) {
			return Resolved.NONE;
		}
		List<Map<String, Object>> nodes = rest.post("listCompetitions/",
				Map.of("filter", Map.of("eventTypeIds", List.of("1"))), NODES);
		Map<String, List<String>> byName = new HashMap<>();
		for (Map<String, Object> node : nodes) {
			Map<?, ?> competition = map(node.get("competition"));
			Object name = competition.get("name");
			Object id = competition.get("id");
			if (name != null && id != null) {
				byName.computeIfAbsent(name.toString(), key -> new ArrayList<>()).add(id.toString());
			}
		}
		List<String> ids = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (String name : names) {
			List<String> hits = byName.getOrDefault(name, List.of());
			if (hits.size() == 1) {
				ids.add(hits.getFirst());
			} else {
				missing.add(name + (hits.isEmpty() ? "" : " (ambiguous: " + hits + ")"));
			}
		}
		// Reported as well as logged. The log line distinguishes a league between
		// rounds from a league whose name has changed only to somebody reading the
		// container log at the time; the report reaches health, and health is what
		// reaches ntfy (#180). The caller decides whether to publish it.
		return new Resolved(List.copyOf(ids), List.copyOf(missing));
	}

	/** What {@link #resolve} found, separated from what gets published about it. */
	private record Resolved(List<String> ids, List<String> missing) {

		static final Resolved NONE = new Resolved(List.of(), List.of());
	}

	/**
	 * The earliest kickoff ahead, asked for directly rather than searched for.
	 *
	 * <p><b>{@code FIRST_TO_START} with one result, not a wide window paged
	 * through.</b> A lookahead of days over four market types is thousands of
	 * markets, and {@code listMarketCatalogue} returns one page — so taking a
	 * minimum client-side would be a minimum over whichever markets the page
	 * happened to hold, which is the one way of getting this figure that fails
	 * silently and in the dangerous direction. Sorting upstream makes the page
	 * cap irrelevant: the first row is the answer.
	 *
	 * <p>Both selections are asked and the earlier wins, because scope opens for
	 * a control market exactly as it does for a target fixture.
	 *
	 * <p>No credentials, or nothing selected, is not an error and not a kickoff:
	 * this process is not going to capture anything either way, and the caller
	 * reports that it does not know rather than that the week is clear.
	 */
	@Override
	public @Nullable Instant nextKickoff(Duration lookahead) {
		if (!properties.configured()) {
			return null;
		}
		CaptureSelection.Selection asked = selection.current();
		if (asked.isEmpty()) {
			return null;
		}
		Map<String, String> window = window(lookahead);
		Instant earliest = null;
		List<String> competitionIds = resolve(asked.leagues()).ids();
		if (!competitionIds.isEmpty()) {
			earliest = earlier(earliest,
					firstToStart(Map.of("competitionIds", competitionIds),
							asked.marketTypes(), window));
		}
		if (!asked.controlCountries().isEmpty()) {
			earliest = earlier(earliest,
					firstToStart(Map.of("marketCountries", asked.controlCountries()),
							asked.marketTypes(), window));
		}
		return earliest;
	}

	private static @Nullable Instant earlier(@Nullable Instant a, @Nullable Instant b) {
		if (a == null) {
			return b;
		}
		return b == null || a.isBefore(b) ? a : b;
	}

	private @Nullable Instant firstToStart(Map<String, Object> extra, List<String> marketTypes,
			Map<String, String> window) {
		Map<String, Object> filter = new LinkedHashMap<>();
		filter.put("eventTypeIds", List.of("1"));
		filter.put("marketTypeCodes", marketTypes);
		filter.put("turnInPlayEnabled", true);
		filter.put("marketStartTime", window);
		filter.putAll(extra);

		List<Map<String, Object>> nodes = rest.post("listMarketCatalogue/",
				Map.of("filter", filter, "maxResults", "1", "sort", "FIRST_TO_START",
						"marketProjection", List.of("MARKET_START_TIME")),
				NODES);
		return nodes.stream()
				.map(node -> instant(node.get("marketStartTime")))
				.filter(start -> start != null)
				.findFirst()
				.orElse(null);
	}

	/**
	 * Which of the two catalogue queries this is, and how loud its page cap is.
	 *
	 * <p>The helper is shared, and the two callers could not differ more when it
	 * fills a page. The control set is filler that {@code SubscriptionPlanner}
	 * trims anyway, so markets past the page were never going to be subscribed —
	 * INFO. The requested set at the cap is target-league fixtures that are never
	 * <em>discovered</em>, so nothing downstream can report them missing: the
	 * planner only logs what it drops, and it never saw these. That is the same
	 * invisible-absence shape as #122, #141 and #144, and it is a WARN.
	 */
	private enum Origin {

		REQUESTED("requested competitions", true),
		CONTROL("control countries", false);

		private final String describe;
		private final boolean loud;

		Origin(String describe, boolean loud) {
			this.describe = describe;
			this.loud = loud;
		}
	}

	/** One catalogue query, with the state each market is in attached. */
	private List<CatalogueMarket> markets(Origin origin, Map<String, Object> extra,
			List<String> marketTypes, Map<String, String> window) {
		Map<String, Object> filter = new LinkedHashMap<>();
		filter.put("eventTypeIds", List.of("1"));
		filter.put("marketTypeCodes", marketTypes);
		filter.put("turnInPlayEnabled", true);
		filter.put("marketStartTime", window);
		filter.putAll(extra);

		List<Map<String, Object>> nodes = rest.post("listMarketCatalogue/",
				Map.of("filter", filter, "maxResults", String.valueOf(MAX_RESULTS),
						"marketProjection",
						List.of("EVENT", "COMPETITION", "MARKET_START_TIME", "MARKET_DESCRIPTION")),
				NODES);
		if (nodes.size() >= MAX_RESULTS) {
			// One page and no pagination, so "at the cap" and "over the cap" are
			// indistinguishable from here — which is why the requested side says
			// markets "may have been" lost rather than guessing either way.
			if (origin.loud) {
				log.warn("catalogue query for {} filled the {}-market page — target-league "
						+ "markets may have been silently lost, and nothing downstream can "
						+ "report them missing because they were never discovered; narrow the "
						+ "market types or the horizon", origin.describe, MAX_RESULTS);
			} else {
				log.info("catalogue query for {} filled the {}-market page; the surplus is "
						+ "filler the planner trims anyway", origin.describe, MAX_RESULTS);
			}
		}
		Map<String, State> state = state(nodes.stream().map(n -> string(n.get("marketId")))
				.filter(id -> id != null).toList());
		return nodes.stream().map(node -> market(node, state)).filter(m -> m != null).toList();
	}

	/**
	 * The state half, from {@code listMarketBook}.
	 *
	 * <p>Absence is not an error: a market the book does not answer for is
	 * carried through as open and not in-play, which is what a catalogue-only
	 * view would have said anyway. Scope's guards are what end such a market, so
	 * a missing book row delays a decision rather than making a wrong one.
	 */
	private Map<String, State> state(List<String> marketIds) {
		if (marketIds.isEmpty()) {
			return Map.of();
		}
		Map<String, State> states = new HashMap<>();
		// Betfair's own limit for this call. Chunked rather than assumed: a
		// Saturday's horizon is comfortably more than one page.
		int chunk = 100;
		for (int from = 0; from < marketIds.size(); from += chunk) {
			List<String> page = marketIds.subList(from, Math.min(from + chunk, marketIds.size()));
			List<Map<String, Object>> nodes = rest.post("listMarketBook/",
					Map.of("marketIds", page, "priceProjection", Map.of("priceData", List.of())),
					NODES);
			for (Map<String, Object> node : nodes) {
				String marketId = string(node.get("marketId"));
				if (marketId != null) {
					states.put(marketId, new State(Boolean.TRUE.equals(node.get("inplay")),
							string(node.get("status"))));
				}
			}
		}
		return states;
	}

	private static @Nullable CatalogueMarket market(Map<String, Object> node,
			Map<String, State> states) {
		String marketId = string(node.get("marketId"));
		Map<?, ?> description = map(node.get("description"));
		String marketType = string(description.get("marketType"));
		if (marketId == null || marketType == null) {
			// Without an id there is nothing to subscribe to, and without the
			// canonical type there is nothing to record it as. Neither is worth
			// guessing from the display name.
			return null;
		}
		Map<?, ?> event = map(node.get("event"));
		Map<?, ?> competition = map(node.get("competition"));
		State state = states.getOrDefault(marketId, State.UNKNOWN);
		return new CatalogueMarket(marketId, string(event.get("id")), string(event.get("name")),
				string(competition.get("id")), string(competition.get("name")), marketType,
				string(event.get("countryCode")), instant(node.get("marketStartTime")),
				state.inPlay(), state.status());
	}

	private Map<String, String> window(Duration horizon) {
		Instant now = clock.instant();
		return Map.of("from", WINDOW.format(now), "to", WINDOW.format(now.plus(horizon)));
	}

	private static Map<?, ?> map(@Nullable Object value) {
		return value instanceof Map<?, ?> map ? map : Map.of();
	}

	private static @Nullable String string(@Nullable Object value) {
		return value == null ? null : value.toString();
	}

	private static @Nullable Instant instant(@Nullable Object value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value.toString());
		} catch (RuntimeException e) {
			// A market whose start time will not parse is still a market. Scope
			// sorts it last and its kickoff guard simply never fires.
			log.warn("unparseable marketStartTime {}", value);
			return null;
		}
	}

	private record State(boolean inPlay, @Nullable String status) {
		static final State UNKNOWN = new State(false, null);
	}
}
