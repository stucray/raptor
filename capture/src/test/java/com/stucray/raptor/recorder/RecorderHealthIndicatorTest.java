package com.stucray.raptor.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
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
		when(supervisor.connectionStatuses()).thenReturn(List.of(new RecorderSupervisor
				.ConnectionStatus(0, RecorderState.RECORDING, NOW.minusSeconds(90), 0, recording)));
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
		when(supervisor.connectionStatuses()).thenReturn(List.of());
		when(supervisor.stateSince()).thenReturn(NOW.minus(Duration.ofHours(6)));

		Health health = indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("state", "IDLE")
				.containsEntry("secondsInState", 21_600.0)
				.doesNotContainKey("sessionId");
	}

	/**
	 * Every connection in its own block, and the worst at the top (#66).
	 *
	 * <p>The top-level fields keep their names, because the heartbeat, keep-awake
	 * and the post-boot check read them. They report the quietest recording,
	 * which is the one a stalled stream would be: a healthy connection beside a
	 * silent one must not average the silence away.
	 */
	@Test
	void reportsEachConnectionAndTheQuietestAtTheTop() {
		when(supervisor.state()).thenReturn(RecorderState.RECONNECTING);
		when(supervisor.stateSince()).thenReturn(NOW.minusSeconds(30));
		Recording busy = recording(7L, NOW.minusSeconds(2));
		Recording quiet = recording(8L, NOW.minusSeconds(40));
		when(supervisor.connectionStatuses()).thenReturn(List.of(
				new RecorderSupervisor.ConnectionStatus(0, RecorderState.RECORDING,
						NOW.minusSeconds(600), 0, busy),
				new RecorderSupervisor.ConnectionStatus(1, RecorderState.RECORDING,
						NOW.minusSeconds(300), 0, quiet),
				new RecorderSupervisor.ConnectionStatus(2, RecorderState.RECONNECTING,
						NOW.minusSeconds(30), 3, null)));

		Health health = indicator.health();

		assertThat(health.getDetails()).containsEntry("state", "RECONNECTING")
				.containsEntry("sessionId", 8L)
				.containsEntry("secondsSinceLastFrame", 40.0);
		assertThat(health.getDetails().get("connections")).asInstanceOf(InstanceOfAssertFactories.LIST)
				.containsExactly(
						Map.of("connectionSlot", 0, "state", "RECORDING", "secondsInState", 600.0,
								"consecutiveFailedAttempts", 0, "sessionId", 7L, "framed", 0L,
								"written", 0L, "secondsSinceLastFrame", 2.0),
						Map.of("connectionSlot", 1, "state", "RECORDING", "secondsInState", 300.0,
								"consecutiveFailedAttempts", 0, "sessionId", 8L, "framed", 0L,
								"written", 0L, "secondsSinceLastFrame", 40.0),
						Map.of("connectionSlot", 2, "state", "RECONNECTING", "secondsInState", 30.0,
								"consecutiveFailedAttempts", 3));
	}

	private static Recording recording(long sessionId, Instant lastFrame) {
		Recording recording = mock(Recording.class);
		when(recording.sessionId()).thenReturn(sessionId);
		when(recording.lastFrameAt()).thenReturn(lastFrame);
		return recording;
	}

	/** Standby is single-writer working, not a fault. */
	@Test
	void standbyIsUp() {
		when(supervisor.state()).thenReturn(RecorderState.STANDBY);
		when(supervisor.connectionStatuses()).thenReturn(List.of());
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
		when(supervisor.connectionStatuses()).thenReturn(List.of());
		when(supervisor.stateSince()).thenReturn(NOW);

		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}
}
