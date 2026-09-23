package com.stucray.raptor.recorder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notices that the stream has stopped, when the socket will not say so.
 *
 * <p>Waiting for an {@code IOException} is not a detection strategy. A suspended
 * laptop leaves TCP connections that look perfectly healthy until a write
 * eventually fails, which can take minutes — minutes of a match in which nothing
 * is recorded and nothing complains. Both checks here exist to shorten that to
 * one watchdog interval.
 *
 * <ol>
 * <li><b>Silence.</b> Betfair sends a heartbeat every five seconds, so a stream
 * that has said nothing for the timeout is gone whatever the socket believes.
 * <li><b>A clock jump.</b> Wall time advancing materially more than monotonic
 * time means the machine was suspended. This is the only check that can name
 * what happened rather than merely that something did, which is why
 * {@code SLEEP} is a separate cause: sleep is the largest single source of holes
 * in the existing corpus, and a gap attributed to it needs no further
 * investigation. The measurement itself lives in {@link SuspendClock}, because
 * this class is not the only thing that has to consult it — see #264.
 * </ol>
 *
 * <p>Both end the same way — tear the stream down and reconnect — because there
 * is nothing else to do with a connection that is not delivering. What differs
 * is what gets written down.
 *
 * <p>Reconnecting buys less than it appears to, and the honest version matters:
 * Betfair replays from {@code clk} only a bounded delta, minutes rather than
 * tens of them, so a long sleep loses the intervening book permanently. The goal
 * is to bound the loss and record it as a fact, not to pretend it can be
 * recovered.
 */
@Component
class StreamWatchdog {

	private static final Logger log = LoggerFactory.getLogger(StreamWatchdog.class);

	private final RecorderSupervisor supervisor;
	private final CaptureGaps gaps;
	private final CaptureSessions sessions;
	private final RecorderProperties.Watchdog properties;
	private final Clock clock;
	private final SuspendClock suspends;

	private boolean heartbeatFailing;

	@Autowired
	StreamWatchdog(RecorderSupervisor supervisor, CaptureGaps gaps, CaptureSessions sessions,
			RecorderProperties properties, Clock clock, SuspendClock suspends) {
		this.supervisor = supervisor;
		this.gaps = gaps;
		this.sessions = sessions;
		this.properties = properties.watchdog();
		this.clock = clock;
		this.suspends = suspends;
	}

	@Scheduled(fixedDelayString = "${raptor.recorder.watchdog.interval:10s}",
			initialDelayString = "${raptor.recorder.watchdog.interval:10s}")
	void check() {
		Instant wall = clock.instant();
		// Sampled before anything else, and unconditionally: the baseline has to
		// move on whether or not there is a session, or the next tick reports this
		// same interval again. What changed in #264 is where the measurement goes
		// — SuspendClock keeps it, so a gap written by the read loop a moment
		// later can still be named SLEEP.
		SuspendClock.Suspend slept = suspends.sample();

		Recording current = supervisor.current();
		if (current == null) {
			// Nothing to tear down. The measurement is not lost by returning here:
			// either the supervisor has already consulted SuspendClock while writing
			// the gap, or it is about to and will find the sample above.
			return;
		}
		if (slept != null) {
			log.warn("the machine appears to have suspended for {}s; tearing the stream down",
					slept.duration().toSeconds());
			supervisor.reconnect(GapCause.SLEEP, slept.from());
			return;
		}
		Instant lastFrame = current.lastFrameAt();
		Duration silence = Duration.between(lastFrame, wall);
		if (silence.compareTo(properties.silenceTimeout()) > 0) {
			log.warn("no frame for {}s (timeout {}s); the socket claims to be alive and is not",
					silence.toSeconds(), properties.silenceTimeout().toSeconds());
			supervisor.reconnect(GapCause.SILENCE, lastFrame);
			return;
		}
		heartbeat(current.sessionId());
	}

	/**
	 * Stamp the session as alive, so a row left open by a kill can be told from
	 * one that is still recording (#129).
	 *
	 * <p>Last of the three, and swallowing its own failure, because the checks
	 * above are what this class is for: a database that cannot take an update is
	 * already reported by the spill indicator, and a watchdog that threw here
	 * would stop looking for the silence and the suspend it exists to catch. Said
	 * once per outage rather than every ten seconds — the timer is the reason a
	 * warning here would drown the log it belongs in.
	 */
	private void heartbeat(long sessionId) {
		try {
			sessions.seen(sessionId);
			heartbeatFailing = false;
		} catch (RuntimeException e) {
			if (!heartbeatFailing) {
				heartbeatFailing = true;
				log.warn("could not stamp the capture session as alive ({}); an open session row "
						+ "may look abandoned until this recovers", e.getMessage());
			}
		}
	}

}
