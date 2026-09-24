package com.stucray.raptor.screens;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Scope, gaps and source files, each read back through its endpoint.
 *
 * <p>Rows go in through the acquisition identity and come out through the read
 * identity, so every test is also a test that V35 covers what the screen reads:
 * a table the grant missed fails as a 500. Market ids are synthetic
 * ({@code 1.9xxxxxxxx}), per the no-Betfair-data rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Scope, gaps and source files are read from the ledger and raw")
class OperationalScreensTest {

    @Autowired MockMvc mvc;
    @Autowired @Acquisition JdbcClient jdbc;

    private long ledgerRunId;

    @BeforeEach
    void ledgerRun() {
        Long id = jdbc.sql("""
                insert into ledger.ledger_run (job_name, partition_key, job_execution_id)
                values ('test', 'all', 0) returning id""")
            .query(Long.class).single();
        ledgerRunId = id == null ? 0 : id;
    }

    /** A market whose kickoff was {@code hoursAgo} ago, in play from kickoff for two hours. */
    private void scoped(String marketId, String competition, boolean requested, String state,
                        long messages, int hoursAgo) {
        jdbc.sql("""
                insert into ledger.market_scope
                  (market_id, event_name, competition_name, market_type, country_code,
                   requested, state, exit_reason, kickoff, first_seen_at, state_changed_at,
                   in_play_since, messages, ledger_run_id)
                values
                  (:marketId, 'Home v Away', :competition, 'MATCH_ODDS', 'GB', :requested, :state,
                   case when :state = 'DONE' then 'CLOSED' end,
                   now() - make_interval(hours => :ago),
                   now() - make_interval(hours => :ago) - interval '4 hours',
                   case when :state = 'DONE'
                        then now() - make_interval(hours => :ago) + interval '2 hours'
                        else now() - make_interval(hours => :ago) - interval '4 hours' end,
                   case when :ago >= 0 then now() - make_interval(hours => :ago) end,
                   :messages, :run)""")
            .param("marketId", marketId)
            .param("competition", competition)
            .param("requested", requested)
            .param("state", state)
            .param("ago", hoursAgo)
            .param("messages", messages)
            .param("run", ledgerRunId)
            .update();
    }

    private void gap(long id, int startedHoursAgo, int seconds, String cause) {
        jdbc.sql("""
                insert into ledger.capture_gap
                  (id, session_id, started_at, ended_at, cause, ledger_run_id)
                values (:id, 1, now() - make_interval(hours => :ago),
                        now() - make_interval(hours => :ago) + make_interval(secs => :secs),
                        :cause, :run)""")
            .param("id", id)
            .param("ago", startedHoursAgo)
            .param("secs", seconds)
            .param("cause", cause)
            .param("run", ledgerRunId)
            .update();
    }

    @Test
    void openMarketsComeFirstSoonestKickoffFirstThenRecentlyFinished() throws Exception {
        scoped("1.900000001", "English Premier League", true, "DONE", 40_000, 20);
        scoped("1.900000002", "English Premier League", true, "PENDING", 0, -6);
        scoped("1.900000003", "English Premier League", true, "SUBSCRIBED", 0, -2);
        // Finished long before the window: not shown.
        scoped("1.900000004", "English Premier League", true, "DONE", 40_000, 24 * 10);

        mvc.perform(get("/api/scope/markets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].marketId")
                .value(contains("1.900000003", "1.900000002", "1.900000001")))
            .andExpect(jsonPath("$[2].exitReason").value("CLOSED"))
            .andExpect(jsonPath("$[2].messages").value(40_000));
    }

