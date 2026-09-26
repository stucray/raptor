package com.stucray.raptor.health;

import com.stucray.raptor.capture.CaptureConfig;
import com.stucray.raptor.capture.CaptureConfigProvider;
import com.stucray.raptor.projection.PlayWindow;
import com.stucray.raptor.sources.SourceAdapter;
import com.stucray.raptor.sources.SourceAdapter.Freshness;
import com.stucray.raptor.sources.SourceRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collector health, read from capture's own records and nothing else: the
 * ledger, the {@code raw} file tables and Spring Batch's run history (PRD #309,
 * paddock#321).
 *
 * <p>Nothing here reads {@code query} and nothing imports a parser. That is the
 * point of the screen living in raptor: whether capture is running and what it
 * got must be answerable while overround-analysis is down, late, or mid-rebuild.
 * It runs as the read identity, which V35 lets see exactly the tables named
 * below and no payload.
 */
@RestController
@RequestMapping("/api/health")
class CollectorHealthController {

    /**
     * The ledger refresh runs every five minutes, which is 288 executions a day.
     * Listed with the other jobs it would bury every run anybody could want to
     * read, so the run list leaves it out; its recency is reported where it
     * matters instead, beside the summary it is the "as of" for.
     */
    static final String LEDGER_REFRESH_JOB = "projectCaptureLedgerJob";

    private final JdbcClient jdbc;
    private final SourceRegistry registry;
    private final CaptureConfigProvider captureConfig;

