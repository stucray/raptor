package com.stucray.raptor.health;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Source health and job history, from capture's own records.
 *
 * <p>Rows are seeded through the acquisition identity and read back through the
 * endpoints, which run as the read identity. So every test here is also a test
 * that V35's grant covers what the screen reads: a table the grant missed fails
 * as a 500, not as a wrong number.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Source health and job runs are read from raw, the ledger and batch")
class CollectorHealthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired @Acquisition JdbcClient jdbc;

    private void run(long id, String jobName, int startedHoursAgo, String status,
                     @Nullable String exitMessage) {
        jdbc.sql("""
                insert into batch.batch_job_instance (job_instance_id, version, job_name, job_key)
                values (:id, 0, :job, md5(cast(:id as text)))""")
            .param("id", id)
            .param("job", jobName)
            .update();
        jdbc.sql("""
                insert into batch.batch_job_execution
                  (job_execution_id, version, job_instance_id, create_time, start_time,
                   end_time, status, exit_code, exit_message, last_updated)
                values (:id, 0, :id,
                        localtimestamp - make_interval(hours => :ago),
                        localtimestamp - make_interval(hours => :ago),
                        localtimestamp - make_interval(hours => :ago) + interval '1 minute',
                        :status, :status, :message, localtimestamp)""")
            .param("id", id)
            .param("ago", startedHoursAgo)
            .param("status", status)
            .param("message", exitMessage)
            .update();
    }

    @Test
    void everyRegisteredSourceIsListedWithItsContract() throws Exception {
        mvc.perform(get("/api/health/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[*].source")
                .value(contains("betfair-live", "football-data", "betfair-historic")))
            .andExpect(jsonPath("$[?(@.source == 'betfair-live')].acquisitionOwner")
                .value(contains("raptor's resident recorder (advisory capture lease)")))
            .andExpect(jsonPath("$[?(@.source == 'betfair-live')].custodyPath")
                .value(contains(endsWith("betfair-live/captured"))));
    }

    /** A source whose jobs have never run still appears, and says so. */
    @Test
    void aSourceThatHasNeverRunSaysSo() throws Exception {
        mvc.perform(get("/api/health/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.source == 'betfair-historic')].lastStatus")
                .value(contains("NEVER_RUN")))
            .andExpect(jsonPath("$[?(@.source == 'betfair-historic')].totalRuns")
                .value(contains(0)))
            .andExpect(jsonPath("$[?(@.source == 'betfair-historic')].lastDeliveredAt")
                .value(contains((Object) null)));
    }

    @Test
    void aSourcesRunsAreItsJobsExecutions() throws Exception {
        run(1, "fetchArchiveJob", 30, "COMPLETED", null);
        run(2, "fetchArchiveJob", 6, "FAILED", "java.io.IOException: 503\n\tat somewhere");
        // Another source's job, which must not be counted against this one.
        run(3, "loadHistoricCorpusJob", 3, "COMPLETED", null);

        mvc.perform(get("/api/health/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.source == 'football-data')].totalRuns").value(contains(2)))
            .andExpect(jsonPath("$[?(@.source == 'football-data')].failedRuns").value(contains(1)))
            // The newest run decides the status, not the newest success.
            .andExpect(jsonPath("$[?(@.source == 'football-data')].lastStatus")
                .value(contains("FAILED")))
            .andExpect(jsonPath("$[?(@.source == 'football-data')].lastSuccessAt").isNotEmpty());
    }

    /**
     * When a source last delivered is read from capture's records — here the
     * football file ledger — and not from anything overround-analysis projects.
     */
    @Test
    void lastDeliveredIsWhenCustodyLastTookInAFile() throws Exception {
        jdbc.sql("""
                insert into raw.football_file
                  (path, division, season, sha256, bytes, content, fetched_at)
                values ('mmz4281/2526/E0.csv', 'E0', '2526', sha256('x'), 1, 'x',
                        timestamptz '2026-09-20T19:03:00Z')""")
            .update();

        mvc.perform(get("/api/health/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.source == 'football-data')].lastDeliveredAt")
                .value(contains("2026-09-20T19:03:00Z")));
    }

    @Test
    void jobRunsAreNewestFirstWithTheFirstLineOfTheExitMessage() throws Exception {
        run(1, "fetchArchiveJob", 30, "COMPLETED", null);
        run(2, "fetchArchiveJob", 6, "FAILED", "java.io.IOException: 503\n\tat somewhere");

        mvc.perform(get("/api/health/job-runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].executionId").value(2))
            .andExpect(jsonPath("$[0].status").value("FAILED"))
            .andExpect(jsonPath("$[0].exitMessage").value("java.io.IOException: 503"))
            .andExpect(jsonPath("$[1].jobName").value("fetchArchiveJob"));
    }

    /**
     * Batch's columns carry no zone; the deployed JVM writes them in UTC, so
     * they are read as UTC whatever zone the READER runs in. Pinned to 19:03 so
     * a reader at any offset from UTC — the developer's +7 included — renders a
     * different string. CI runs UTC and cannot see this failing; a laptop can.
     */
    @Test
    void batchTimesAreReadAsUtcWhateverTheReadersZone() throws Exception {
        run(1, "fetchArchiveJob", 30, "COMPLETED", null);
        jdbc.sql("""
                update batch.batch_job_execution
                set start_time = timestamp '2026-09-20 19:03:00',
                    end_time = timestamp '2026-09-20 19:04:00'
                where job_execution_id = 1""")
            .update();

        mvc.perform(get("/api/health/job-runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].startedAt").value("2026-09-20T19:03:00Z"));
        mvc.perform(get("/api/health/sources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.source == 'football-data')].lastSuccessAt")
                .value(contains("2026-09-20T19:04:00Z")));
    }

    /** 288 ledger refreshes a day would bury every other run. */
    @Test
    void theLedgerRefreshIsLeftOutOfTheRunList() throws Exception {
        run(1, "fetchArchiveJob", 30, "COMPLETED", null);
        run(2, CollectorHealthController.LEDGER_REFRESH_JOB, 1, "COMPLETED", null);

        mvc.perform(get("/api/health/job-runs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].jobName").value("fetchArchiveJob"));
    }

    @Test
    void limitBoundsTheHistory() throws Exception {
        run(1, "fetchArchiveJob", 30, "COMPLETED", null);
        run(2, "fetchArchiveJob", 6, "COMPLETED", null);

        mvc.perform(get("/api/health/job-runs").param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }
}
