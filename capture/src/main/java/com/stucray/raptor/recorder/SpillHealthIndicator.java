package com.stucray.raptor.recorder;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Free space on the spill volume, as a first-class signal.
 *
 * <p>The spill absorbs a database outage. The one failure it cannot absorb is a
 * <b>full disk and a down database at the same time</b> — everything from that
 * instant is lost, and nothing in this design can change that. What can be
 * changed is arriving there without warning, which is why free space is measured
 * and reported here rather than left as a follow-up.
 *
 * <p><b>Pending files used to be a detail here, and that was wrong.</b> The
 * reasoning was that a pending file means an outage happened and the messages
 * are safe on disk, which is the system working — true, but only if something
 * puts them back. Nothing did: {@link SpillReplayer} had no production caller at
 * all, so a file from session 45 sat unreplayed through four sessions with this
 * indicator reporting UP the whole time and its one message absent from the
 * system of record (#167).
 *
 * <p>So the distinction is now about <b>time</b>, not existence. A file that
 * appeared moments ago is the spill working and says nothing; a file still
 * waiting after {@code staleAfter} means the drain is not doing its job, and
 * that is a state somebody has to know about — the messages are on one local
 * disk, absent from {@code raw}, and invisible to everything downstream.
 *
 * <p>Two conditions, two verdicts, deliberately. <b>DOWN</b> for free space,
 * unchanged: the spill can no longer do the job it exists for, and disk full
 * with the database down at once is the one failure nothing here absorbs.
 * <b>OUT_OF_SERVICE</b> for a stale file, matching {@code captureCoverage} —
 * the application is not broken, the read side is serving perfectly well, but
 * this instance is not doing a job it is supposed to be doing.
 *
 * <p><b>Two things #271 added are FACTS here, not verdicts</b>, and that
 * distinction is the same one {@code CaptureCoverageHealthIndicator} makes about
 * the projection backlog. {@code quarantinedMessages} counts rows the database
 * refused on their own merits; {@code unreplayableFiles} counts spill files
 * moved aside because they could never succeed. Both mean messages are outside
 * the system of record, and both are real findings — but neither can be fixed by
 * restarting the JVM, and the heartbeat restarts the backend after three
 * unhealthy checks with markets in scope. Turning the verdict over on them would
 * answer "a payload Postgres will not accept" with a SIGTERM, repeatedly. They
 * are published so that something outside the process can alert on them, which
 * is where the response can be a sentence rather than a kill.
 */
@Component
class SpillHealthIndicator implements HealthIndicator {

	private final SpillDirectory directory;
	private final long minFreeBytes;
	private final boolean enabled;
	private final Duration staleAfter;
	private final Clock clock;
	private final Quarantine quarantine;

	SpillHealthIndicator(SpillDirectory directory, RecorderProperties properties, Clock clock,
			MeterRegistry meters, Quarantine quarantine) {
		this.directory = directory;
		this.quarantine = quarantine;
		this.minFreeBytes = properties.spill().minFree().toBytes();
		this.enabled = properties.spill().enabled();
		this.staleAfter = properties.spill().staleAfter();
		this.clock = clock;

		Gauge.builder("raptor.recorder.spill.free.bytes", directory, SpillDirectory::freeBytes)
				.description("Free space on the volume holding the spill directory")
				.register(meters);
		Gauge.builder("raptor.recorder.spill.pending.files", directory,
						self -> self.pending().size())
				.description("Spill files waiting to be replayed into the system of record")
				.register(meters);
		Gauge.builder("raptor.recorder.spill.unreplayable.files", directory,
						self -> self.setAside().size())
				.description("Spill files moved aside because they can never be replayed (#271)")
				.register(meters);
	}

	@Override
	public Health health() {
		if (!enabled) {
			return Health.up().withDetail("spill", "disabled").build();
		}
		long free = directory.freeBytes();
		int pending = directory.pending().size();
		Optional<Instant> oldest = directory.oldestPendingAt();
		Duration waiting = oldest.map(at -> Duration.between(at, clock.instant()))
				.orElse(Duration.ZERO);
		boolean stale = pending > 0 && waiting.compareTo(staleAfter) > 0;

		Health.Builder health;
		if (free < minFreeBytes) {
			health = Health.down();
		} else if (stale) {
			health = Health.outOfService();
		} else {
			health = Health.up();
		}
		health.withDetail("directory", directory.path().toString())
				.withDetail("freeBytes", free)
				.withDetail("minFreeBytes", minFreeBytes)
				.withDetail("pendingFiles", pending)
				.withDetail("unreplayableFiles", directory.setAside().size())
				.withDetail("quarantinedMessages", quarantine.quarantined())
				// Always, not only when stale: "how long has the oldest been
				// waiting" is the number that distinguishes a spill that is working
				// from one that is stuck, and a reader should not have to wait for
				// the verdict to flip before they can see it.
				.withDetail("oldestPendingSeconds", waiting.toSeconds());
		if (stale) {
			health.withDetail("reason", pending + " spill file(s) still pending after "
					+ waiting.toSeconds() + "s; the oldest messages are not in the system of "
					+ "record and exist only on this disk");
		}
		return health.build();
	}

}
