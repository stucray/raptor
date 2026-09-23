package com.stucray.raptor.recorder;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Keeps a capture running: one lease, one stream at a time, reconnect forever.
 *
 * <p>This is what replaces the LaunchAgent. The agent gave three things for
 * free, and each has to be earned back explicitly here: only one recorder ever
 * ran (the lease), a run had a definite beginning and end (the session ledger),
 * and a night that died was visible as a job that exited (the gap ledger and the
 * health endpoint). What it never gave — recovery inside a match — is the reason
 * for the trade.
 *
 * <p><b>A disconnect is not an error.</b> Betfair closes long-lived connections
 * on its own schedule and says so in its documentation; a stream that ends is a
 * stream to rebuild, not an incident. What must never happen is a disconnect
 * going unrecorded, because a gap nobody wrote down becomes a gap somebody has
 * to infer later from timestamp arithmetic — which is the apparatus this whole
 * design exists to replace.
 *
 * <p>The supervisor's own thread does nothing but wait: it starts a recording,
 * blocks until that recording's source ends, records the gap, and starts
 * another. All the work is on the two threads inside the recording, so nothing
 * here can starve the loop draining the socket.
 */
@Component
class RecorderSupervisor implements SmartLifecycle, RecorderStatus {

	private static final Logger log = LoggerFactory.getLogger(RecorderSupervisor.class);

	private final ObjectProvider<StreamSourceFactory> factories;
	private final RecorderPipeline pipeline;
	private final CaptureSessions sessions;
	private final CaptureLease lease;
	private final CaptureGaps gaps;
	private final SuspendClock suspends;
	private final RecorderProperties properties;
	private final Clock clock;

	private volatile RecorderState state = RecorderState.STOPPED;
	private volatile Instant stateSince;
	private volatile boolean running;
	private volatile @Nullable Recording recording;
	/**
	 * Attempts in a row that recorded nothing.
	 *
	 * <p>An attempt records nothing if the stream could not be opened at all, or
	 * if the session it opened ended having written no message. Both spellings
	 * count, because the deterministic failures this exists for are split across
	 * them: a subscription refusal produces an empty session, while a revoked app
	 * key, an invalid session token and upstream maintenance never get as far as
	 * one.
	 *
	 * <p>Written only by the supervisor thread and read by health, so an atomic
	 * rather than a plain int — and reset rather than decremented, because the
	 * question is "is it failing NOW", not "how often has it failed".
	 */
	private final AtomicInteger consecutiveFailed = new AtomicInteger();
	private volatile @Nullable Thread supervisor;
	private volatile @Nullable StreamSourceFactory factory;

	/** Why the stream in flight is being torn down, when it is not just ending. */
	private final AtomicReference<@Nullable PendingGap> pending = new AtomicReference<>();

	RecorderSupervisor(ObjectProvider<StreamSourceFactory> factories, RecorderPipeline pipeline,
			CaptureSessions sessions, CaptureLease lease, CaptureGaps gaps, SuspendClock suspends,
			RecorderProperties properties, Clock clock) {
		this.factories = factories;
		this.pipeline = pipeline;
		this.sessions = sessions;
		this.lease = lease;
		this.gaps = gaps;
		this.suspends = suspends;
		this.properties = properties;
		this.clock = clock;
		this.stateSince = clock.instant();
	}

	@Override
	public void start() {
		if (!properties.enabled()) {
			enter(RecorderState.DISABLED);
			log.info("recorder disabled by configuration; this instance will not capture");
			return;
		}
		StreamSourceFactory factory = factories.getIfAvailable();
		if (factory == null) {
			// Said at INFO and not WARN on purpose: an instance with the live stream
			// switched off, or with no credentials to open one, is a correct
			// application in a correct state — and a warning every boot is how a
			// real one stops being read.
			enter(RecorderState.NO_SOURCE);
			log.info("recorder enabled but no stream source is configured; nothing to capture "
					+ "(set raptor.betfair.stream.enabled and supply credentials)");
			return;
		}
		if (!lease.acquire()) {
			enter(RecorderState.STANDBY);
			return;
		}
		reconcileOpenSessions();
		this.factory = factory;
		running = true;
		enter(RecorderState.RECONNECTING);
		Thread thread = Thread.ofVirtual().name("recorder-supervisor").start(() -> supervise(factory));
		supervisor = thread;
		log.info("recorder supervising {}", factory.describe());
	}

