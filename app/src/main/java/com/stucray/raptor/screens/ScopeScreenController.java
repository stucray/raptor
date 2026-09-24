package com.stucray.raptor.screens;

import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What capture was asked to watch, and how much of it it got — from
 * {@code ledger.market_scope} only (paddock#321).
 *
 * <p>The labels here (event, competition, country) are Betfair's own, stored
 * verbatim when the market entered scope. Nothing is parsed out of a message
 * to produce them, and nothing is mapped: a competition shows under the name
 * Betfair gave it, which is exactly what a season rollover needs to be visible
 * as.
 */
@RestController
@RequestMapping("/api/scope")
class ScopeScreenController {

    private final JdbcClient jdbc;

    ScopeScreenController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every market still in scope, and those that finished within the last
     * {@code days}, soonest kickoff first for the open ones and most recent
     * first for the finished.
     */
    @GetMapping("/markets")
    List<ScopedMarketView> markets(@RequestParam(defaultValue = "2") int days,
                                   @RequestParam(defaultValue = "500") int limit) {
        return jdbc.sql("""
                select market_id, event_name, competition_name, market_type,
                       country_code, kickoff, requested, state, exit_reason,
                       first_seen_at, state_changed_at, in_play_since, messages
                from ledger.market_scope
                where state <> 'DONE'
                   or state_changed_at >= now() - make_interval(days => :days)
                order by (state = 'DONE'),
                         case when state = 'DONE' then null else kickoff end asc nulls last,
                         kickoff desc nulls last,
                         market_id
                limit :limit""")
            .param("days", days)
            .param("limit", limit)
            .query((rs, i) -> new ScopedMarketView(
                rs.getString("market_id"),
                rs.getString("event_name"),
                rs.getString("competition_name"),
                rs.getString("market_type"),
                rs.getString("country_code"),
                rs.getObject("kickoff", OffsetDateTime.class),
                rs.getBoolean("requested"),
                rs.getString("state"),
                rs.getString("exit_reason"),
                rs.getObject("first_seen_at", OffsetDateTime.class),
                rs.getObject("state_changed_at", OffsetDateTime.class),
                rs.getObject("in_play_since", OffsetDateTime.class),
                rs.getLong("messages")))
            .list();
    }

    /**
     * Per competition, over markets whose kickoff fell in the last {@code days}:
     * how many were scoped, how many finished, and how many of those finished
     * with nothing captured.
     *
     * <p>Requested and control markets are kept apart rather than summed. A
     * control market that produced nothing is not a lost fixture — the health
     * summary makes the same distinction — and pooling them would hide exactly
     * the target-league loss the programme exists to avoid.
     */
    @GetMapping("/coverage")
    List<CoverageRow> coverage(@RequestParam(defaultValue = "7") int days) {
        return jdbc.sql("""
                select coalesce(competition_name, '(no competition)') as competition,
                       requested,
                       count(*)                                          as markets,
                       count(*) filter (where state = 'DONE')            as done,
                       count(*) filter (where state = 'DONE' and messages > 0) as captured,
                       count(*) filter (where state = 'DONE' and messages = 0) as lost,
                       sum(messages)                                     as messages
                from ledger.market_scope
                where kickoff >= now() - make_interval(days => :days)
                group by 1, 2
                order by requested desc, lost desc, competition""")
            .param("days", days)
            .query((rs, i) -> new CoverageRow(
                rs.getString("competition"),
                rs.getBoolean("requested"),
                rs.getInt("markets"),
                rs.getInt("done"),
                rs.getInt("captured"),
                rs.getInt("lost"),
                rs.getLong("messages")))
            .list();
    }

    /**
     * @param requested one of the configured target leagues, rather than a
     *     control market
     * @param inPlaySince when Betfair first reported the market in play, or null
     *     if it never was while scoped
     * @param messages stream messages captured for it, from the ledger
     */
    record ScopedMarketView(
        String marketId,
        @Nullable String eventName,
        @Nullable String competitionName,
        String marketType,
        @Nullable String countryCode,
        @Nullable OffsetDateTime kickoff,
        boolean requested,
        String state,
        @Nullable String exitReason,
        OffsetDateTime firstSeenAt,
        OffsetDateTime stateChangedAt,
        @Nullable OffsetDateTime inPlaySince,
        long messages) {}

    /**
     * @param lost finished with no message captured — for a requested
     *     competition, the loudest thing this screen can say
     */
    record CoverageRow(
        String competition,
        boolean requested,
        int markets,
        int done,
        int captured,
        int lost,
        long messages) {}
}
