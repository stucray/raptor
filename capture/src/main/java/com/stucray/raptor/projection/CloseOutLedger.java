package com.stucray.raptor.projection;

import com.stucray.raptor.datasource.Acquisition;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * When a close-out ran, and what it did.
 *
 * <p><b>Durable, not in-memory</b>, and the reason is the failure this whole PRD
 * is about. A restart must not make a run that happened look like one that did
 * not: those are opposite findings, and the one that means "nothing has run"
 * must never be produced by something as ordinary as a redeploy.
 *
 * <p>A row is written when a run <b>starts</b> and closed when it ends, rather
 * than written once at the end. A chain that begins and never returns — a wedged
 * JVM, a killed container — then leaves a RUNNING row, which is a finding. Write
 * only on completion and that same failure leaves nothing at all, which is
 * indistinguishable from a night when the timer never fired.
 */
@Component
class CloseOutLedger implements CloseOutHistory {

	private final JdbcClient jdbc;
	private final Clock clock;
	private final CloseOutSchedule schedule;

	CloseOutLedger(@Acquisition JdbcClient jdbc, Clock clock, CloseOutSchedule schedule) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.schedule = schedule;
	}

	/** Record that a run has begun, and return its id. */
	long started() {
		Long id = jdbc.sql("""
						insert into batch.close_out (started_at, status)
						values (?, 'RUNNING') returning id""")
				.param(at(clock.instant()))
				.query(Long.class).single();
		return id == null ? 0 : id;
	}

	/**
	 * Close a run with the archive sweep's verdict, which since #316 is the whole
	 * run's.
	 *
	 * <p>Called from a finally block, so it runs for a chain that threw as well
	 * as one that finished — a failed run that leaves no record is the same
	 * absence as no run at all.
	 */
	void finished(long id, boolean archiveSuccessful, int archiveFiles, @Nullable String detail) {
		// The capture half's columns stay NULL: since #316 there is no capture
		// half to record, and a zero would read as a night that projected nothing
		// (V33).
		jdbc.sql("""
						update batch.close_out
						set finished_at = ?, status = ?, archive_status = ?, archive_files = ?,
							detail = ?
						where id = ?""")
				.params(Arrays.asList(at(clock.instant()), status(archiveSuccessful),
						status(archiveSuccessful), archiveFiles, detail, id))
				.update();
	}

	@Override
	public Optional<CloseOut> latestFinished() {
		return jdbc.sql("""
						select finished_at, archive_status, archive_files, detail
						from batch.close_out
						where status <> 'RUNNING'
						order by finished_at desc, id desc
						limit 1""")
				.query((rs, rowNum) -> new CloseOut(
						rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
						"COMPLETED".equals(rs.getString("archive_status")),
						rs.getInt("archive_files"), rs.getString("detail")))
				.optional();
	}

	@Override
	public Punctuality punctuality() {
		Instant now = clock.instant();
		Instant dueAt = schedule.lastDue(now);
		Optional<Instant> first = jdbc.sql("""
						select started_at from batch.close_out
						where started_at >= ?
						order by started_at
						limit 1""")
				.param(at(dueAt))
				.query((rs, rowNum) -> rs.getObject("started_at", OffsetDateTime.class).toInstant())
				.optional();
		return schedule.punctuality(now, first.orElse(null));
	}

	private static String status(boolean successful) {
		return successful ? "COMPLETED" : "FAILED";
	}

	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