	/**
	 * Close whatever the last recorder left open before opening anything new.
	 *
	 * <p>Only ever reached with the lease in hand, which is the whole argument:
	 * the lease is a session-level advisory lock, so a killed JVM's is released
	 * by PostgreSQL within seconds. Holding it proves nothing else is writing,
	 * and every still-open row therefore belongs to a process that is gone —
	 * which is the only safe moment to say so (#129).
	 *
	 * <p>WARN and not INFO. A session with no ending means this instance was
	 * killed rather than stopped, and the previous spelling of that fact was an
	 * absence nobody was looking for.
	 */
	private void reconcileOpenSessions() {
		List<Long> abandoned = sessions.abandonOpenSessions(
				"no ending recorded; closed at its last heartbeat by the next recorder to "
						+ "take the lease");
		if (!abandoned.isEmpty()) {
			log.warn("closed {} capture session(s) that were left open by a recorder that did "
					+ "not stop cleanly: {}", abandoned.size(), abandoned);
		}
	}

	/**
	 * Tear the current stream down and build another, recording what was lost.
	 *
	 * <p>Returns immediately. The watchdog calls this from its scheduled thread,
	 * and that thread must not be held for however long a socket takes to close —
	 * the next check is what notices if this reconnect itself gets stuck.
	 *
	 * @param since when the stream was last known to be arriving
	 */
	void reconnect(GapCause cause, Instant since) {
		Recording current = recording;
		if (current == null || !running) {
			return;
		}
		// ACCUMULATE, never simply replace. A teardown takes longer than the
		// watchdog interval often enough to matter, so a second request routinely
		// arrives for the same closing recording — and taking the later `since`
		// would move the start of the gap forward and under-report the outage.
		// The cause is no longer decided here at all (see closeAndRecord), but the
		// instant still is, and the earliest one is the honest answer: the
		// recorder stopped receiving when it stopped receiving.
		pending.accumulateAndGet(new PendingGap(cause, since), RecorderSupervisor::earliest);
		// Stop reading only. The writer drains what is already framed and the
		// session is closed by the supervisor thread, in that order — losing
		// messages that survived the disconnect would be a poor way to react to a
		// disconnect.
		current.requestStop();
	}

	private void supervise(StreamSourceFactory factory) {
		while (running) {
			Recording current = null;
			try {
				StreamSource source = factory.open();
				// Cleared as the new stream starts, not as the old one ends: a
				// reconnect requested against a recording that was already closing
				// would otherwise sit here and mislabel the NEXT gap with a cause
				// that belonged to the previous stream.
				pending.set(null);
				current = pipeline.start(source, CaptureOrigin.RESIDENT);
				recording = current;
				enter(RecorderState.RECORDING);
				current.awaitSource();
			} catch (IOException e) {
				if (running) {
					// Counted, not merely logged. A connect that never became a
					// session is the shape a revoked key or an invalid token takes,
					// and #171's counter — which only ever saw sessions — was blind
					// to every one of them.
					consecutiveFailed.incrementAndGet();
					log.warn("could not open a stream from {} ({})", factory.describe(),
							e.getMessage());
				}
				// Silent when the recorder is stopping: a factory interrupted while
				// waiting for a fixture to enter scope has not failed at anything, and
				// a warning on every tidy shutdown is how a log stops being read.
			} catch (InterruptedException e) {
				// The interrupt is NOT restored, which is deliberate and is the one
				// place in this file worth arguing about. What runs next, in the
				// finally below, is the session's last commit and its ended_at stamp
				// — and HikariCP answers a connection request on an interrupted
				// thread by refusing it, so restoring the flag here would spill the
				// final batch of a match every time the application was shut down
				// tidily. `running` is what stops the loop; the flag has done its
				// job by getting us here.
				running = false;
			} finally {
				closeAndRecord(current);
			}
			if (running) {
				enter(RecorderState.RECONNECTING);
				pause(reconnectDelay());
			}
		}
		enter(RecorderState.STOPPED);
	}

