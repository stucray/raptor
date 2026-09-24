package com.stucray.raptor.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.stucray.raptor.TestcontainersConfiguration;
import com.stucray.raptor.datasource.Acquisition;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a gap cost, against a real PostgreSQL.
 *
 * <p>Every expectation is arithmetic done by hand over rows this test inserts,
 * and the rows are written relative to <b>now</b> rather than to a fixed
 * instant — the query's whole subject is a recent past, and a fixture pinned to
 * a date would fall out of the window and pass by being empty.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CaptureGapLedgerIntegrationTest {

	@Autowired GapHistory gaps;
	@Autowired @Acquisition JdbcClient jdbc;

	private Instant now;
	private long projection;
	// ledger.capture_gap.id is raw's id carried across, not a generated one.
	private long nextGapId;

	@BeforeEach
	void seed() {
		now = Instant.now();
		nextGapId = 1;
		projection = jdbc.sql("""
						insert into ledger.ledger_run (job_name, partition_key, job_execution_id)
						values ('test', 'gaps', 1) returning id""")
				.query(Long.class).single();
	}

	/** The case this exists for: a lid closed during the second half. */
	@Test
	void reportsTheOverlapAndWhatItCost() {
		// Three markets on, two of them kicked off an hour ago.
		market("1.1", now.minusSeconds(7200), now.minusSeconds(3600));
		market("1.2", now.minusSeconds(7200), now.minusSeconds(3600));
		market("1.3", now.minusSeconds(7200), null);
		gap(now.minusSeconds(1000), now.minusSeconds(678), "SLEEP");

		assertThat(gaps.lastGapDuringPlay()).hasValueSatisfying(gap -> {
			assertThat(gap.cause()).isEqualTo("SLEEP");
			assertThat(gap.seconds()).isEqualTo(322);
			assertThat(gap.markets()).isEqualTo(3);
			// The number that decides whether anybody is told: the third market
			// was in scope and had not kicked off, so it was not in play.
			assertThat(gap.live()).isEqualTo(2);
		});
	}

	/**
	 * A gap with nothing in play says nothing at all.
	 *
	 * <p>The 2026-09-11 case — a 322-second suspend with scope empty is a
	 * non-event, and an alert that fires on every end-of-evening lid close is one
	 * that gets muted before the night it matters.
	 */
	@Test
	void silentWhenNothingWasInPlay() {
		market("1.1", now.minusSeconds(600), null);
		gap(now.minusSeconds(400), now.minusSeconds(100), "SLEEP");

		assertThat(gaps.lastGapDuringPlay()).isEmpty();
	}

	/** A market that had already finished when the gap began lost nothing. */
	@Test
	void silentWhenPlayHadAlreadyFinished() {
		done("1.1", now.minusSeconds(20000), now.minusSeconds(16000), now.minusSeconds(9000));
		gap(now.minusSeconds(400), now.minusSeconds(100), "SLEEP");

		assertThat(gaps.lastGapDuringPlay()).isEmpty();
	}

	/**
	 * Old gaps are out of scope, however costly they were.
	 *
	 * <p>Not because they did not matter, but because this answer's reader
	 * announces whatever it finds: an unbounded query would push a finding about
	 * a suspend from a fortnight ago the first time a host lost its de-dupe key.
	 */
	@Test
	void ignoresAGapOlderThanTheWindow() {
		market("1.1", now.minusSeconds(400000), now.minusSeconds(390000));
		gap(now.minusSeconds(200000), now.minusSeconds(199000), "SLEEP");

		assertThat(gaps.lastGapDuringPlay()).isEmpty();
	}

	/** Two qualifying gaps: the one that ended last is the one to report. */
	@Test
	void reportsTheMostRecentQualifyingGap() {
		market("1.1", now.minusSeconds(20000), now.minusSeconds(19000));
		gap(now.minusSeconds(9000), now.minusSeconds(8000), "DISCONNECT");
		gap(now.minusSeconds(5000), now.minusSeconds(4880), "SILENCE");

		assertThat(gaps.lastGapDuringPlay()).hasValueSatisfying(gap -> {
			assertThat(gap.cause()).isEqualTo("SILENCE");
			assertThat(gap.seconds()).isEqualTo(120);
		});
	}

	/**
	 * A reconnect during play is not a finding.
	 *
	 * <p>The case #298 was filed for, and the one the live ledger was entirely
	 * made of: sixteen sub-second disconnects on the 2026-09-05 card, every one
	 * of them the reconnect machinery doing its job. Reported, they would have
	 * been sixteen notifications and one operator who stopped reading them.
	 */
	@Test
	void ignoresAGapTooShortToBeMoreThanAReconnect() {
		market("1.1", now.minusSeconds(20000), now.minusSeconds(19000));
		gap(now.minusSeconds(5000), now.minusSeconds(4970), "DISCONNECT");

		assertThat(gaps.lastGapDuringPlay()).isEmpty();
	}

	/**
	 * The floor is a duration, not a cause list.
	 *
	 * <p>A long upstream outage in the second half is worth knowing about
	 * whatever the ledger calls it; what is excluded is brevity, not kind.
	 */
	@Test
	void reportsALongGapWhateverItsCause() {
		market("1.1", now.minusSeconds(20000), now.minusSeconds(19000));
		gap(now.minusSeconds(5000), now.minusSeconds(4700), "DISCONNECT");

		assertThat(gaps.lastGapDuringPlay()).hasValueSatisfying(gap ->
				assertThat(gap.seconds()).isEqualTo(300));
	}

	private void market(String id, Instant firstSeen, Instant inPlaySince) {
		insertMarket(id, firstSeen, inPlaySince, "SUBSCRIBED", firstSeen);
	}

	private void done(String id, Instant firstSeen, Instant inPlaySince, Instant finished) {
		insertMarket(id, firstSeen, inPlaySince, "DONE", finished);
	}

	private void insertMarket(String id, Instant firstSeen, Instant inPlaySince, String state,
			Instant stateChanged) {
		jdbc.sql("""
						insert into ledger.market_scope (market_id, market_type, requested, state,
							first_seen_at, state_changed_at, in_play_since, messages, ledger_run_id)
						values (?, 'MATCH_ODDS', true, ?, ?, ?, ?, 0, ?)""")
				.params(Arrays.asList(id, state, at(firstSeen), at(stateChanged),
						inPlaySince == null ? null : at(inPlaySince), projection))
				.update();
	}

	private void gap(Instant from, Instant to, String cause) {
		jdbc.sql("""
						insert into ledger.capture_gap (id, session_id, started_at, ended_at, cause,
							ledger_run_id)
						values (?, 1, ?, ?, ?, ?)""")
				.params(Arrays.asList(nextGapId++, at(from), at(to), cause, projection))
				.update();
	}

	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
