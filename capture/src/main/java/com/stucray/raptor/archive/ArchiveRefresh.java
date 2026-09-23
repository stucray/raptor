package com.stucray.raptor.archive;

/**
 * Fetch the current football-data season, on someone else's schedule.
 *
 * <p>This package's only published type, and it exists for one reason: the
 * archive no longer has a timer of its own. It had one from S9 until #200 — a
 * separate 08:30Z cron that fetched and then stopped, because nothing ever
 * projected what it wrote. The fetch half was automated and the projection half
 * was a manual POST, which is how {@code raw.football_file} and
 * {@code query.football_match} came to agree with each other and both sit four
 * days behind the archive.
 *
 * <p>So the fetch is a step of the nightly close-out now, and the close-out
 * lives in {@code projection}. The interface is what keeps that call from being
 * an excuse to widen this package: a caller gets the sweep and its tally, and
 * still cannot reach a target, a validator or the custody root.
 *
 * <p><b>The cost, stated plainly:</b> 23:30Z is later than 08:30Z, so a match
 * finishing near 22:00Z may not have its result row until the following night's
 * run. That is accepted. Every request is conditional, so the extra day costs
 * nothing but latency, and a result published after a run is picked up by the
 * next one.
 */
public interface ArchiveRefresh {

	/**
	 * The current season across every configured division: 22 conditional
	 * requests, not the 748 of a full revalidation, which stays an ops decision.
	 *
	 * <p><b>Does not throw.</b> An upstream outage is an ordinary night for a
	 * free service on the public internet, and the caller's other half — the
	 * live capture projection — must not lose a night to it. The verdict comes
	 * back in the outcome instead, where the close-out ledger records it.
	 */
	Outcome refreshCurrentSeason();

	/**
	 * @param successful whether the sweep ran to completion. A file that failed
	 *     alone is a failure here too: the tally is what says how much of the
	 *     sweep that was.
	 * @param filesChanged files this sweep put into the system of record — NEW,
	 *     UPDATED and ADOPTED. Deliberately not the request count: a night when
	 *     every one of the 22 revalidated unchanged did its job perfectly and
	 *     changed nothing, and the number that matters is how much new evidence
	 *     arrived.
	 * @param report the whole verdict tally in words, for the ledger's detail
	 *     column and the log line — the sentence a reader of a failed overnight
	 *     run needs, rather than a status to go and correlate.
	 */
	record Outcome(boolean successful, int filesChanged, String report) {}
}