	/**
	 * End the recording and write down the gap it leaves behind.
	 *
	 * <p>The gap ends here, when the stream was torn down — not when the next one
	 * connects. The interval between the two is a different fact with a different
	 * answer: it is the space between this session's {@code ended_at} and the next
	 * session's {@code started_at}, which is legible without anyone writing a row
	 * for it, and which stays legible when the recorder is stopped rather than
	 * reconnected.
	 */
	private void closeAndRecord(@Nullable Recording current) {
		if (current == null) {
			return;
		}
		Instant lastFrame = current.lastFrameAt();
		try {
			current.close();
		} catch (IOException e) {
			log.warn("capture session {} did not close cleanly", current.sessionId(), e);
		}
		recording = null;
		if (running) {
			// Not while shutting down: a session ended by Ctrl-C having written
			// nothing is an orderly stop, and counting it would let three tidy
			// restarts in a row look like a crashloop.
			if (current.written() > 0) {
				consecutiveFailed.set(0);
			} else {
				int empty = consecutiveFailed.incrementAndGet();
				log.warn("capture session {} ended having written nothing ({} in a row)",
						current.sessionId(), empty);
			}
		}
		PendingGap gap = pending.getAndSet(null);
		if (!running && gap == null) {
			// An orderly shutdown is not a gap: the session's ended_at says exactly
			// when recording stopped, and inventing a gap row for it would put noise
			// in the one ledger whose value is that every row means something.
			return;
		}
		Instant now = clock.instant();
		String detail = "framed=" + current.framed() + " written=" + current.written();
		// What was asked for, which is not necessarily what happened.
		GapCause requested = gap == null ? GapCause.DISCONNECT : gap.cause();
		Instant from = gap == null ? lastFrame : gap.since();

		// ALREADY DECIDED BY THE CLOCK. The watchdog names a suspend from the same
		// measurement this would consult, so asking twice could only disagree with
		// itself.
		if (requested == GapCause.SLEEP) {
			gaps.record(current.sessionId(), from, now, GapCause.SLEEP, detail);
			return;
		}

		// THE CLOCK OVERRULES BOTH OTHER CAUSES, and #293 is why it has to overrule
		// SILENCE and not just DISCONNECT.
		//
		// #264 was "whichever detector notices first names the gap". #290 answered
		// the socket-first half — the read loop's timeout expires while the machine
		// is away and fires on resume — and left a second door open, through which
		// the very next clamshell close walked: the watchdog identified the suspend
		// correctly at 01:26:51 on 2026-09-17, and the tick TEN SECONDS LATER
		// overwrote it with SILENCE while the teardown was still in flight, because
		// the stream had of course delivered nothing while the machine was off.
		//
		// That is deterministic rather than unlucky. Any suspend longer than the
		// silence timeout leaves the stream silent for its whole duration, so the
		// following tick always relabels it; the 2026-09-12 close survived only
		// because its teardown finished inside one watchdog interval.
		//
		// So silence AFTER a suspend is an artefact of the suspend, and the
		// measurement that can tell says so. Asked here rather than corrected
		// afterwards because `raw` is append-only: there is no second chance at
		// this row.
		SuspendClock.Suspend suspend = suspends.straddling(from, now);
		if (suspend == null) {
			gaps.record(current.sessionId(), from, now, requested, detail);
			return;
		}
		// The measurement goes in the detail, because a reader of this row is
		// entitled to know the cause was inferred from a clock jump rather than
		// observed by the detector that named it — and, now, to see that it
		// overruled one that had.
		gaps.record(current.sessionId(), from, now, GapCause.SLEEP,
				detail + " suspended=" + suspend.duration().toSeconds() + "s"
						+ (gap == null ? "" : " (" + requested + " overruled)"));
	}

	/**
	 * How long to wait before trying again, and why it is not always the same.
	 *
	 * <p>The flat delay is right for the case it was written for: a stream
	 * dropped mid-match, where every second of waiting is book lost. It is wrong
	 * for a <b>deterministic</b> refusal, which cannot succeed on a retry because
	 * the input that caused it has not changed —
	 * {@code SUBSCRIPTION_LIMIT_EXCEEDED} was refused identically sixteen times
	 * in four minutes on 2026-09-05 (#171), each attempt costing a TLS handshake
	 * and an authentication against Betfair's stream API, a session row and a gap
	 * row.
	 *
	 * <p>The two are told apart by <b>whether the last attempt recorded
	 * anything</b>, not by classifying error codes. A code list needs
	 * maintaining, fails open on anything unlisted, and would have to name every
	 * refusal in advance; "the last attempt recorded nothing" already captures
	 * the property that matters and names none. The mid-match case is untouched
	 * by construction: a stream that delivered a single message resets the count
	 * and the next reconnect is the full-speed one.
	 *
	 * <p>No jitter. There is one recorder by construction — the lease says so —
	 * so there is no herd to disperse.
	 */
	private Duration reconnectDelay() {
		int failed = consecutiveFailed.get();
		Duration delay = backoff(properties.reconnectDelay(), properties.reconnectDelayMax(), failed);
		if (delay.compareTo(properties.reconnectDelay()) > 0) {
			log.info("{} attempt(s) in a row recorded nothing; waiting {} before the next rather "
					+ "than {} — an unchanged request refused identically cannot succeed on a "
					+ "retry", failed, delay, properties.reconnectDelay());
		}
		return delay;
	}