    CollectorHealthController(JdbcClient jdbc, SourceRegistry registry,
                              CaptureConfigProvider captureConfig) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.captureConfig = captureConfig;
    }

    /**
     * Health per registered source. The registry is the list — not the run
     * history — so a source that has never run once still appears, saying so,
     * instead of being invisible exactly when that matters most.
     */
    @GetMapping("/sources")
    List<SourceHealth> sources() {
        return registry.all().stream()
            .map(adapter -> {
                RunStats s = runStats(adapter);
                return new SourceHealth(
                    adapter.id(),
                    adapter.description(),
                    adapter.custodyPath(),
                    adapter.acquisition().owner(),
                    adapter.jobs(),
                    s.lastSuccessAt(),
                    s.lastStatus(),
                    s.totalRuns(),
                    s.failedRuns(),
                    lastDeliveredAt(adapter));
            })
            .toList();
    }

    private RunStats runStats(SourceAdapter adapter) {
        if (adapter.jobs().isEmpty()) {
            return RunStats.NEVER;
        }
        // `in (:jobs)`, not `= any (:jobs)`: a named collection parameter is
        // expanded into a list, and `any` needs an array.
        return jdbc.sql("""
                select max(e.end_time) filter (where e.status = 'COMPLETED') as last_success,
                       (array_agg(e.status order by e.job_execution_id desc))[1] as last_status,
                       count(*)                                               as total_runs,
                       count(*) filter (where e.status = 'FAILED')            as failed_runs
                from batch.batch_job_execution e
                join batch.batch_job_instance i using (job_instance_id)
                where i.job_name in (:jobs)""")
            .param("jobs", adapter.jobs())
            .query((rs, i) -> rs.getInt("total_runs") == 0 ? RunStats.NEVER
                : new RunStats(
                    batchTime(rs, "last_success"),
                    rs.getString("last_status"),
                    rs.getInt("total_runs"),
                    rs.getInt("failed_runs")))
            .single();
    }

    /**
     * When a source last delivered — receipt time, from capture's own records.
     *
     * <p>Table and column are interpolated because they cannot be bound as
     * parameters. Both come from the code-declared registry and never from a
     * request, which is what makes that safe.
     */
    private @Nullable OffsetDateTime lastDeliveredAt(SourceAdapter adapter) {
        Freshness freshness = adapter.freshness();
        if (freshness == null) {
            return null;
        }
        return jdbc.sql("select max(" + freshness.column() + ") from " + freshness.table())
            .query(OffsetDateTime.class).optional().orElse(null);
    }

    /**
     * @param lastStatus the newest run's status, or NEVER_RUN when none of the
     *     source's jobs has ever run
     */
    private record RunStats(
        @Nullable OffsetDateTime lastSuccessAt,
        @Nullable String lastStatus,
        int totalRuns,
        int failedRuns) {

        static final RunStats NEVER = new RunStats(null, "NEVER_RUN", 0, 0);
    }

    /**
     * What raptor's jobs did, newest first: loads, replays and archive fetches.
     * Replaces paddock's {@code /import-runs}, whose table recorded a spool
     * importer that has not existed since #94.
     */
    @GetMapping("/job-runs")
    List<JobRunView> jobRuns(@RequestParam(defaultValue = "25") int limit) {
        return jdbc.sql("""
                select e.job_execution_id, i.job_name, e.start_time, e.end_time,
                       e.status, e.exit_code, e.exit_message
                from batch.batch_job_execution e
                join batch.batch_job_instance i using (job_instance_id)
                where i.job_name <> :ledgerRefresh
                order by e.job_execution_id desc
                limit :limit""")
            .param("ledgerRefresh", LEDGER_REFRESH_JOB)
            .param("limit", limit)
            .query((rs, i) -> new JobRunView(
                rs.getLong("job_execution_id"),
                rs.getString("job_name"),
                batchTime(rs, "start_time"),
                batchTime(rs, "end_time"),
                rs.getString("status"),
                rs.getString("exit_code"),
                firstLine(rs.getString("exit_message"))))
            .list();
    }

    /**
     * Spring Batch writes {@code LocalDateTime.now()} into a column with no
     * zone, so a value means "local time in the JVM that wrote it" — and the JVM
     * that writes the rows that matter is the deployed container, which runs in
     * UTC. Measured, not assumed: on 2026-09-24 every {@code fetchArchiveJob}
     * start and end on the live database equalled the {@code timestamptz} in
     * {@code batch.close_out} for the same run to the millisecond.
     *
     * <p>So the zone is stated, not taken from whoever is reading. Taking it
     * from the reader was the first version of this, and a development JVM at
     * UTC+7 then labelled the container's 23:33Z as 23:33+07:00, seven hours
     * wrong — invisible in CI, which runs UTC. What remains is the converse: rows
     * a non-UTC development JVM wrote into its own database read shifted, which
     * costs a development screen and nothing else.
     */
    private static @Nullable OffsetDateTime batchTime(ResultSet rs, String column)
            throws SQLException {
        LocalDateTime utc = rs.getObject(column, LocalDateTime.class);
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC);
    }

    /**
     * An exit message is a whole stack trace when a job fails. The screen needs
     * the sentence that says what went wrong; the rest is in the log.
     */
    private static @Nullable String firstLine(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    /** How many sessions back the "recently" counts look. */
    private static final int RECENT_SESSIONS = 7;

    /**
     * The one-glance capture verdict, from the ledger.
     *
     * <p>It asks about fixtures, not about whether a run started: a market that
     * entered scope, was subscribed, reached DONE and produced <em>no
     * messages</em> was not captured. That is the failure anybody cares about,
     * and a resident recorder has no schedule a missing run could be judged
     * against.
     */
    @GetMapping("/capture-summary")
    CaptureSummary captureSummary() {
        List<CaptureSessionView> recent = captureSessions(RECENT_SESSIONS);
        Optional<CaptureConfig> config = captureConfig.current();
        CaptureSessionView latest = recent.isEmpty() ? null : recent.getFirst();
        Long hoursSince = latest == null ? null
            : Duration.between(latest.startedAt(), OffsetDateTime.now()).toHours();
        int failures = (int) recent.stream()
            // COMPLETED ran out and INTERRUPTED was stopped cleanly — both are
            // endings somebody chose. A null status is a session still running.
            .filter(s -> s.exitStatus() != null
                && !"COMPLETED".equals(s.exitStatus())
                && !"INTERRUPTED".equals(s.exitStatus()))
            .count();
        return new CaptureSummary(latest, hoursSince, scope(), lostFixtures(),
            gapsDuringPlay(), restartsDuringPlay(), failures, ledgerRefreshedAt(),
            config.map(CollectorHealthController::configured).orElse(null));
    }

    /**
     * When the ledger was last rebuilt from {@code raw}. Every count in the
     * summary is as of this moment, so a ledger that has stopped refreshing
     * would otherwise show a confidently frozen picture.
     */
    private @Nullable OffsetDateTime ledgerRefreshedAt() {
        return jdbc.sql("select max(completed_at) from ledger.ledger_run")
            .query(OffsetDateTime.class).optional().orElse(null);
    }

    /** What the recorder is trying to capture right now, and what it just finished. */
    private Scope scope() {
        return jdbc.sql("""
                select count(*) filter (where state = 'PENDING')    as pending,
                       count(*) filter (where state = 'SUBSCRIBED') as subscribed,
                       count(*) filter (where state = 'LIVE')       as live,
                       count(*) filter (where state = 'DONE'
                           and state_changed_at >= now() - interval '24 hours') as done_recently,
                       min(kickoff) filter (where state <> 'DONE')  as next_kickoff
                from ledger.market_scope""")
            .query((rs, i) -> new Scope(
                rs.getInt("pending"), rs.getInt("subscribed"), rs.getInt("live"),
                rs.getInt("done_recently"),
                rs.getObject("next_kickoff", OffsetDateTime.class)))
            .single();
    }

    /**
     * Fixtures that were asked for, were watched, finished, and produced
     * nothing.
     *
     * <p>Scoped to {@code requested}: a control market that produced nothing is
     * not a lost fixture, and counting it would put a permanent number on the
     * alarm nobody would then look at.
     */
    private int lostFixtures() {
        Integer lost = jdbc.sql("""
                select count(*) from ledger.market_scope
                where state = 'DONE' and requested and messages = 0
                  and kickoff >= now() - interval '7 days'""")
            .query(Integer.class).single();
        return lost == null ? 0 : lost;
    }

    /**
     * Recorded gaps that overlapped a market while it was in play. A gap at
     * 04:00 on a Tuesday costs nothing; the same gap in the second half is what
     * the whole programme exists to avoid, and only the overlap can tell them
     * apart.
     */
    private int gapsDuringPlay() {
        Integer gaps = jdbc.sql("""
                select count(*) from ledger.capture_gap g
                where exists (
                    select 1 from ledger.market_scope s
                    where %1$s
                      and g.started_at < case when s.state = 'DONE'
                              then s.state_changed_at else now() end
                      and g.ended_at > %2$s)""".formatted(PlayWindow.WENT_IN_PLAY, PlayWindow.STARTS))
            .query(Integer.class).single();
        return gaps == null ? 0 : gaps;
    }

    /**
     * Interruptions that left no gap row because the recorder was stopped
     * rather than dropped, measured against play (paddock#145).
     *
     * <p>An orderly shutdown deliberately writes no {@code capture_gap} row — the
     * session's {@code ended_at} already says when recording stopped — so the
     * interval between one session's end and the next one's start is read
     * instead. Counted separately from {@link #gapsDuringPlay()}: a gap the
     * recorder suffered is weather, and an interruption an operator caused is a
     * decision.
     *
     * <p>A session that was killed hard leaves no {@code ended_at}, so the
     * interval after it has no beginning and is not counted here. Inventing one
     * would be guessing at exactly the moment the evidence stops.
     */
    private int restartsDuringPlay() {
        Integer restarts = jdbc.sql("""
                with down as (
                    select ended_at as from_at,
                           lead(started_at) over (order by started_at) as to_at
                    from ledger.capture_session)
                select count(*) from down d
                where d.from_at is not null and d.to_at is not null
                  and d.to_at > d.from_at
                  and exists (
                    select 1 from ledger.market_scope s
                    where %1$s
                      and d.from_at < case when s.state = 'DONE'
                              then s.state_changed_at else now() end
                      and d.to_at > %2$s)""".formatted(PlayWindow.WENT_IN_PLAY, PlayWindow.STARTS))
            .query(Integer.class).single();
        return restarts == null ? 0 : restarts;
    }

    /**
     * @param scope what the recorder is trying to capture now
     * @param lostFixtures requested markets that finished having produced
     *     nothing, over the last week. The loudest state
     * @param gapsDuringPlay recorded gaps overlapping a market that was in play
     * @param restartsDuringPlay intervals between two sessions overlapping a
     *     market that was in play
     * @param recentFailures sessions that ended other than COMPLETED or
     *     INTERRUPTED, over the last {@value #RECENT_SESSIONS}
     * @param ledgerRefreshedAt when the ledger every figure above is read from
     *     was last rebuilt, or null if it never has been
     */
    record CaptureSummary(
        @Nullable CaptureSessionView latest,
        @Nullable Long hoursSinceLatestStart,
        Scope scope,
        int lostFixtures,
        int gapsDuringPlay,
        int restartsDuringPlay,
        int recentFailures,
        @Nullable OffsetDateTime ledgerRefreshedAt,
        @Nullable ConfiguredCapture configured) {}

    record Scope(
        int pending,
        int subscribed,
        int live,
        int doneRecently,
        @Nullable OffsetDateTime nextKickoff) {}

    /**
     * What capture is configured to record, shown beside what it did — so a
     * season rollover that quietly stopped resolving a competition is visible as
     * a difference rather than as an absence.
     */
    record ConfiguredCapture(
        List<String> leagues,
        List<String> marketTypes,
        List<String> controlCountries) {}

    private static ConfiguredCapture configured(CaptureConfig c) {
        return new ConfiguredCapture(c.leagues(), c.marketTypes(),
            c.controlCountries());
    }

    /**
     * The capture ledger: what the RECORDER did, session by session, most
     * recent first. A row is a session — a stream connection held by the
     * resident process — not a night.
     */
    @GetMapping("/capture-sessions")
    List<CaptureSessionView> captureSessions(@RequestParam(defaultValue = "25") int limit) {
        return jdbc.sql("""
                select session_id, source_key, started_at, ended_at, origin,
                       exit_status, exit_detail, build_version, markets, messages,
                       conflated, gap_count, gap_total_ms, max_gap_ms, gaps_sleep, gaps_silence,
                       gaps_disconnect,
                       -- A session with no ending that a LATER session succeeded
                       -- was killed: the recorder is single-writer, so an older
                       -- open session cannot still be running. This is the only
                       -- trace a SIGKILL leaves (paddock#118).
                       ended_at is null and exists (
                           select 1 from ledger.capture_session later
                           where later.started_at > s.started_at) as abandoned
                from ledger.capture_session s
                order by started_at desc
                limit :limit""")
            .param("limit", limit)
            .query((rs, i) -> new CaptureSessionView(
                rs.getLong("session_id"),
                rs.getString("source_key"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("ended_at", OffsetDateTime.class),
                rs.getString("origin"),
                rs.getString("exit_status"),
                rs.getString("exit_detail"),
                rs.getString("build_version"),
                rs.getInt("markets"),
                rs.getLong("messages"),
                rs.getLong("conflated"),
                rs.getInt("gap_count"),
                rs.getLong("gap_total_ms"),
                rs.getLong("max_gap_ms"),
                rs.getInt("gaps_sleep"),
                rs.getInt("gaps_silence"),
                rs.getInt("gaps_disconnect"),
                rs.getBoolean("abandoned")))
            .list();
    }

    /**
     * @param sourceKey the run key an IMPORTED session was identified by, and
     *     null for a session the resident recorder opened for itself
     * @param exitStatus null while a session is still running
     * @param conflated messages Betfair marked conflated although the
     *     subscription asked for none: not loss but summarisation, invisible to
     *     every gap count (paddock#132)
     * @param abandoned no ending, and a later session exists: the session was
     *     killed rather than stopped (paddock#118)
     */
    record CaptureSessionView(
        long sessionId,
        @Nullable String sourceKey,
        OffsetDateTime startedAt,
        @Nullable OffsetDateTime endedAt,
        String origin,
        @Nullable String exitStatus,
        @Nullable String exitDetail,
        String buildVersion,
        int markets,
        long messages,
        long conflated,
        int gapCount,
        long gapTotalMs,
        long maxGapMs,
        int gapsSleep,
        int gapsSilence,
        int gapsDisconnect,
        boolean abandoned) {}

    /**
     * @param jobs the jobs whose runs are counted for this source
     * @param lastSuccessAt when one of them last COMPLETED
     * @param lastStatus the newest run's status, or NEVER_RUN
     * @param lastDeliveredAt when capture last took in data for this source —
     *     receipt time, from capture's own records
     */
    record SourceHealth(
        String source,
        String description,
        String custodyPath,
        String acquisitionOwner,
        List<String> jobs,
        @Nullable OffsetDateTime lastSuccessAt,
        @Nullable String lastStatus,
        int totalRuns,
        int failedRuns,
        @Nullable OffsetDateTime lastDeliveredAt) {}

    /**
     * @param exitMessage the first line of Batch's exit message; the whole of it
     *     is a stack trace when a job fails
     */
    record JobRunView(
        long executionId,
        String jobName,
        @Nullable OffsetDateTime startedAt,
        @Nullable OffsetDateTime endedAt,
        String status,
        @Nullable String exitCode,
        @Nullable String exitMessage) {}
}
