package com.stucray.raptor.recorder;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
 *
 * <p>Each connection slot is reported in its own block (#66), and the top-level
 * fields keep their names and report the worst: the most urgent state, and the
 * quietest recording's session and silence.
 */
@Component
class RecorderHealthIndicator implements HealthIndicator {

	private final RecorderSupervisor supervisor;
	private final Clock clock;

	RecorderHealthIndicator(RecorderSupervisor supervisor, Clock clock, MeterRegistry meters) {
		this.supervisor = supervisor;
		this.clock = clock;

		Gauge.builder("raptor.recorder.silence.seconds", this, RecorderHealthIndicator::silence)
				.description("Seconds since the last frame arrived on the quietest connection, "
						+ "or -1 when not recording")
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
		List<RecorderSupervisor.ConnectionStatus> statuses = supervisor.connectionStatuses();
		// The top-level session fields keep their names and report the quietest
		// recording, the one a stalled stream would be (#66): the heartbeat alarms
		// on secondsSinceLastFrame, and the worst connection is the one it must see.
		Recording quietest = quietest(statuses);
		if (quietest != null) {
			health.withDetail("sessionId", quietest.sessionId())
					.withDetail("framed", quietest.framed())
					.withDetail("written", quietest.written())
					.withDetail("secondsSinceLastFrame", silence(quietest));
		}
		if (!statuses.isEmpty()) {
			health.withDetail("connections", statuses.stream().map(this::detail).toList());
		}
		return health.build();
	}

	/** One connection, as the per-slot block reports it (#66). */
	private Map<String, Object> detail(RecorderSupervisor.ConnectionStatus status) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("connectionSlot", status.connectionSlot());
		detail.put("state", status.state().name());
		detail.put("secondsInState",
				Duration.between(status.stateSince(), clock.instant()).toMillis() / 1000d);
		detail.put("consecutiveFailedAttempts", status.consecutiveFailedAttempts());
		Recording recording = status.recording();
		if (recording != null) {
			detail.put("sessionId", recording.sessionId());
			detail.put("framed", recording.framed());
			detail.put("written", recording.written());
			detail.put("secondsSinceLastFrame", silence(recording));
		}
		return detail;
	}

	private double secondsInState() {
		return Duration.between(supervisor.stateSince(), clock.instant()).toMillis() / 1000d;
	}

	/** The longest silence of any recording, or -1 when nothing is recording. */
	private double silence() {
		Recording quietest = quietest(supervisor.connectionStatuses());
		return quietest == null ? -1 : silence(quietest);
	}

	private double silence(Recording recording) {
		return Duration.between(recording.lastFrameAt(), clock.instant()).toMillis() / 1000d;
	}

	private static @Nullable Recording quietest(
			List<RecorderSupervisor.ConnectionStatus> statuses) {
		Recording quietest = null;
		for (RecorderSupervisor.ConnectionStatus status : statuses) {
			Recording recording = status.recording();
			if (recording != null && (quietest == null
					|| recording.lastFrameAt().isBefore(quietest.lastFrameAt()))) {
				quietest = recording;
			}
		}
		return quietest;
	}
}
