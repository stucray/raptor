package com.stucray.raptor.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The close-out ledger against a real PostgreSQL, including the constraints —
 * which are load-bearing rather than decorative.
 *
 * <p>Since #316 a run is the archive sweep alone, and the capture half's columns
 * are NULL on every row it writes (V33).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("The close-out ledger records each run's archive verdict, and nothing it did not measure")
class CloseOutLedgerIntegrationTest {

	@Autowired CloseOutLedger ledger;
	@Autowired @Acquisition JdbcClient jdbc;

	@Test
	@DisplayName("a run in flight is RUNNING with no finish time, and is not the latest finished")
	void aStartedRunIsRecordedBeforeItFinishes() {
		long id = ledger.started();

		assertThat(row(id)).containsEntry("status", "RUNNING").containsEntry("finished_at", null);
		assertThat(ledger.latestFinished())
				.as("a run in flight has no verdict yet, and a summary of it would be a guess")
				.isEmpty();
	}

	@Test
	@DisplayName("a completed run records the archive verdict and leaves the capture half NULL")
	void aCompletedRunRecordsOnlyWhatItDid() {
		long id = ledger.started();
		ledger.finished(id, true, 3, null);

		assertThat(row(id))
				.containsEntry("status", "COMPLETED")
				.containsEntry("archive_status", "COMPLETED")
				.containsEntry("archive_files", 3)
				// NOT zero: a zero would read as a night that projected nothing,
				// and this run had no projection to count (V33).
				.containsEntry("capture_status", null)
				.containsEntry("markets_projected", null)
				.containsEntry("markets_failed", null);
	}

	@Test
	@DisplayName("a failed sweep is a failed run, with the reason")
	void aFailedSweepIsAFailedRun() {
		long id = ledger.started();
		ledger.finished(id, false, 0, "archive sweep failed (503)");

		assertThat(row(id))
				.containsEntry("status", "FAILED")
				.containsEntry("archive_status", "FAILED")
				.containsEntry("detail", "archive sweep failed (503)");
	}

	@Test
	@DisplayName("the latest finished run carries what the morning summary is composed from")
	void theLatestFinishedRunCarriesWhatItDid() {
		long older = ledger.started();
		ledger.finished(older, true, 0, null);
		long newer = ledger.started();
		ledger.finished(newer, false, 0, "archive sweep failed (503)");

		CloseOutHistory.CloseOut run = ledger.latestFinished().orElseThrow();

		assertThat(run.archiveSuccessful()).isFalse();
		assertThat(run.archiveFiles()).isZero();
		assertThat(run.detail()).isEqualTo("archive sweep failed (503)");
	}

	@Test
	@DisplayName("punctuality finds the run that started since it was due, RUNNING or not (#334)")
	void punctualityFindsTheRunSinceItWasDue() {
		assertThat(ledger.punctuality().startedAt())
				.as("an empty ledger has started nothing since the due time")
				.isNull();

		ledger.started();

		CloseOutHistory.Punctuality punctuality = ledger.punctuality();
		assertThat(punctuality.startedAt())
				.as("a run in flight counts: the trigger came, which is the question")
				.isNotNull();
		assertThat(punctuality.overdue()).isFalse();
	}

	@Test
	@DisplayName("a run from before the due time does not count as tonight's")
	void aRunBeforeTheDueTimeDoesNotCount() {
		// Two days back is before any lastDue(now), whatever the time of day.
		jdbc.sql("""
						insert into batch.close_out (started_at, finished_at, status, archive_status)
						values (now() - interval '2 days', now() - interval '2 days',
							'COMPLETED', 'COMPLETED')""")
				.update();

		assertThat(ledger.punctuality().startedAt()).isNull();
	}

	/**
	 * The schema refuses a finished run with no finish time, and one with no
	 * archive verdict. Both pairings are what make "started and never came back"
	 * a distinguishable state; V33 moved the second from the capture verdict to
	 * the archive one when the capture half left.
	 */
	@Test
	@DisplayName("the schema refuses a finished run with no finish time or no archive verdict")
	void theSchemaRefusesAnIncompleteFinish() {
		// Each row breaks exactly ONE of the two pairings, so the constraint the
		// server names is the one under test rather than whichever it checked first.
		assertThatThrownBy(() -> jdbc.sql("""
						insert into batch.close_out (started_at, status, archive_status)
						values (now(), 'COMPLETED', 'COMPLETED')""")
				.update())
				.hasMessageContaining("close_out_finished_when_done");
		assertThatThrownBy(() -> jdbc.sql("""
						insert into batch.close_out (started_at, finished_at, status)
						values (now(), now(), 'COMPLETED')""")
				.update())
				.hasMessageContaining("close_out_archive_when_done");
	}

	private Map<String, Object> row(long id) {
		return jdbc.sql("""
						select status, finished_at, capture_status, archive_status, archive_files,
							markets_projected, markets_failed, detail
						from batch.close_out where id = ?""")
				.param(id).query().singleRow();
	}
}
