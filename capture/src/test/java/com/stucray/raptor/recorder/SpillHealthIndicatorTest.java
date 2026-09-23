package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.util.unit.DataSize;

class SpillHealthIndicatorTest {

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	/**
	 * A file that has just appeared is the system working, not the system
	 * failing.
	 *
	 * <p>It means an outage happened and the book is safe on disk. A verdict that
	 * went red for that would cry wolf at exactly the moment the design did its
	 * job — and would then be indistinguishable from the case that really is
	 * fatal. What matters is whether it is still there later; see below.
	 */
	@Test
	void aFreshlySpilledFileIsADetailNotAFailure(@TempDir Path directory) throws Exception {
		Files.writeString(directory.resolve("spill-1-123-1.ndjson"), "{}\n");

		Health health = indicator(directory, DataSize.ofBytes(1), true).health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("pendingFiles", 1);
		assertThat((Long) health.getDetails().get("freeBytes")).isPositive();
	}

	/**
	 * No room left is the one failure the spill cannot absorb.
	 *
	 * <p>Disk full and database down together loses everything from that instant,
	 * and nothing in the design can change that. What it can do is refuse to be
	 * quiet about approaching it.
	 */
	@Test
	void reportsDownWhenThereIsNoRoomLeftToSpillInto(@TempDir Path directory) {
		Health health = indicator(directory, DataSize.ofTerabytes(100_000), true).health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
	}

	@Test
	void saysSoWhenTheSpillIsSwitchedOff(@TempDir Path directory) {
		Health health = indicator(directory, DataSize.ofTerabytes(100_000), false).health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("spill", "disabled");
	}

	/**
	 * A file still waiting long after it was written means the drain is not
	 * working, and that is a state somebody has to be told about.
	 *
	 * <p>This is #167 in one assertion. {@code SpillReplayer} had no production
	 * caller, so a file from session 45 sat unreplayed through four capture
	 * sessions — its one message absent from {@code raw}, present only on one
	 * local disk — while this indicator reported UP throughout. Existence was
	 * never the signal; duration is.
	 *
	 * <p>OUT_OF_SERVICE rather than DOWN, matching {@code captureCoverage}: the
	 * application is fine and the read side is serving, but this instance is not
	 * doing a job it is supposed to be doing.
	 */
	@Test
	void reportsOutOfServiceWhenAFileHasBeenPendingTooLong(@TempDir Path directory)
			throws Exception {
		Files.writeString(directory.resolve("spill-45-123-1.ndjson"), "{}\n");
		// An hour later, against a staleAfter of fifteen minutes.
		Clock later = Clock.offset(Clock.systemUTC(), Duration.ofHours(1));

		Health health = indicator(directory, DataSize.ofBytes(1), true, later).health();

		assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
		assertThat(health.getDetails()).containsEntry("pendingFiles", 1);
		assertThat((Long) health.getDetails().get("oldestPendingSeconds"))
				.isGreaterThan(Duration.ofMinutes(15).toSeconds());
		assertThat((String) health.getDetails().get("reason"))
				.contains("not in the system of record");
	}

	/**
	 * An empty directory never goes stale, however long the recorder has been up.
	 *
	 * <p>Guards the obvious way to write the staleness check wrongly: an
	 * {@code oldestPendingAt} of "nothing" is not an age of zero-since-the-epoch,
	 * and a spill that has never been used is the normal state of this system.
	 */
	@Test
	void neverGoesStaleWithNothingPending(@TempDir Path directory) {
		Clock muchLater = Clock.offset(Clock.systemUTC(), Duration.ofDays(30));

		Health health = indicator(directory, DataSize.ofBytes(1), true, muchLater).health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("pendingFiles", 0);
		assertThat(health.getDetails()).containsEntry("oldestPendingSeconds", 0L);
	}

	/** A directory that does not exist yet is a recorder that has never failed. */
	@Test
	void measuresFreeSpaceBeforeTheFirstSpillEverHappens(@TempDir Path directory) {
		SpillDirectory spill = new SpillDirectory(properties(directory.resolve("never/created"),
				DataSize.ofBytes(1), true));

		assertThat(spill.freeBytes()).isPositive();
		assertThat(spill.pending()).isEmpty();
	}

	private SpillHealthIndicator indicator(Path directory, DataSize minFree, boolean enabled) {
		return indicator(directory, minFree, enabled, Clock.systemUTC());
	}

	private SpillHealthIndicator indicator(Path directory, DataSize minFree, boolean enabled,
			Clock clock) {
		RecorderProperties properties = properties(directory, minFree, enabled);
		return new SpillHealthIndicator(new SpillDirectory(properties), properties, clock, meters,
				new CollectingQuarantine());
	}

	private static RecorderProperties properties(Path directory, DataSize minFree, boolean enabled) {
		return new RecorderProperties(true, 50_000, 1024, Duration.ofMillis(200),
				Duration.ofSeconds(5), Duration.ofSeconds(60), 1L, 3, Duration.ofMinutes(10),
				new RecorderProperties.Spill(enabled, directory, minFree, Duration.ofMinutes(1),
						200, Duration.ofMinutes(15), 3),
				new RecorderProperties.Watchdog(Duration.ofSeconds(10), Duration.ofSeconds(30),
						Duration.ofSeconds(5)));
	}
}
