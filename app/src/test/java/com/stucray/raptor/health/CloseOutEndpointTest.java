package com.stucray.raptor.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The close-out endpoint as launchd and a person each call it (#79).
 *
 * <p>launchd fires every night with {@code scheduled=true}, and football-data.co.uk
 * asks for at most two automated downloads a week, so the scheduled call must
 * decline once the due run is done. By hand it must always run. The sweep is
 * disabled in this context ({@code TestcontainersConfiguration}), so a run here
 * completes without reaching football-data.co.uk.
 *
 * <p>Each case states the ledger it starts from: {@code DatabaseReset} empties
 * {@code batch.close_out} before every method.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("The scheduled close-out runs once per due time; by hand it always runs")
class CloseOutEndpointTest {

	@Autowired MockMvc mvc;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	@DisplayName("a scheduled call with nothing done since the due time runs and records a row")
	void aDueScheduledCallRuns() throws Exception {
		mvc.perform(post("/ops/close-out").param("scheduled", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		assertThat(rows()).isEqualTo(1);
	}

	@Test
	@DisplayName("a second scheduled call declines as NOT_DUE and records nothing")
	void aSecondScheduledCallDeclines() throws Exception {
		mvc.perform(post("/ops/close-out").param("scheduled", "true"))
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mvc.perform(post("/ops/close-out").param("scheduled", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("NOT_DUE"))
				.andExpect(jsonPath("$.archiveFiles").value(0));

		assertThat(rows())
				.as("a declined firing is not a run, and must not read as one")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a call by hand runs even when the scheduled run is done")
	void aHandRunAlwaysRuns() throws Exception {
		mvc.perform(post("/ops/close-out").param("scheduled", "true"))
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		mvc.perform(post("/ops/close-out"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		assertThat(rows()).isEqualTo(2);
	}

	@Test
	@DisplayName("a FAILED run since the due time does not count: the next firing tries again")
	void aFailedRunIsRetried() throws Exception {
		// Finished now, so after any due time; FAILED, so the work is not done.
		jdbc.sql("""
						insert into batch.close_out
							(started_at, finished_at, status, archive_status, detail)
						values (now(), now(), 'FAILED', 'FAILED', 'archive sweep failed (503)')""")
				.update();

		mvc.perform(post("/ops/close-out").param("scheduled", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		assertThat(rows()).isEqualTo(2);
	}

	@Test
	@DisplayName("a COMPLETED run from before the due time does not count either")
	void aRunBeforeTheDueTimeIsNotThisOne() throws Exception {
		// Eight days back is before the last due time on any weekly schedule.
		jdbc.sql("""
						insert into batch.close_out
							(started_at, finished_at, status, archive_status)
						values (now() - interval '8 days', now() - interval '8 days',
							'COMPLETED', 'COMPLETED')""")
				.update();

		mvc.perform(post("/ops/close-out").param("scheduled", "true"))
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		assertThat(rows()).isEqualTo(2);
	}

	private int rows() {
		Integer count = jdbc.sql("select count(*) from batch.close_out").query(Integer.class).single();
		return count == null ? 0 : count;
	}
}