    /**
     * Control and target are counted apart: a control market that captured
     * nothing is not a lost fixture, and pooling them would bury one that is.
     */
    @Test
    void coverageSeparatesTargetFromControlAndCountsTheLost() throws Exception {
        scoped("1.900000001", "English Premier League", true, "DONE", 40_000, 20);
        scoped("1.900000002", "English Premier League", true, "DONE", 0, 22);
        scoped("1.900000003", "English Ladies League Cup", false, "DONE", 0, 22);

        mvc.perform(get("/api/scope/coverage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // Requested first.
            .andExpect(jsonPath("$[0].competition").value("English Premier League"))
            .andExpect(jsonPath("$[0].requested").value(true))
            .andExpect(jsonPath("$[0].markets").value(2))
            .andExpect(jsonPath("$[0].captured").value(1))
            .andExpect(jsonPath("$[0].lost").value(1))
            .andExpect(jsonPath("$[1].requested").value(false))
            .andExpect(jsonPath("$[1].lost").value(1));
    }

    /** What a gap cost is the markets in play across it, not its length. */
    @Test
    void eachGapSaysHowManyMarketsWereInPlayAcrossIt() throws Exception {
        // In play from 20h ago until 18h ago.
        scoped("1.900000001", "English Premier League", true, "DONE", 40_000, 20);
        gap(1, 19, 40, "SILENCE");
        gap(2, 200, 600, "SLEEP");

        mvc.perform(get("/api/gaps"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].durationMs").value(40_000))
            .andExpect(jsonPath("$[0].marketsInPlay").value(1))
            .andExpect(jsonPath("$[1].cause").value("SLEEP"))
            .andExpect(jsonPath("$[1].marketsInPlay").value(0));
    }

    @Test
    void sourceFilesSummariseWhatCustodyHolds() throws Exception {
        jdbc.sql("""
                insert into raw.football_file
                  (path, division, season, sha256, bytes, content, fetched_at, checked_at)
                values ('mmz4281/2526/E0.csv', 'E0', '2526', sha256('a'), 10, 'a',
                        timestamptz '2026-09-20T19:03:00Z', timestamptz '2026-09-23T23:30:00Z'),
                       ('mmz4281/2526/E0.csv', 'E0', '2526', sha256('b'), 12, 'b',
                        timestamptz '2026-09-21T19:03:00Z', timestamptz '2026-09-23T23:30:00Z')""")
            .update();
        jdbc.sql("""
                insert into raw.historic_file (path, sha256, bytes, messages)
                values ('synthetic/1.900000001.bz2', sha256('h'), 100, 7)""")
            .update();

        mvc.perform(get("/api/source-files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.source == 'football-data')].files").value(contains(1)))
            // A CSV republished in place is a second version of one file.
            .andExpect(jsonPath("$[?(@.source == 'football-data')].superseded").value(contains(1)))
            .andExpect(jsonPath("$[?(@.source == 'football-data')].lastArrived")
                .value(contains("2026-09-21T19:03:00Z")))
            .andExpect(jsonPath("$[?(@.source == 'football-data')].lastChecked")
                .value(contains("2026-09-23T23:30:00Z")))
            .andExpect(jsonPath("$[?(@.source == 'betfair-historic')].files").value(contains(1)))
            .andExpect(jsonPath("$[?(@.source == 'betfair-live')].files").value(contains(0)));
    }

    @Test
    void aSourcesFilesAreListedNewestFirstWithTheirDigest() throws Exception {
        jdbc.sql("""
                insert into raw.historic_file (path, sha256, bytes, messages, loaded_at)
                values ('synthetic/1.900000001.bz2', sha256('h'), 100, 7,
                        timestamptz '2026-09-05T06:27:00Z'),
                       ('synthetic/1.900000002.bz2', sha256('i'), 200, 9,
                        timestamptz '2026-09-05T06:28:00Z')""")
            .update();

        mvc.perform(get("/api/source-files/betfair-historic"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].path").value("synthetic/1.900000002.bz2"))
            .andExpect(jsonPath("$[0].messages").value(9))
            .andExpect(jsonPath("$[0].sha256").value(
                "de7d1b721a1e0632b7cf04edf5032c8ecffa9f9a08492152b926f1a5a7e765d7"));
    }

    /** Never interpolated: an unknown source is a 404, not a table name. */
    @Test
    void anUnknownSourceIsNotFound() throws Exception {
        mvc.perform(get("/api/source-files/raw.stream_message"))
            .andExpect(status().isNotFound());
    }
}
