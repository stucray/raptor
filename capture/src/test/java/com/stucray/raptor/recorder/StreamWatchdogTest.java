package com.stucray.raptor.recorder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Noticing a stream that has stopped, when the socket will not admit it.
 *
 * <p>Every case here is one a real socket reports as healthy. That is the whole
 * problem: a suspended laptop leaves TCP connections that look fine until a
 * write eventually fails, which can take minutes of a match. Waiting for an
 * {@code IOException} is not a detection strategy, so both clocks are driven by
 * hand here and the socket is never consulted.
 */
class StreamWatchdogTest {

	private static final Instant START = Instant.parse("2026-09-02T18:00:00Z");

	private final MutableClock clock = new MutableClock(START);
	private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
	private final RecorderSupervisor supervisor = mock(RecorderSupervisor.class);
	private final CaptureGaps gaps = mock(CaptureGaps.class);
	private final CaptureSessions sessions = mock(CaptureSessions.class);
	private final Recording recording = mock(Recording.class);

	private final SuspendClock suspends = new SuspendClock(properties(), clock, nanos::get);

	private final StreamWatchdog watchdog = new StreamWatchdog(supervisor, gaps, sessions,
			properties(), clock, suspends);

	/**
	 * The two clocks disagreeing IS the suspend.
	 *
	 * <p>{@code Clock} advances while a machine is asleep and {@code nanoTime}
	 * does not, so the difference between them over one interval measures exactly
	 * how long the machine was away — and nothing else can. This is the only check
	 * that can name what happened, which is why {@code SLEEP} is worth a cause of
	 * its own: sleep is the largest single source of holes in the existing corpus,
	 * and a gap attributed to it needs no further investigation.
	 */
	@Test
	void tearsTheStreamDownWhenTheMachineSuspended() {
		recordingSince(START);
		watchdog.check();

		advance(Duration.ofMinutes(10), Duration.ofSeconds(10));
		watchdog.check();

		// The gap starts when the machine went away, not when the check noticed:
		// ten minutes of wall clock passed and ten seconds of it was real.
		verify(supervisor).reconnect(GapCause.SLEEP,
				START.plusSeconds(600).minusSeconds(590));
	}

	/** Jitter and NTP slew are milliseconds; a suspend is seconds to hours. */
	@Test
	void doesNotMistakeSchedulingJitterForASuspend() {
		recordingSince(START);
		watchdog.check();

		advance(Duration.ofMillis(10_150), Duration.ofSeconds(10));
		watchdog.check();

		verify(supervisor, never()).reconnect(any(), any());
	}

	/**
	 * Silence past the timeout is a dead stream whatever the socket believes.
	 *
	 * <p>Betfair sends a heartbeat every five seconds precisely so that a quiet
	 * book and a dead connection can be told apart, which is why the read loop
	 * timestamps every frame rather than only the ones carrying market changes.
	 */
	@Test
	void tearsItDownWhenNothingHasArrived() {
		Instant lastFrame = START.minusSeconds(45);
		recordingSince(lastFrame);

		watchdog.check();

		verify(supervisor).reconnect(GapCause.SILENCE, lastFrame);
	}

	@Test
	void leavesAHealthyStreamAlone() {
		recordingSince(START.minusSeconds(3));

		watchdog.check();

		verify(supervisor, never()).reconnect(any(), any());
	}

	/**
	 * A healthy stream says so in the ledger, every interval.
	 *
	 * <p>The heartbeat is what stops {@code ended_at is null} meaning both "in
	 * progress" and "killed with nothing left to stamp an ending" (#129).
	 */
	@Test
	void stampsTheSessionAsAliveWhileRecording() {
		when(recording.sessionId()).thenReturn(41L);
		recordingSince(START.minusSeconds(3));

		watchdog.check();

		verify(sessions).seen(41L);
	}

	/** Nothing recording, nothing to stamp — and no row invented for it. */
	@Test
	void stampsNothingWhenNothingIsBeingRecorded() {
		when(supervisor.current()).thenReturn(null);

		watchdog.check();

		verifyNoInteractions(sessions);
	}

	/**
	 * A database that cannot take the heartbeat must not stop the watchdog
	 * looking for the two things it exists to catch — the spill indicator already
	 * reports an unreachable database, and a silence nobody notices is worse than
	 * a session that looks abandoned for a while.
	 */
	@Test
	void keepsWatchingWhenTheHeartbeatCannotBeWritten() {
		when(recording.sessionId()).thenReturn(41L);
		Mockito.doThrow(new IllegalStateException("database is away"))
				.when(sessions).seen(anyLong());
		recordingSince(START.minusSeconds(3));

		watchdog.check();

		advance(Duration.ofMinutes(10), Duration.ofSeconds(10));
		watchdog.check();

		verify(supervisor).reconnect(eq(GapCause.SLEEP), any());
	}

	/**
	 * A suspend is reported as a suspend, not as the silence it also causes.
	 *
	 * <p>Both conditions are true after a sleep — no frames arrived, and the
	 * clocks disagree — and the cause that reaches the ledger decides whether a
	 * reader has to investigate the gap or already knows what it was.
	 */
	@Test
	void reportsASuspendAsSleepEvenThoughItAlsoLooksLikeSilence() {
		recordingSince(START);
		watchdog.check();

		advance(Duration.ofMinutes(10), Duration.ofSeconds(10));
		when(recording.lastFrameAt()).thenReturn(START);
		watchdog.check();

		verify(supervisor).reconnect(eq(GapCause.SLEEP), any());
		verify(supervisor, never()).reconnect(eq(GapCause.SILENCE), any());
	}

	/**
	 * With no session open there is nothing to tear down and nothing to attach a
	 * gap to; the space between two {@code capture_session} rows already says the
	 * recorder was not running.
	 */
	@Test
	void staysQuietWhenNothingIsBeingRecorded() {
		when(supervisor.current()).thenReturn(null);

		watchdog.check();
		advance(Duration.ofMinutes(10), Duration.ofSeconds(10));
		watchdog.check();

		verify(supervisor, never()).reconnect(any(), any());
	}

	private void recordingSince(Instant lastFrame) {
		when(supervisor.current()).thenReturn(recording);
		when(recording.lastFrameAt()).thenReturn(lastFrame);
	}

	private void advance(Duration wall, Duration monotonic) {
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

	/** A clock a test can suspend a machine with. */
	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
