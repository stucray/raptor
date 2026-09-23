package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Whether the machine was away, measured rather than guessed.
 *
 * <p>Both clocks are driven by hand, because the whole measurement is their
 * disagreement and nothing else in the system can produce one on demand. The
 * instants are the real ones from 2026-09-12, where a suspend and a genuine
 * disconnect landed four minutes apart and only an overlap test separates them.
 */
class SuspendClockTest {

	private static final Instant LID_CLOSED = Instant.parse("2026-09-12T08:17:37Z");
	private static final Instant RESUMED = Instant.parse("2026-09-12T08:28:00Z");

	private final MutableClock clock = new MutableClock(LID_CLOSED);
	private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
	private final SuspendClock suspends = new SuspendClock(properties(), clock, nanos::get);

	/**
	 * The two clocks disagreeing IS the suspend: {@code Clock} advances while a
	 * machine is asleep and {@code nanoTime} does not, so the difference over one
	 * interval measures exactly how long it was away and nothing else can.
	 */
	@Test
	void measuresASuspendAsTheDisagreementBetweenTheClocks() {
		suspend(Duration.ofSeconds(623), Duration.ofSeconds(10));

		SuspendClock.Suspend suspend = suspends.sample();

		assertThat(suspend).isNotNull();
		assertThat(suspend.duration()).isEqualTo(Duration.ofSeconds(613));
		// Dated from when it was noticed, backwards: nothing was running to record
		// the moment the machine actually went away.
		assertThat(suspend.to()).isEqualTo(LID_CLOSED.plusSeconds(623));
	}

	/** Jitter and NTP slew are milliseconds; a suspend is seconds to hours. */
	@Test
	void doesNotMistakeSchedulingJitterForASuspend() {
		suspend(Duration.ofMillis(10_150), Duration.ofSeconds(10));

		assertThat(suspends.sample()).isNull();
	}

	/** The baseline moves on, or every later tick reports the same interval. */
	@Test
	void reportsAnIntervalOnceAndNotForever() {
		suspend(Duration.ofSeconds(623), Duration.ofSeconds(10));
		assertThat(suspends.sample()).isNotNull();

		suspend(Duration.ofSeconds(10), Duration.ofSeconds(10));

		assertThat(suspends.sample()).isNull();
	}

	/**
	 * The socket-first case, which is the whole of #264.
	 *
	 * <p>Nothing has sampled since the machine went away — the read loop's timeout
	 * fired the instant it resumed, before the watchdog's next tick — so the
	 * measurement has to come off the standing baseline without disturbing it.
	 */
	@Test
	void findsASuspendNothingHasSampledYet() {
		suspend(Duration.ofSeconds(623), Duration.ofSeconds(10));

		SuspendClock.Suspend suspend = suspends.straddling(LID_CLOSED, RESUMED.plusSeconds(28));

		assertThat(suspend).isNotNull();
		assertThat(suspend.duration()).isEqualTo(Duration.ofSeconds(613));
	}

	/**
	 * And the case that must NOT be claimed: the stale pre-suspend socket timing
	 * out at 08:28:20, twenty seconds after the machine was back. A genuine
	 * DISCONNECT, and recency alone would have taken it.
	 */
	@Test
	void doesNotClaimAGapThatStartsAfterTheSuspendEnded() {
		suspend(Duration.ofSeconds(623), Duration.ofSeconds(10));
		suspends.sample();
		// Awake for the next thirty seconds, so the live measurement is clean and
		// only the retained suspend is in play.
		suspend(Duration.ofSeconds(30), Duration.ofSeconds(30));
		suspends.sample();

		assertThat(suspends.straddling(RESUMED.plusSeconds(20), RESUMED.plusSeconds(50))).isNull();
	}

	/**
	 * A tick that lands between the read loop ending and the gap being written
	 * consumes the live measurement; the retained one is what is left.
	 */
	@Test
	void stillAnswersForASuspendAlreadySampled() {
		suspend(Duration.ofSeconds(623), Duration.ofSeconds(10));
		suspends.sample();

		assertThat(suspends.straddling(LID_CLOSED, RESUMED)).isNotNull();
	}

	private void suspend(Duration wall, Duration monotonic) {
		clock.advance(wall);
		nanos.addAndGet(monotonic.toNanos());
	}

	private static RecorderProperties properties() {
		return new RecorderProperties(true, 50_000, 1024, Duration.ofMillis(200),
				Duration.ofSeconds(5), Duration.ofSeconds(60), 1L, 3, Duration.ofMinutes(10),
				new RecorderProperties.Spill(false, java.nio.file.Path.of("target/unused"),
						org.springframework.util.unit.DataSize.ofGigabytes(1),
						Duration.ofMinutes(1), 200, Duration.ofMinutes(15), 3),
				new RecorderProperties.Watchdog(Duration.ofSeconds(10), Duration.ofSeconds(30),
						Duration.ofSeconds(5)));
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
