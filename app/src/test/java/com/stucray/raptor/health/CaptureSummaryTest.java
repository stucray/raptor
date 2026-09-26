package com.stucray.raptor.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one-glance capture verdict, after S10 moved it off the schedule.
 *
 * <p>It used to ask "has a run started since the last due fire hour". That was
 * the right question while launchd fired a 12-hour window every night — a night
 * that never ran left no failed row to notice, so the absence of a run WAS the
 * alarm. S6 abolished the run and S8 unloads launchd, after which nothing fires
 * at any hour and the old rule would go on returning a confident answer about a
 * thing that no longer happens.
 *
 * <p>So it asks about fixtures: a market that entered scope, was subscribed,
 * finished and produced no messages was not captured. Everything below is
 * written in those terms.
 *
 * <p>Rows are seeded through the <b>acquisition</b> identity. The read side
 * holds {@code select} on the ledger and nothing that writes, which is the
 * boundary working rather than a test problem.
 */
@SpringBootTest(properties = {
    // The shipped config, not a fixture: the panel shows intent beside outcome,
    // so the test should fail if the file the recorder runs on stops parsing.
    "raptor.capture.config-file=../config/capture.properties"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Capture summary: what is in scope, and what was missed")
class CaptureSummaryTest {

    @Autowired MockMvc mvc;
    @Autowired @Acquisition JdbcClient jdbc;

    private long ledgerRunId;

    @BeforeEach
    void resetLedger() {
        Long id = jdbc.sql("""
                insert into ledger.ledger_run (job_name, partition_key, job_execution_id)
                values ('test', 'all', 0) returning id""")
            .query(Long.class).single();
        ledgerRunId = id == null ? 0 : id;
    }

    /**
     * Start times are relative to now, never pinned instants: a fixture pinned
     * to a date can quietly flip its verdict as it ages and fail on a day
     * nobody touched the code.
     *
     * <p>Half an hour past the hour, deliberately (#154). The row is written with
     * PostgreSQL's clock and {@code hoursSinceLatestStart} is measured with the
     * JVM's, truncated to whole hours — so a session started exactly N hours ago
     * reports N or N-1 depending on which clock is a millisecond ahead, and the
     * database runs in a VM whose clock drifts against the host. Sitting between
     * two hour boundaries gives thirty minutes of margin in both directions and
     * costs the tests nothing: none of them cares about the half hour, and every
     * window they do assert on is hours wide.
     */
    private void session(long id, int startedHoursAgo, @Nullable String exitStatus) {
        jdbc.sql("""
                insert into ledger.capture_session
                  (session_id, source_key, started_at, ended_at, origin, exit_status,
                   build_version, config_json, markets, messages, first_message_at,
                   last_message_at, gap_count, gap_total_ms, max_gap_ms, gaps_sleep,
                   gaps_silence, gaps_disconnect, ledger_run_id)
                values
                  (:id, null, now() - make_interval(hours => :ago, mins => 30),
                   -- A session with no exit status has not ended, so it has no
                   -- ended_at either. Setting one anyway would make every
                   -- fixture here a session that somehow finished without
                   -- saying how, which is the one shape the ledger cannot have.
                   case when cast(:exitStatus as text) is null then null
                        else now() - make_interval(hours => :ago, mins => 30)
                             + interval '6 hours' end,
                   'RESIDENT', :exitStatus, 'test', '{}'::jsonb, 64, 767587,
                   null, null, 0, 0, 0, 0, 0, 0, :projection)""")
            .param("id", id)
            .param("ago", startedHoursAgo)
            .param("exitStatus", exitStatus)
            .param("projection", ledgerRunId)
            .update();
    }

    /**
     * A session with both ends given in minutes, for the intervals BETWEEN two
     * of them: the hour-granular helper above can only ever produce a
     * zero-length interval, which is the one length a restart never has.
     */
    private void sessionSpanning(long id, int startedMinutesAgo, int endedMinutesAgo) {
        jdbc.sql("""
                insert into ledger.capture_session
                  (session_id, source_key, started_at, ended_at, origin, exit_status,
                   build_version, config_json, markets, messages, first_message_at,
                   last_message_at, gap_count, gap_total_ms, max_gap_ms, gaps_sleep,
                   gaps_silence, gaps_disconnect, ledger_run_id)
                values
                  (:id, null, now() - make_interval(mins => :started),
                   now() - make_interval(mins => :ended), 'RESIDENT', 'COMPLETED',
                   'test', '{}'::jsonb, 64, 767587, null, null, 0, 0, 0, 0, 0, 0,
                   :projection)""")
            .param("id", id)
            .param("started", startedMinutesAgo)
            .param("ended", endedMinutesAgo)
            .param("projection", ledgerRunId)
            .update();
    }

    private void scoped(String marketId, boolean requested, String state,
                        long messages, int kickoffHoursAgo) {
        jdbc.sql("""
                insert into ledger.market_scope
                  (market_id, market_type, requested, state, exit_reason, kickoff,
                   first_seen_at, state_changed_at, in_play_since, messages,
                   ledger_run_id)
                values
                  (:marketId, 'MATCH_ODDS', :requested, :state,
                   case when :state = 'DONE' then 'CLOSED' end,
                   now() - make_interval(hours => :ago),
                   now() - make_interval(hours => :ago) - interval '4 hours',
                   now() - make_interval(hours => :ago) + interval '2 hours',
                   now() - make_interval(hours => :ago),
                   :messages, :projection)""")
            .param("marketId", marketId)
            .param("requested", requested)
            .param("state", state)
            .param("ago", kickoffHoursAgo)
            .param("messages", messages)
            .param("projection", ledgerRunId)
            .update();
    }

    private void gap(long id, int startedHoursAgo, int minutes) {
        jdbc.sql("""
                insert into ledger.capture_gap
                  (id, session_id, started_at, ended_at, cause, ledger_run_id)
                values (:id, 1, now() - make_interval(hours => :ago),
                        now() - make_interval(hours => :ago)
                            + make_interval(mins => :mins),
                        'SILENCE', :projection)""")
            .param("id", id)
            .param("ago", startedHoursAgo)
            .param("mins", minutes)
            .param("projection", ledgerRunId)
            .update();
    }

    @Test
    void theLatestSessionIsTheNewestOneWithWhatItReceived() throws Exception {
        session(1, 33, "COMPLETED");
        session(2, 9, "COMPLETED");

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latest.sessionId").value(2))
            .andExpect(jsonPath("$.latest.messages").value(767587))
            .andExpect(jsonPath("$.latest.markets").value(64))
            .andExpect(jsonPath("$.hoursSinceLatestStart").value(9))
            .andExpect(jsonPath("$.recentFailures").value(0))
            .andExpect(jsonPath("$.lostFixtures").value(0));
    }

    /**
     * The signal that replaces "no run has started".
     *
     * <p>The old rule could only ever say a RUN had not started. It could not
     * say that a run started, ran all night, and missed a fixture — which is
     * the failure anybody actually cared about.
     */
    @Test
    void aFixtureThatWasWatchedAndProducedNothingIsTheAlarm() throws Exception {
        session(1, 9, "COMPLETED");
        scoped("1.captured", true, "DONE", 40_000, 20);
        scoped("1.silent", true, "DONE", 0, 18);
        // A control market that produced nothing is not a lost fixture: it was
        // never what the programme was for, and counting it would put a
        // permanent number on the alarm nobody would then look at.
        scoped("1.control", false, "DONE", 0, 18);

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lostFixtures").value(1));
    }

    /** A fixture still in scope has not missed anything yet. */
    @Test
    void aFixtureStillInScopeIsNotYetLost() throws Exception {
        session(1, 1, null);
        scoped("1.live", true, "LIVE", 0, 0);

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lostFixtures").value(0))
            .andExpect(jsonPath("$.scope.live").value(1));
    }

    /**
     * A gap costs something only if it happened while a market was in play.
     *
     * <p>The distinction the old ledger's three disagreeing gap columns were
     * guessing at from outside, answered from the intervals instead.
     */
    @Test
    void onlyGapsThatOverlappedPlayAreCounted() throws Exception {
        session(1, 9, "COMPLETED");
        // In play from kickoff+2h until it finished, 20 hours ago.
        scoped("1.match", true, "DONE", 40_000, 20);
        // In play from 20h ago until 18h ago, so 19h ago is inside it and 18h
        // ago is exactly its edge — the boundary is not what this test is about.
        gap(1, 19, 5);
        gap(2, 200, 60); // days earlier, and much longer

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gapsDuringPlay").value(1));
    }

    /**
     * Play starts at kickoff, not at the poll that noticed it (#16) — for the
     * gap count and the restart count alike, which share the rule.
     *
     * <p>Here the catalogue poll marks the match in play ten minutes after the
     * whistle, as it does routinely (on 2026-09-25, eight minutes). Both
     * interruptions fall between the two: before #16 each ended before
     * {@code in_play_since} and counted as nothing.
     */
    @Test
    void interruptionsBeforeTheFirstInPlayPollStillCountAsDuringPlay() throws Exception {
        scoped("1.match", true, "DONE", 40_000, 20);
        jdbc.sql("""
                update ledger.market_scope
                set in_play_since = kickoff + interval '10 minutes'
                where market_id = '1.match'""").update();
        // A gap from kickoff+2m to kickoff+7m.
        jdbc.sql("""
                insert into ledger.capture_gap
                  (id, session_id, started_at, ended_at, cause, ledger_run_id)
                values (1, 1, now() - interval '20 hours' + interval '2 minutes',
                        now() - interval '20 hours' + interval '7 minutes',
                        'SLEEP', :projection)""")
            .param("projection", ledgerRunId)
            .update();
        // And a restart: stopped at kickoff+3m, back at kickoff+5m.
        sessionSpanning(1, 21 * 60, 20 * 60 - 3);
        sessionSpanning(2, 20 * 60 - 5, 12 * 60);

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gapsDuringPlay").value(1))
            .andExpect(jsonPath("$.restartsDuringPlay").value(1));
    }

    /**
     * The interruption that leaves no evidence in the gap ledger, and is an
     * interruption anyway.
     *
     * <p>An orderly shutdown writes no gap row on purpose — {@code ended_at}
     * already says when recording stopped — so a {@code bin/up} during the
     * second half scored zero until #145. Counted from the space between two
     * sessions instead, and counted <b>separately</b>: a gap the recorder
     * suffered is weather, and a restart is somebody's decision.
     */
    @Test
    void aRestartWhileAMarketWasInPlayIsCountedThoughItLeavesNoGapRow() throws Exception {
        // In play from 20h ago until 18h ago.
        scoped("1.match", true, "DONE", 40_000, 20);
        // Stopped 19h ago and started again five minutes later — inside play.
        sessionSpanning(1, 20 * 60, 19 * 60);
        sessionSpanning(2, 19 * 60 - 5, 12 * 60);
        // And a restart hours after the match settled, which costs nothing.
        sessionSpanning(3, 5 * 60, 4 * 60);
        sessionSpanning(4, 4 * 60 - 5, 60);

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restartsDuringPlay").value(1))
            // The whole point: nothing wrote a gap row for either restart.
            .andExpect(jsonPath("$.gapsDuringPlay").value(0));
    }

    /**
     * A session that was killed has no {@code ended_at}, so the interval after
     * it has no beginning. Not counted, deliberately: that absence is #129's
     * subject, and inventing a beginning would be guessing at the moment the
     * evidence stops.
     */
    @Test
    void aKilledSessionIsNotCountedAsARestart() throws Exception {
        scoped("1.match", true, "DONE", 40_000, 20);
        session(1, 20, null);
        sessionSpanning(2, 19 * 60, 12 * 60);

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restartsDuringPlay").value(0));
    }

    @Test
    void whatIsInScopeIsReportedByState() throws Exception {
        session(1, 1, null);
        scoped("1.a", true, "PENDING", 0, -4);
        scoped("1.b", true, "SUBSCRIBED", 0, -2);
        scoped("1.c", true, "LIVE", 900, 0);
        scoped("1.d", true, "DONE", 40_000, 3);

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope.pending").value(1))
            .andExpect(jsonPath("$.scope.subscribed").value(1))
            .andExpect(jsonPath("$.scope.live").value(1))
            .andExpect(jsonPath("$.scope.doneRecently").value(1))
            .andExpect(jsonPath("$.scope.nextKickoff").exists());
    }

    /**
     * A killed session cannot write down that it was killed, so the ledger has
     * to conclude it. Single-writer is what makes that sound: an older session
     * with no ending, succeeded by a newer one, cannot still be running (#118).
     */
    @Test
    void aSessionWithNoEndingThatALaterOneSucceededWasKilled() throws Exception {
        session(1, 9, null);
        session(2, 2, null);

        mvc.perform(get("/api/health/capture-sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].sessionId").value(2))
            .andExpect(jsonPath("$[0].abandoned").value(false))
            .andExpect(jsonPath("$[1].sessionId").value(1))
            .andExpect(jsonPath("$[1].abandoned").value(true));
    }

    /**
     * Every figure in the summary is read from the ledger, so the summary says
     * when the ledger was last rebuilt: a refresh that has stopped would
     * otherwise show a frozen picture with full confidence.
     */
    @Test
    void theSummarySaysWhenTheLedgerWasLastRebuilt() throws Exception {
        jdbc.sql("""
                update ledger.ledger_run
                set completed_at = timestamptz '2026-09-20T19:03:00Z'
                where id = :id""")
            .param("id", ledgerRunId)
            .update();

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ledgerRefreshedAt").value("2026-09-20T19:03:00Z"));
    }

    @Test
    void anEmptyLedgerHasNoLatestSession() throws Exception {
        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latest").doesNotExist())
            .andExpect(jsonPath("$.hoursSinceLatestStart").doesNotExist())
            .andExpect(jsonPath("$.scope.live").value(0));
    }

    @Test
    void onlyEndingsNobodyChoseCountAsFailures() throws Exception {
        // COMPLETED ran out; INTERRUPTED was stopped cleanly. FAILED died;
        // TRUNCATED just stops. A session still RUNNING has not ended at all,
        // which the launchd ledger could not represent — a run was written
        // down only once it was over.
        session(1, 40, "COMPLETED");
        session(2, 30, "INTERRUPTED");
        session(3, 20, "FAILED");
        session(4, 10, "TRUNCATED");
        session(5, 1, null);

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recentFailures").value(2))
            .andExpect(jsonPath("$.latest.sessionId").value(5));
    }

    @Test
    void theSummaryLooksNoFurtherBackThanTheRecentWindow() throws Exception {
        // Eight sessions, oldest a failure: it falls outside the 7-session
        // window and must not keep the screen shouting about an old one.
        session(99, 200, "FAILED");
        for (int i = 7; i >= 1; i--) {
            session(i, i * 2, "COMPLETED");
        }

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recentFailures").value(0));
    }

    @Test
    void theSummaryCarriesWhatCaptureIsConfiguredToDo() throws Exception {
        session(1, 9, "COMPLETED");

        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured.leagues.length()").value(8))
            .andExpect(jsonPath("$.configured.marketTypes.length()").value(4))
            .andExpect(jsonPath("$.configured.controlCountries[0]").value("GB"))
            // No schedule: nothing fires at any hour after S8, so the panel
            // shows what capture records and not when.
            .andExpect(jsonPath("$.configured.runHours").doesNotExist())
            .andExpect(jsonPath("$.configured.fireHourUtc").doesNotExist());
    }
}
