package com.stucray.raptor.projection;

import com.stucray.raptor.archive.ArchiveRefresh;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The nightly close-out: fetching the current football-data season into
 * {@code raw}, every night, without being asked.
 *
 * <p><b>It used to project too, and since #316 it does not.</b> From #199 this
 * was where {@code raw} became {@code query} unbidden — the live capture backlog
 * first, then the archive fetched and projected. overround-analysis writes
 * {@code query} now, and runs that projection as the first step of its own
 * close-out at 00:30Z. What stays here is the one step that writes {@code raw}:
 * an archive fetch is capture of a source, and capture is paddock's (raptor's,
 * after the cutover). An hour of headroom means the night's corrected results are
 * in {@code raw} before the other application projects them.
 *
 * <p><b>Due at 23:30Z, and the hour is not arbitrary.</b> A 20:00Z kickoff leaves scope
 * at the 130-minute in-play timeout, around 22:10Z, so the evening's football is
 * over; and 23:30Z is 06:30 in the operator's local time. <b>It therefore fetches
 * on the CURRENT UTC day, not the previous one</b> — the backup agent's plist
 * carries a scar from getting local-versus-UTC reasoning wrong by nine hours.
 *
 * <p><b>A run is recorded whether or not the fetch works</b>, in
 * {@code batch.close_out}: a row when it starts and its verdict when it ends, so
 * a run that never came back stays RUNNING and a night the timer never fired
 * leaves no row at all. A football-data outage costs a night's results, which
 * the next sweep fetches; it is reported, never thrown.
 *
 * <p>Off by default in code, on in {@code application.yml}; with it off there is
 * no {@code /ops/close-out} either.
 */
@Component
@ConditionalOnProperty(name = "raptor.close-out.enabled", havingValue = "true")
class NightlyCloseOut {

	private static final Logger log = LoggerFactory.getLogger(NightlyCloseOut.class);

	private final ArchiveRefresh archive;
	private final CloseOutLedger ledger;
	private final CloseOutLock lock;

	NightlyCloseOut(ArchiveRefresh archive, CloseOutLedger ledger, CloseOutLock lock) {
		this.archive = archive;
		this.ledger = ledger;
		this.lock = lock;
	}

	/**
	 * Run the close-out now, unless one is already running.
	 *
	 * <p><b>No timer of its own since #334.</b> This was a
	 * {@code @Scheduled(cron = "0 30 23 * * *")} method, and Spring waits out a
	 * cron's delay on the monotonic clock, which inside the Docker VM stops while
	 * the Mac sleeps — so a firing did not run late and catch up, it slipped by
	 * exactly the sleep that followed (63 minutes on 2026-09-22/23, silently). The
	 * trigger is launchd's close-out launchd agent now, whose
	 * {@code StartCalendarInterval} runs a missed firing on wake, through
	 * {@code POST /ops/close-out}. One trigger, deliberately: a cron left behind as
	 * a fallback would run a second sweep every night the first one worked.
	 *
	 * <p>When the run is due is {@link CloseOutSchedule}'s to say, and whether it
	 * came is reported on capture health rather than trusted.
	 */
	Result closeOut() {
		CloseOutLock.Held held = lock.tryHold();
		if (held == null) {
			log.info("close-out asked for while one is running; leaving it to that run");
			return new Result(Status.ALREADY_RUNNING, 0, null);
		}
		try (held) {
			return run();
		}
	}

	private Result run() {
		long id = ledger.started();
		boolean successful = false;
		int archiveFiles = 0;
		@Nullable String detail = null;
		try {
			ArchiveRefresh.Outcome sweep = archive.refreshCurrentSeason();
			archiveFiles = sweep.filesChanged();
			successful = sweep.successful();
			if (!successful) {
				detail = "archive sweep failed (" + sweep.report() + ")";
			}
		}
		catch (RuntimeException e) {
			// The chain itself broke, as distinct from the source failing inside it.
			detail = e.toString();
			throw e;
		}
		finally {
			ledger.finished(id, successful, archiveFiles, detail);
			// AT ERROR WHEN UNSUCCESSFUL — the #141 lesson: a FAILED daily sweep
			// logged at INFO next to the successful ones read exactly like them.
			if (successful) {
				log.info("close-out complete: {} archive file(s) changed", archiveFiles);
			}
			else {
				log.error("close-out UNSUCCESSFUL: {}", detail);
			}
		}
		return new Result(successful ? Status.COMPLETED : Status.FAILED, archiveFiles, detail);
	}

	enum Status { COMPLETED, FAILED, ALREADY_RUNNING }

	/**
	 * What one request for a close-out did.
	 *
	 * @param detail why, when it failed; null on a clean run and on a refusal
	 */
	record Result(Status status, int archiveFiles, @Nullable String detail) {}
}