	/**
	 * {@code min(base * 2^(failed - 1), max)}, and {@code base} below two.
	 *
	 * <p>Separated from the loop so the arithmetic can be asserted at its exact
	 * values rather than inferred from how long a test took.
	 */
	static Duration backoff(Duration base, Duration max, int failed) {
		if (failed < 2) {
			return base;
		}
		// Shift capped well short of 63, so a recorder left failing over a long
		// weekend cannot overflow its way back to a busy wait.
		Duration backed = base.multipliedBy(1L << Math.min(failed - 1, 20));
		return backed.compareTo(max) > 0 ? max : backed;
	}

	private void pause(Duration delay) {
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			running = false;
		}
	}

	@Override
	public void stop() {
		running = false;
		Recording current = recording;
		if (current != null) {
			current.requestStop();
		}
		Thread thread = supervisor;
		if (thread != null) {
			// Interrupted, not just asked. The supervisor thread spends most of its
			// life waiting — for a source to end, for the reconnect delay, or for a
			// fixture to enter scope on a quiet Tuesday — and a shutdown that only
			// sets a flag waits out whichever of those is in flight. Every wait on
			// this thread treats an interrupt as "stop", and the work that must not
			// be cut short is on the two threads inside the recording, which has
			// already been asked to stop above.
			thread.interrupt();
			try {
				thread.join(Duration.ofSeconds(30));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		supervisor = null;
		factory = null;
		lease.close();
		enter(RecorderState.STOPPED);
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/**
	 * Stopped early, before the pools and the transaction manager go.
	 *
	 * <p>The last thing a capture does is commit its tail and stamp
	 * {@code ended_at}; both need the database still to be there. A recorder shut
	 * down after its datasource would spill a perfectly good final batch and leave
	 * a session that looks like a crash.
	 */
	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 1000;
	}

	/**
	 * What to call what the recorder is doing now.
	 *
	 * <p>Derived rather than stored for the one case the supervisor thread cannot
	 * report on itself: while it waits inside {@code factory.open()} for a
	 * fixture to enter the horizon, it is blocked, and the field it set on the
	 * way in still says RECONNECTING. The factory is the only thing that knows
	 * the difference, so it is asked (#144).
	 */
	@Override
	public RecorderState state() {
		return awaitingSince() == null ? state : RecorderState.IDLE;
	}

	/**
	 * Since when the recorder has been in {@link #state()}.
	 *
	 * <p>What makes a state answerable. RECONNECTING for four seconds is a
	 * reconnect; RECONNECTING for forty minutes is an outage, and the word alone
	 * cannot tell them apart.
	 */
	@Override
	public Instant stateSince() {
		Instant awaiting = awaitingSince();
		return awaiting == null ? stateSince : awaiting;
	}

	/** When the factory started waiting for scope, if it is waiting at all. */
	private @Nullable Instant awaitingSince() {
		if (state != RecorderState.RECONNECTING) {
			// Only ever true between streams: a factory that has just handed back a
			// source still reports the wait that preceded it for as long as it takes
			// the pipeline to start, and a RECORDING recorder is not idle.
			return null;
		}
		StreamSourceFactory current = factory;
		return current == null ? null : current.awaitingSince();
	}

	private void enter(RecorderState next) {
		state = next;
		stateSince = clock.instant();
	}

	@Override
	public int consecutiveFailedAttempts() {
		return consecutiveFailed.get();
	}

	/** The session in flight, or {@code null} when nothing is being recorded. */

	@Nullable Recording current() {
		return recording;
	}

	/**
	 * Keep whichever request reaches further back, preferring a SLEEP cause.
	 *
	 * <p>The cause kept here barely matters — {@code closeAndRecord} asks the
	 * clock regardless — but keeping SLEEP when there is one saves the second
	 * lookup and keeps the field meaning what it says.
	 */
	private static PendingGap earliest(@Nullable PendingGap existing, PendingGap arriving) {
		if (existing == null) {
			return arriving;
		}
		Instant since = existing.since().isBefore(arriving.since())
				? existing.since() : arriving.since();
		GapCause cause = existing.cause() == GapCause.SLEEP || arriving.cause() == GapCause.SLEEP
				? GapCause.SLEEP
				: existing.cause();
		return new PendingGap(cause, since);
	}

	private record PendingGap(GapCause cause, Instant since) {}
}
