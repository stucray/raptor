package com.stucray.raptor.projection;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the capture ledger current, because a stale one is worse than none.
 *
 * <p>Every other job here is launched deliberately, and for good reason: a
 * resident service that ingests several million rows because somebody restarted
 * it is not operable. This one is the exception, and the exception is about what
 * the ledger is FOR. The health screen exists to answer "did last night's
 * capture happen" in the morning; a ledger that only moves when an operator
 * remembers to POST would answer it with yesterday's state and look exactly as
 * confident. The old ledger was refreshed by the spool import poll, so this
 * restores a property that is being taken away rather than adding a new one.
 *
 * <p>It is affordable because of what it costs: nine aggregates over rows that
 * are already indexed by session, a few seconds, a few dozen rows replaced. It
 * is safe to run beside anything because {@link ProjectionLock} serialises it
 * against itself and it touches no table any other projection writes.
 *
 * <p><b>Opt-in by property</b>, which is deliberate rather than timid: a
 * scheduled Batch launch in every {@code @SpringBootTest} context would put a
 * job execution into the middle of unrelated tests, and a test that fails
 * because of a timer is a test nobody trusts again.
 */
@Component
@ConditionalOnProperty(name = "raptor.projection.capture-ledger.enabled", havingValue = "true")
class CaptureLedgerRefresh {

	private static final Logger log = LoggerFactory.getLogger(CaptureLedgerRefresh.class);

	private final ProjectCaptureLedgerController ledger;
	private final Clock clock;

	CaptureLedgerRefresh(ProjectCaptureLedgerController ledger, Clock clock) {
		this.ledger = ledger;
		this.clock = clock;
	}

	@Scheduled(
			fixedDelayString = "${raptor.projection.capture-ledger.interval:PT5M}",
			initialDelayString = "${raptor.projection.capture-ledger.interval:PT5M}")
	void refresh() {
		try {
			// Never a full rebuild on the timer. The incremental pass is what keeps
			// this inside the pool's socket timeout (#261); a scheduled rebuild would
			// reintroduce exactly the scan that broke it.
			ledger.project(clock.millis(), false);
		} catch (Exception e) {
			// A failed refresh must not stop the schedule: the next one is five
			// minutes away and the ledger is a projection, so the cost of missing
			// one is that the screen is five minutes older than it could be.
			//
			// ERROR rather than WARN, though: the capture screen and the heartbeat
			// verdict both read what this job writes, so a run that keeps failing is
			// a health signal going stale with nothing else to say so — the shape of
			// #122, #141 and #144, where the only trace of a broken watcher was a
			// number that had stopped moving.
			log.error("capture ledger refresh failed; the screen and the capture verdict "
					+ "will be stale until the next one", e);
		}
	}
}
