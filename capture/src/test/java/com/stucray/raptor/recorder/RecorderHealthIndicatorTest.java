package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * What an operator sees, and — more importantly — what does not make them look.
 *
 * <p>The verdict is deliberately hard to turn red. A second instance reporting
 * itself unhealthy for standing by would train everyone to ignore the signal,
 * and single-writer working is the opposite of a fault.
 */
class RecorderHealthIndicatorTest {

	private static final Instant NOW = Instant.parse("2026-09-02T18:00:00Z");

	private final RecorderSupervisor supervisor = mock(RecorderSupervisor.class);
	private final RecorderHealthIndicator indicator = new RecorderHealthIndicator(supervisor,
			Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());

	@Test
	void reportsTheSessionAndItsSilenceWhileRecording() {
		when(supervisor.stateSince()).thenReturn(NOW.minusSeconds(90));
		Recording recording = mock(Recording.class);
		when(recording.sessionId()).thenReturn(7L);
		when(recording.framed()).thenReturn(1_200L);
		when(recording.written()).thenReturn(1_190L);
		when(recording.lastFrameAt()).thenReturn(NOW.minusSeconds(4));
		when(supervisor.current()).thenReturn(recording);
		when(supervisor.state()).thenReturn(RecorderState.RECORDING);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("state", "RECORDING")
				.containsEntry("sessionId", 7L)
				.containsEntry("framed", 1_200L)
				.containsEntry("written", 1_190L)
				.containsEntry("secondsSinceLastFrame", 4.0)
				.containsEntry("secondsInState", 90.0);
	}

	/**
	 * Idle is UP, and dated.
	 *
	 * <p>The pair this endpoint exists to separate (#144): overnight with an
	 * empty horizon reads IDLE and is correct, where the same forty minutes spent
	 * failing to reach Betfair reads RECONNECTING. Neither is red — a recorder
	 * with nothing to record is not a fault — so the clock is what an operator
	 * actually judges on, and it must be there when there is no session to
	 * report.
	 */
	@Test
	void idleIsUpAndCarriesHowLongItHasBeenIdle() {
		when(supervisor.state()).thenReturn(RecorderState.IDLE);
		when(supervisor.current()).thenReturn(null);
		when(supervisor.stateSince()).thenReturn(NOW.minus(Duration.ofHours(6)));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("state", "IDLE")
				.containsEntry("secondsInState", 21_600.0)
				.doesNotContainKey("sessionId");
	}

	/** Standby is single-writer working, not a fault. */
	@Test
	void standbyIsUp() {
		when(supervisor.state()).thenReturn(RecorderState.STANDBY);
		when(supervisor.current()).thenReturn(null);
		when(supervisor.stateSince()).thenReturn(NOW);

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("state", "STANDBY")
				.doesNotContainKey("sessionId");
	}

	/** And so is having nothing to record from, which is this app's state today. */
	@Test
	void noSourceIsUp() {
		when(supervisor.state()).thenReturn(RecorderState.NO_SOURCE);
		when(supervisor.current()).thenReturn(null);
		when(supervisor.stateSince()).thenReturn(NOW);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}
}
