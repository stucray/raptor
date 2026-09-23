package com.stucray.raptor.projection;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What the nightly close-out last did.
 *
 * <p>Since #316 the close-out only fetches the football archive into {@code raw};
 * whether captured markets reached {@code query} is overround-analysis's to
 * report, on its own close-out. The "when did the capture half last succeed"
 * question this interface used to answer left with the half.
 *
 * <p>The projection module's second published type, and as narrow as the first:
 * the answers are not visible anywhere else. A close-out that has stopped firing
 * produces no log line, no failed job and no alert — it produces an absence,
 * which is what #201 watches for from the host and what the capture health
 * screen reports as a fact.
 */
public interface CloseOutHistory {

	/**
	 * The most recent <b>finished</b> run, whatever it did.
	 *
	 * <p>Distinct from the above in two ways that matter to its reader: it is the
	 * last run rather than the last good one, and it carries the counts. The
	 * morning summary is composed from exactly this, and it reports a failed run
	 * as readily as a successful one — a night that half-worked is the case where
	 * a summary earns its keep.
	 *
	 * <p>Excludes a RUNNING row: a run in flight has no verdict yet, and
	 * announcing one would be announcing a guess.
	 */
	Optional<CloseOut> latestFinished();

	/**
	 * Whether tonight's run came when it was due (#334).
	 *
	 * <p>A fact, like the rest of this interface: its reader is capture health,
	 * whose verdict drives an automatic restart, and a restart cannot make a
	 * host-side trigger fire.
	 */
	Punctuality punctuality();

	/**
	 * One finished run.
	 *
	 * @param finishedAt when it ended. <b>The summary's identity</b>: the host
	 *     keys its once-per-run de-dupe on this, so it must be the run's own
	 *     timestamp and never the time it was read.
	 * @param archiveSuccessful the football-data sweep fetched the current season
	 * @param detail why, in words, when it failed
	 */
	record CloseOut(Instant finishedAt, boolean archiveSuccessful, int archiveFiles,
			@Nullable String detail) {}

	/**
	 * @param dueAt the most recent time a run was due
	 * @param startedAt when the first run since then started — RUNNING or
	 *     finished, launchd or by hand — or null if none has
	 * @param overdue no run has started, and the stated margin past
	 *     {@code dueAt} has gone: the trigger is late, stopped, or on the wrong
	 *     clock. Before this a late run and a good night read identically until
	 *     the 26-hour absence.
	 */
	record Punctuality(Instant dueAt, @Nullable Instant startedAt, boolean overdue) {}
}
