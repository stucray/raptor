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
 * Every interval the recorder went without the stream, from
 * {@code ledger.capture_gap} (paddock#321).
 *
 * <p>The health summary counts gaps that overlapped play; this lists them, so
 * the count has something behind it. Each row says how many markets were in
 * play across it, because that — not the duration — is what a gap cost: ten
 * minutes at 04:00 on a Tuesday lost nothing, and forty seconds in a second
 * half lost the book for every market that was live.
 */
@RestController
@RequestMapping("/api/gaps")
class GapScreenController {

    private final JdbcClient jdbc;

    GapScreenController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Most recent first. */
    @GetMapping
    List<GapView> gaps(@RequestParam(defaultValue = "100") int limit) {
        // The overlap test is the health summary's, written once per row:
        // a market is in play from in_play_since until it left scope, or until
        // now if it has not.
        return jdbc.sql("""
                select g.id, g.session_id, g.started_at, g.ended_at, g.cause, g.detail,
                       (extract(epoch from (g.ended_at - g.started_at)) * 1000)::bigint as duration_ms,
                       (select count(*) from ledger.market_scope s
                        where s.in_play_since is not null
                          and g.started_at < case when s.state = 'DONE'
                                  then s.state_changed_at else now() end
                          and g.ended_at > s.in_play_since) as markets_in_play
                from ledger.capture_gap g
                order by g.started_at desc, g.id desc
                limit :limit""")
            .param("limit", limit)
            .query((rs, i) -> new GapView(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getLong("duration_ms"),
                rs.getString("cause"),
                rs.getString("detail"),
                rs.getInt("markets_in_play")))
            .list();
    }

    /**
     * @param cause what the recorder concluded the gap was: SLEEP, SILENCE or
     *     DISCONNECT
     * @param marketsInPlay markets in play at any point across the gap — zero
     *     means it cost nothing
     */
    record GapView(
        long id,
        long sessionId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        long durationMs,
        String cause,
        @Nullable String detail,
        int marketsInPlay) {}
}
