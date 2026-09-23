package com.stucray.raptor.recorder;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * What the recorder is doing, in the one place an operator already looks.
 *
 * <p>The verdict is deliberately hard to turn red. {@code STANDBY} is UP:
 * single-writer is working exactly as designed, and a second instance that
 * reported itself unhealthy would train everyone to ignore the signal.
 * {@code NO_SOURCE} is UP for the same reason — it is the correct state of this
 * application until S6 lands the live source, and so is {@code IDLE}, which is
 * what 03:00 on a Tuesday looks like when the horizon holds no fixture. What would justify DOWN is a
 * recorder that believes it is recording while nothing arrives, and the watchdog
 * reaches that case first by tearing the stream down; the seconds since the last
 * frame are reported here so the condition is visible either way.
 */
@Component
class RecorderHealthIndicator implements HealthIndicator {

	private final RecorderSupervisor supervisor;
	private final Clock clock;

	RecorderHealthIndicator(RecorderSupervisor supervisor, Clock clock, MeterRegistry meters) {
		this.supervisor = supervisor;
		this.clock = clock;

		Gauge.builder("raptor.recorder.silence.seconds", this, RecorderHealthIndicator::silence)
				.description("Seconds since the last frame arrived, or -1 when not recording")
				.register(meters);
	}

	@Override
	public Health health() {
		Health.Builder health = Health.up().withDetail("state", supervisor.state().name())
				// Always, and not only while recording. A state with no clock against
				// it cannot answer "is this normal?" — IDLE for six hours overnight is
				// correct, RECONNECTING for six hours is an outage nobody was told
				// about, and the word is the same length in both (#144).
				.withDetail("secondsInState", secondsInState());
		Recording current = supervisor.current();
		if (current != null) {
			health.withDetail("sessionId", current.sessionId())
					.withDetail("framed", current.framed())
					.withDetail("written", current.written())
					.withDetail("secondsSinceLastFrame", silence());
		}
		return health.build();
	}

	private double secondsInState() {
		return Duration.between(supervisor.stateSince(), clock.instant()).toMillis() / 1000d;
	}

	private double silence() {
		Recording current = supervisor.current();
		return current == null ? -1
				: Duration.between(current.lastFrameAt(), clock.instant()).toMillis() / 1000d;
	}
}
